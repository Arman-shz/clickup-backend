# ClickUp Backend — Task Breakdown

Derived from `src/main/resources/swagger.yaml`, which is the **single source of truth**.
The Quarkus code is a starter and currently implements only `/hello`.

**Stack:** Java 25 · Quarkus 3.37.4 · fully reactive (Mutiny) · Hibernate Reactive Panache ·
Postgres 18.4 · native image for production.

**Working rule:** one task = one commit, message describing what actually changed.

Legend: `[ ]` todo · `[x]` done · `[~]` partly done · `[!]` blocked on a decision

---

## Open decisions (answer before the tasks that depend on them)

These are not implementation details — each one changes the shape of the code, and
guessing would mean inventing behaviour the spec does not state.

- ~~**D1 — Entity ID format.**~~ **Decided:** prefixed strings generated from a
  per-entity sequence (`usr_101`, `proj_1`), matching the spec's examples literally.
- ~~**D2 — HTTP port.**~~ **Decided:** 7575 plain, 7443 TLS. Port 3000 is the frontend,
  so the spec's server URL was describing something other than this API and has been
  corrected.
- ~~**D3 — Dev Services vs compose.**~~ **Decided:** dev uses the compose Postgres;
  Dev Services disabled. `./mvnw quarkus:dev` now requires `docker compose up -d` first.
- **D4 — File upload destination.** `/api/upload` returns both a local-looking
  `url: "/uploads/..."` and `cloudMetadata.provider: "Google Cloud Object Storage"`.
  Local disk, real GCS, or local disk with synthesised cloud metadata? Blocks: 9.x.
- ~~**D5 — `/api/projects/sync` semantics.**~~ **Decided:** upsert by `id`, delete
  nothing. A known id is updated, an unknown one is created under the id the client sent,
  and a project absent from the payload is left alone. The rejected reading — the payload
  is the complete server state, so remove the rest — is the only one under which the two
  sides truly converge, but one request from a client holding a stale list would destroy
  projects and, through the cascade, every task on them, while the response says nothing
  beyond "success". The cost of the choice, recorded in `swagger.yaml`: a project deleted
  on the client returns on the next sync, because the server cannot tell "deleted" from
  "not loaded".
- ~~**D6 — User provisioning.**~~ **Decided:** self-service registration.
  `POST /api/auth/register` takes a full name, a student id and a password and returns a
  signed-in session. Added to `swagger.yaml`, so the spec stays the source of truth.
  This is not the member invitation that was removed — nobody is invited, and no
  existing account is involved.
- **D7 — Central logging target.** `/api/logs` says it writes `/logs/app.log` and
  `/logs/error.log`. Files on the container filesystem, or a DB table? Files vanish on
  container restart unless a volume is added. Blocks: 10.1.
- ~~**D8 — Refresh token storage.**~~ **Decided by the spec itself:** the refreshToken
  example is a bare UUID. It carries no signature, so nothing about it can be verified
  without a lookup — it must be persisted. Table `refresh_tokens`.

---

## Phase 0 — Foundation

- [x] 0.1 Postgres via docker-compose (UTF8 + ICU `fa` collation, named volume)
- [x] 0.2 Add reactive extensions to `pom.xml`: `quarkus-reactive-pg-client`,
      `quarkus-hibernate-reactive-panache`, `quarkus-hibernate-validator`,
      `quarkus-smallrye-jwt`, `quarkus-smallrye-jwt-build`, `quarkus-rest-jackson`
- [x] 0.3 `application.properties`: reactive datasource pointing at compose, ports
      7575/7443, Dev Services disabled, Hibernate schema generation off
- [x] 0.4 Error model: `ErrorResponse` / `SuccessMessageResponse` records exactly as
      specified, plus exception mappers producing the spec's `400` / `401` / `404` bodies
- [x] 0.5 `GET /api/health` via `quarkus-smallrye-health` — `AsyncHealthCheck` keeps the
      DB probe reactive; `/q/health/*` for probes, `/api/health` for the spec's shape
- [x] 0.6 Deleted the `/hello` starter resource and its two tests, replaced by
      `HealthResourceTest` / `HealthResourceIT`
- [x] 0.7 TLS keystore so 7443 binds. Dev/test use a committed self-signed localhost
      certificate; prod reads `TLS_KEYSTORE_PATH` / `TLS_KEYSTORE_PASSWORD` from the
      environment and ships no key material.

**Phase 0 complete.**

## Phase 1 — Domain model & schema

