# ClickUp Backend — Task Breakdown

Derived from `src/main/resources/swagger.yaml`, which is the **single source of truth**.
The Quarkus code is a starter and currently implements only `/hello`.

**Stack:** Java 25 · Quarkus 3.37.4 · fully reactive (Mutiny) · Hibernate Reactive Panache ·
Postgres 18.4 · native image for production.

**Working rule:** one task = one commit, message describing what actually changed.

Legend: `[ ]` todo · `[x]` done · `[!]` blocked on a decision

---

## Open decisions (answer before the tasks that depend on them)

These are not implementation details — each one changes the shape of the code, and
guessing would mean inventing behaviour the spec does not state.

- **D1 — Entity ID format.** Spec examples use prefixed strings: `usr_101`, `proj_1`,
  `msg_201`, and all `id` fields are `type: string`. Options: keep literal prefixed
  strings, use UUIDs rendered as strings, or bigint surrogate keys exposed as strings.
  Blocks: 1.2, and every resource that returns an `id`.
- **D2 — HTTP port.** Spec declares the dev server as `http://localhost:3000`; Quarkus
  defaults to 8080. Blocks: 0.3.
- **D3 — Dev Services vs compose.** With `quarkus-reactive-pg-client` on the classpath
  and no datasource URL, Quarkus starts its own throwaway Postgres in dev and ignores
  `docker-compose.yml`. Recommendation: point dev at compose, disable Dev Services.
  Blocks: 0.3.
- **D4 — File upload destination.** `/api/upload` returns both a local-looking
  `url: "/uploads/..."` and `cloudMetadata.provider: "Google Cloud Object Storage"`.
  Local disk, real GCS, or local disk with synthesised cloud metadata? Blocks: 9.x.
- **D5 — `/api/projects/sync` semantics.** Spec says "Bulk Sync" and takes an array of
  full `Project` objects, but does not say whether it upserts, replaces the caller's
  whole project set, or merges by `id`. Blocks: 4.5.
- **D6 — User provisioning.** There is deliberately no endpoint that creates a user
  (invite was removed), yet `/api/auth/login` must authenticate someone. Seed via
  migration, a dev-only seeder, or manual SQL? Blocks: 2.2.
- **D7 — Central logging target.** `/api/logs` says it writes `/logs/app.log` and
  `/logs/error.log`. Files on the container filesystem, or a DB table? Files vanish on
  container restart unless a volume is added. Blocks: 10.1.
- **D8 — Refresh token storage.** Spec shows opaque UUID refresh tokens, distinct from
  the JWT access token. Persisted table, or stateless signed token? Blocks: 2.3.

---

## Phase 0 — Foundation

- [x] 0.1 Postgres via docker-compose (UTF8 + ICU `fa` collation, named volume)
- [ ] 0.2 Add reactive extensions to `pom.xml`: `quarkus-reactive-pg-client`,
      `quarkus-hibernate-reactive-panache`, `quarkus-hibernate-validator`,
      `quarkus-smallrye-jwt`, `quarkus-smallrye-jwt-build`
- [!] 0.3 `application.properties`: reactive datasource pointing at compose, HTTP port,
      dev/prod profiles, Dev Services setting — *needs D2, D3*
- [ ] 0.4 Error model: `ErrorResponse` / `SuccessMessageResponse` records exactly as
      specified, plus exception mappers producing the spec's `400` / `401` / `404` bodies
- [ ] 0.5 `GET /api/health` — first vertical slice, proves reactive DB connectivity
      (returns `status`, `timestamp`, `database`)
- [ ] 0.6 Delete the `/hello` starter resource and its two tests once 0.5 replaces them

## Phase 1 — Domain model & schema

- [!] 1.1 Schema strategy: Flyway migrations vs `hibernate-orm.database.generation` —
      *decide alongside D1*
- [!] 1.2 Entities: `User`, `Project`, `Task`, `WeeklyReport`, `ChatMessage`,
      `RefreshToken` — *needs D1*
- [ ] 1.3 `Task.assignees` is an array of user ids → join table `task_assignees`
- [ ] 1.4 Panache reactive repositories returning `Uni`/`Multi` (no blocking calls)
- [ ] 1.5 Confirm every spec schema field maps to a column with the right nullability

## Phase 2 — Authentication

- [!] 2.1 Password hashing (no plaintext) — *pick algorithm; must work in native image*
- [!] 2.2 User seeding — *needs D6*
- [!] 2.3 `POST /api/auth/login` → `LoginResponse` (accessToken, refreshToken, user);
      `401` on bad credentials — *needs D8*
- [!] 2.4 `POST /api/auth/refresh` → new token pair; `401` when invalid — *needs D8*
- [ ] 2.5 JWT signing keys + `BearerAuth` wiring; RBAC for `admin` / `student`
- [ ] 2.6 `401` mapper returns the spec's exact `Unauthorized` body

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

- [ ] 7.1 `GET /api/members` (read-only; no provisioning endpoint by design)

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
