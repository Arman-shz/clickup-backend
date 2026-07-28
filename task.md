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
- **D5 — `/api/projects/sync` semantics.** Spec says "Bulk Sync" and takes an array of
  full `Project` objects, but does not say whether it upserts, replaces the caller's
  whole project set, or merges by `id`. Blocks: 4.5.
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
- Column lengths (`title` 255, `avatar` 1024, …) are unenforced above the database.
  4.2 / 5.2 need `@Size` so over-long input is rejected before the insert.
- `task_assignees.user_id` is a foreign key. Posting an unknown assignee id is a
  violation, so 5.2 must check the ids exist.
- `due_date` is a real `DATE` while the spec types `dueDate` as a string. A full
  datetime string will not parse. 5.2 should reject it explicitly.

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

- [ ] 3.1 `GET /api/users/me`
- [ ] 3.2 `PUT /api/users/me` (name, avatar, theme, language, notificationsEnabled)
- [ ] 3.3 Enforce enums: `role` admin|student, `theme` light|dark, `language` fa|en

## Phase 4 — Projects

- [ ] 4.1 `GET /api/projects`
- [ ] 4.2 `POST /api/projects` → `201`
- [ ] 4.3 `PUT /api/projects/{id}` → `200` / `404`
- [ ] 4.4 `DELETE /api/projects/{id}` → `200` / `404`
- [!] 4.5 `POST /api/projects/sync` — *needs D5*

## Phase 5 — Tasks

- [ ] 5.1 `GET /api/tasks` with optional `projectId` and `status` query filters
- [ ] 5.2 `POST /api/tasks` → `201`
- [ ] 5.3 `PUT /api/tasks/{id}` (edit + status change)
- [ ] 5.4 `DELETE /api/tasks/{id}`
- [ ] 5.5 Enforce `status` and `priority` enums

## Phase 6 — Weekly reports

- [ ] 6.1 `GET /api/reports`
- [ ] 6.2 `POST /api/reports` → `201`, stamping `userId` / `userName` / `submittedAt`

## Phase 7 — Team members

- [ ] 7.1 `GET /api/members` (read-only: members arrive by registering themselves,
      not by being added here)

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