- [x] 1.1 Liquibase changelog owns the schema (`quarkus-liquibase` + `quarkus-jdbc-postgresql`
      for the migration-only JDBC connection; Liquibase has no reactive driver)
- [x] 1.2 Entities: `User`, `Project`, `Task`, `WeeklyReport`, `ChatMessage`,
      `RefreshToken` with prefixed-string ids from per-entity sequences
- [x] 1.6 Seed data: 30 rows per table, Persian content, loaded via Liquibase.
      Guarded by the `seed` context so production does not inherit it
- [x] 1.3 `Task.assignees` is an array of user ids → join table `task_assignees`
- [x] 1.4 Panache reactive repositories returning `Uni` (no blocking calls)
- [x] 1.5 Confirm every spec schema field maps to a column with the right nullability

**Phase 1 complete.** Schema, seed and repositories are exercised by
`RepositoryTest` and `SpecSchemaCoverageTest` against the compose Postgres.

### What the 1.5 audit turned up

Every spec property reaches a column and the nullability matches. Four gaps are
real but belong to later phases — they are places where a bad request currently
reaches the database and comes back as a 500 instead of the spec's 400:

- ~~`users.email` is UNIQUE, and `PUT /api/users/me` can change it.~~ **Handled:**
  `DataConflictExceptionMapper` turns a unique-constraint violation into `409` instead of
  `500`. It was written for registration and covers 3.2 as a side effect.
- ~~Column lengths (`title` 255, `avatar` 1024, …) are unenforced above the database.~~
  **Handled:** `@Size` on every request DTO, in 3.1, 4.2 and 5.2.
- ~~`task_assignees.user_id` is a foreign key. Posting an unknown assignee id is a
  violation, so 5.2 must check the ids exist.~~ **Handled:** `TaskResource` checks both
  foreign keys — `projectId` and every assignee — and answers `400` naming the id.
- ~~`due_date` is a real `DATE` while the spec types `dueDate` as a string. A full
  datetime string will not parse.~~ **Handled:** only `YYYY-MM-DD` is accepted; a
  timestamp is refused rather than truncated.

Two deliberate departures, both recorded in the changelog: tasks cascade when their
project is deleted, and `tasks.project_id` is NOT NULL even though the `Task` schema
does not list it required — `CreateTaskRequest` does, so no task can exist without one.

## Phase 2 — Authentication

- [x] 2.1 Password hashing — **bcrypt** via `PasswordService`. Hashing and verification
      both run on the worker pool: bcrypt is tens of milliseconds of CPU by design, and
      on an event loop that is every other request's latency. `PasswordService` hands the
      result back on the originating Vert.x context so the Hibernate session survives the
      trip. Verified against the seeded hashes. Native-image confirmation still owed, 11.2
- [x] 2.2 User provisioning — `POST /api/auth/register` (name, studentId, password) →
      `201` with the same `LoginResponse` the login route returns, so registering signs
      you in. Duplicate student id → `409`. Every new account is a `student`; the API
      grants `admin` nowhere
- [x] 2.3 `POST /api/auth/login` → `LoginResponse` (accessToken, refreshToken, user);
      `401` on bad credentials. Seeded credentials: `99100111` / `Password123`.
      Verification is the security-jpa provider's, invoked through
      `IdentityProviderManager` — nothing in this project compares a password
- [x] 2.4 `POST /api/auth/refresh` → new token pair; `401` when invalid. Rotates:
      the presented token is revoked in the same transaction that issues its
      replacement, so each one is spendable exactly once
- [x] 2.5 JWT signing keys + `BearerAuth` wiring; RBAC for `admin` / `student`.
      RSA keypair (committed dev/test pair, `JWT_PRIVATE_KEY_LOCATION` /
      `JWT_PUBLIC_KEY_LOCATION` in prod), role carried in `groups`, `@RolesAllowed`
      verified against both roles. **The spec restricts nothing to `admin`** — the word
      appears only as a `role` value — so there is no differential rule to enforce, and
      inventing one would change documented behaviour
- [x] 2.6 `401` mapper returns the spec's exact `Unauthorized` body

**Phase 2 complete.** `AuthResourceTest`, `LoginTest`, `RefreshTest` and `BearerAuthTest`
cover it; `ProtectedProbeResource` exists only on the test classpath, because the first
endpoint the spec actually protects arrives in 3.1.

### What phase 2 turned up

- **Two writes were being dropped silently.** With `@WithTransaction` on `login`, the
  refresh token row was never inserted: the identity provider runs its own session, and
  work resumed after it is not inside a transaction opened before it. The response still
  looked right, because the token is a UUID minted in Java — only a test that counted
  rows caught it. Anything writing after an `authenticate()` call must open its
  transaction afterwards.
- **Error bodies were content-type dependent.** The mappers built an `ErrorResponse` but
  let JAX-RS negotiate the type, so a resource declaring `text/plain` answered a 401 with
  the record's `toString`. All five mappers now pin `application/json`.
- **`quarkus.http.auth.basic=false` is load-bearing.** security-jpa contributes a
  username/password identity provider, which is enough for Quarkus to offer HTTP Basic.
  A second, undocumented way in — one that ships credentials on every request — would
  bypass refresh-token rotation entirely.
- **Deactivation is honoured, and that is an interpretation.** `TeamMember.status` has no
  enum and no endpoint that writes it, so it can only be set against the database by an
  operator shutting an account down. Login and refresh both refuse a non-`active` account;
  ignoring it would make that act do nothing at all.

## Phase 3 — User profile

- [x] 3.1 `GET /api/users/me` → the spec's full `UserProfile`; `401` otherwise. The
      subject comes from the token's `upn`, never from the request, so the route takes no
      id and one account cannot address another. A token that verifies but names a
      deleted account gets the `401`, not a `404` — the spec documents no `404` here
- [x] 3.2 `PUT /api/users/me` — name, **email**, avatar, theme, language,
      notificationsEnabled. This line used to omit `email`; the spec's
      `UpdateUserProfileRequest` lists it, so the spec won. A **merge**: a property the
      request does not send is left as it was, because the schema requires nothing and
      the alternative is a theme toggle wiping the account's name. The cost, and it is
      real: an avatar or email cannot be cleared once set, only replaced. Email is UNIQUE,
      so claiming another account's address is `409` — checked before the write so the
      message can name what collided, with `DataConflictExceptionMapper` behind it for the
      race. `role`, `studentId` and `status` are not in the schema and cannot be sent
- [x] 3.3 Enforce enums: `role` admin|student, `theme` light|dark, `language` fa|en.
      Three layers: the DTOs bind to the enum types so a bad value is refused before any
      query runs, two exception mappers give that refusal the spec's `400` body, and the
      `users_*_check` CHECK constraints stay as the last word. `role` is enforced by there
      being nowhere to send one — no request schema in the spec carries it

**Phase 3 complete.** `UserProfileTest`, `UpdateProfileTest` and `EnumEnforcementTest`
cover it. 101 tests green, and the 30 seeded rows are exactly as the changelog left them.

### What phase 3 turned up

- **Four different 400s, three of them with no body at all.** The spec has one
  `BadRequest` shape, and only bean-validation failures were producing it. An unknown enum
  value and malformed JSON returned `400` with an *empty* body and no content type; a
  wrong-typed field returned Quarkus's own `{"objectName":…,"attributeName":…,"line":…}`,
  which names Java classes. Fixed in two places because the failures arrive by two routes:
  `MismatchedInputException` reaches a mapper intact, while the Jackson reader wraps
  everything else in a `WebApplicationException` before any mapper is consulted, so
  `BadRequestBodyMapper` catches that type and rewrites **only** status-400-with-no-entity.
  It hands 403s and 404s straight back, and there are tests pinning that.
- **The spec documented one response for a route that has four.** `PUT /api/users/me`
  listed only `200`, for something behind BearerAuth that takes a body and writes a UNIQUE
  column. `400`, `401` and `409` are now written down, along with the merge semantics.
- **The enums' own rejection messages are part of the API now.** They reach the client
  inside `errors`, so they say what was allowed: `theme: must be one of [light, dark], was:
  blue`. All five enums were reworded, including `TaskStatus` and `TaskPriority` — the
  mechanism is shared, so 5.5 inherits it and is left with the task routes themselves.
- **`role` has no enforcement point because it has no input.** Registration hard-codes
  `student` and no request schema in the spec carries a role, so the test for it asserts
  that smuggling one changes nothing rather than that it is rejected.

## Phase 4 — Projects

- [x] 4.1 `GET /api/projects` → the full list, oldest first. **Projects are not owned:**
      the spec's `Project` has no owner field, `GET` is not scoped, and the document has
      no membership concept, so every authenticated account sees the same set
- [x] 4.2 `POST /api/projects` → `201` with a server-assigned `proj_<n>` and `createdAt`.
      Only `title` is required; the rest come back null
- [x] 4.3 `PUT /api/projects/{id}` → `200` / `404`. A **replacement**, not a merge —
      the body is `CreateProjectRequest`, the same schema `POST` uses, so an absent
      property means the same thing on both routes: null. Sending only a title clears the
      description, colour and icon. `PUT /api/users/me` merges instead, and the difference
      is the schemas: that one makes everything optional, this one requires a title, so a
      caller here is already sending the whole resource. A test on each side says so
- [x] 4.4 `DELETE /api/projects/{id}` → `200` / `404`. Takes the project's tasks with it
      via `ON DELETE CASCADE`, asserted rather than assumed
- [x] 4.5 `POST /api/projects/sync` — upsert by `id`, delete nothing (**D5**). Idempotent
      because the client's id is honoured; a duplicate id inside one payload is `400`
      rather than resolved by array order; `createdAt` is accepted and ignored

**Phase 4 complete.** `ProjectResourceTest` and `ProjectSyncTest` cover it. 137 tests
green, and the 30 seeded projects, tasks and users are exactly as the changelog left them.

### What phase 4 turned up

- **A client-chosen id can poison the id sequence.** `/sync` creates rows under ids the
  client sent. Sync `proj_900000` and every later `POST /api/projects` keeps counting
  `proj_44`, `proj_45`, … until it reaches that number and fails on the primary key — a
  500 from an endpoint that did nothing wrong, months later, with nothing in the request
  to explain it. `ProjectRepository.insertWithClientId` now pushes the sequence past any
  `proj_<n>` it stores, with `GREATEST` so it can only ever move forward. Two tests pin it.
- **Four of the five routes documented no `401`,** and three no `400`, though all five sit
  behind `BearerAuth` and three take a required body. Written down now.
- **The spec never fixed a format for `createdAt`** — `type: string`, no format, no
  example. It is rendered as an ISO-8601 instant explicitly in `ProjectResponse` rather
  than left to Jackson's `Instant` handling, which is a configurable global and would let
  the wire format change from somewhere unrelated. A test pins the shape.

## Phase 5 — Tasks

- [x] 5.1 `GET /api/tasks` with optional `projectId` and `status` query filters. Both are
      independent and combine with AND; an absent **or empty** one means "do not filter",
      because `?projectId=` is what a form sends for a filter nobody set. An unknown
      `projectId` is `[]`, not an error — it is a filter, not a lookup
- [x] 5.2 `POST /api/tasks` → `201`. `status` defaults to `todo`. Both foreign keys are
      checked before the insert and every problem in the body is reported at once
- [x] 5.3 `PUT /api/tasks/{id}` → `200` / `404`. A **replacement**, the same rule as 4.3
      and for the same reason: the body is `CreateTaskRequest`, the schema `POST` uses.
      A status change must resend the whole task, or the task is left with no description,
      no priority, no due date and **nobody assigned**. The spec's summary for this route
      says "edit or change status", which reads like a partial update — the schema won, and
      consistency with `PUT /api/projects/{id}` decided it
- [x] 5.4 `DELETE /api/tasks/{id}` → `200` / `404`. Takes its `task_assignees` rows with
      it, which is the database's cascade rather than Hibernate's: Panache deletes by id
      with a bulk statement that never visits the element collection
- [x] 5.5 Enforce `status` and `priority` enums — on the body through 3.3's mechanism, and
      on the `status` query parameter by hand, which is the part that needed writing

**Phase 5 complete.** `TaskResourceTest` and `TaskValidationTest` cover it. 188 tests
green, and the 30 seeded tasks with their 50 assignee rows are as the changelog left them.

### What phase 5 turned up

- **Declaring `status` as the enum on the query parameter would have answered `404`.**
  JAX-RS turns a query parameter it cannot convert into a not-found, so `?status=archived`
  would have told a client the endpoint does not exist when what is wrong is the filter.
  It is read as a `String` and converted by hand for that reason alone.
- **A Jalali date is a valid Gregorian one.** `1405-05-24` parses without complaint as the
  year 1405, so nothing here can tell it from a mistake. The API takes Gregorian dates
  only; converting would mean guessing which calendar was meant. Stated in a test rather
  than left to be discovered.
- **A timestamp in `dueDate` is refused, not truncated.** Dropping the time from
  `2026-08-15T23:30:00Z` moves the deadline by a day for anyone east of UTC, and the
  response would say `201` either way.
- **The task schemas documented no `400`, no `401`, no `404` and no lengths**, and neither
  did `CreateProjectRequest` from phase 4 — that one is fixed here too rather than left
  inconsistent with the route beside it.

## Phase 6 — Weekly reports

- [x] 6.1 `GET /api/reports` → newest first, and **scoped by role**: an `admin` reads
      every report, anyone else reads only their own. This is the one resource in the spec
      that has an owner — `Project` and `Task` carry no creator, `WeeklyReport` carries
      `userId`, and hours worked and challenges are a personal record. The spec does not
      say so; it is a decision, recorded on the route in Persian as well as here
- [x] 6.2 `POST /api/reports` → `201`, stamping `userId` / `userName` / `submittedAt`.
      None of the three is in `CreateReportRequest`, so a report cannot be filed in
      someone else's name or backdated — asserted by sending all three and watching them
      be ignored. `userName` is a snapshot, so a later rename does not rewrite history

**Phase 6 complete.** `ReportResourceTest` covers it, from both sides of the role split.

## Phase 7 — Team members

- [x] 7.1 `GET /api/members` (read-only: members arrive by registering themselves,
      not by being added here). Every account is listed, inactive ones included — `status`
      is in the schema, which is only worth sending if the caller is meant to see them.
      Ordered by name in Postgres' ICU `fa` collation, not in Java

**Phase 7 complete.** `MemberResourceTest` covers it. 221 tests green across both phases,
and all five seeded tables are as the changelog left them.

### What phases 6 and 7 turned up

- **The same number reached the client in two shapes.** `POST /api/reports` echoed back
  whatever the client sent — `42` for an integer literal — while a later `GET` rendered
  `42.00` off the `NUMERIC(6,2)` column. The value is scaled to the column before the
  `201` is built. A test asserts both routes agree.
- **The role split has a dead end while registration hard-codes `student`.** The two
  seeded admins are the only accounts that will ever see the full history; nothing in the
  API can promote anyone. Worth knowing before a supervisor is expected to use this.
- **Neither numeric column has a `CHECK`.** `hoursWorked: -40` and `tasksCompleted: -1`
  were storable and readable. `@PositiveOrZero` is now the only thing stopping them —
  the constraint belongs in the database too, but the changelog is applied and adding one
  is a migration rather than an edit.
- **`GET /api/members` is the only route that must be checked for what it does *not*
  return.** It reads the same rows `/api/users/me` does, and `TeamMember` is four
  properties narrower — a directory that leaked `studentId` would be handing out half of
  everyone's credentials. A test compares the key set of every element exactly.

## Phase 8 — Live chat

- [ ] 8.1 `GET /api/chat/messages`
- [ ] 8.2 `POST /api/chat/messages` → `201`
- [ ] 8.3 `GET /api/chat/stream` — SSE `text/event-stream` returning `Multi<ChatMessage>`
- [ ] 8.4 Broadcast new messages to connected SSE clients
- [ ] 8.5 Verify SSE survives the native image build

## Phase 9 — File upload

- [!] 9.1 `POST /api/upload` multipart, 50 MB cap → `FileUploadResponse` — *needs D4*
- [ ] 9.2 Reject oversized uploads with the spec's error shape

## Phase 10 — Central logging

- [!] 10.1 `POST /api/logs` accepting level/message/context — *needs D7*

## Phase 11 — Production build

- [ ] 11.1 `./mvnw package -Dnative` with local GraalVM 25
- [ ] 11.2 Register reflection for entities/DTOs as native build requires
- [ ] 11.3 Build the native container image from `Dockerfile.native`
      (note: pulls a UBI base image — Docker Hub pulls are currently unreliable here)
- [ ] 11.4 Runtime datasource config via env vars, no baked-in credentials

## Phase 12 — Tests

- [ ] 12.1 `@QuarkusTest` per resource, asserting the spec's status codes and bodies
- [ ] 12.2 Test Postgres strategy (Dev Services vs the compose instance)
- [ ] 12.3 Persian text round-trips through the full HTTP → DB → HTTP path
- [ ] 12.4 Keep `GreetingResourceIT` equivalent: native integration test coverage

---

## Not in scope (removed from the spec deliberately)

2FA · password recovery · email/SMS delivery · member invitation. See commit `e760f9d`.

`POST /api/auth/register` is not a reintroduction of member invitation. Invitation meant
an existing user causing an account to be created for someone else, and needed the email
delivery that was also removed. Registration is a stranger creating their own account,
touching nobody else's, and sending nothing.
