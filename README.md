# TaskFlow — Auth & Authorization Service

A secure REST API demonstrating JWT authentication, role-based access control, and ownership-based authorization in Spring Boot. This is Project 2 of a 3-project backend portfolio (Core REST API → **Auth & Authorization** → Production-grade Booking/Order System).

**Status:** 🚧 Day 7 — role-based access control is enforced. `permitAll()` is gone except for the four auth endpoints; writes require MANAGER (or ADMIN), and 401/403 responses match the API's normal error shape. Ownership rules and tests land over the following days (see [Roadmap](#roadmap) below).

---

## What this project proves

- End-to-end JWT access/refresh token lifecycle, including server-side revocation
- Authorization implemented as testable policy (ownership-check beans used from `@PreAuthorize`), not scattered `if` statements in controllers
- Unprompted handling of account abuse: lockout after repeated failed logins, rate limiting on auth endpoints
- Security-specific integration tests (401 vs 403 vs owner-only 200, locked accounts, tampered/expired tokens)

## Tech stack

- Java 17, Spring Boot 3.3.5
- Spring Web, Spring Data JPA, Spring Security, Bean Validation
- PostgreSQL (Docker)
- JWT (JJWT)
- JUnit 5, Mockito, Testcontainers
- springdoc-openapi (Swagger UI)
- Maven

## Getting started

### Prerequisites

- JDK 17+
- Maven 3.9+ (or use your IDE's bundled Maven)
- Docker + Docker Compose

### 1. Start Postgres

```bash
docker compose up -d
```

This starts Postgres in a container and maps it to **host port `5433`** (not the Postgres default `5432`). That's intentional — if you already have a local Postgres instance running on 5432, this avoids a port clash entirely. If you don't have that conflict, you're still fine leaving it on 5433; just make sure `spring.datasource.url` in `application.yml` keeps matching whatever port you choose.

### 2. Run the app

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` using the `dev` Spring profile (active by default), which points at the Postgres container from step 1.

### 3. Stop Postgres

```bash
docker compose down          # stop the container, keep data
docker compose down -v       # stop and wipe the volume (fresh DB next time)
```

## API (Day 7)

Only `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` are public. Everything else needs `Authorization: Bearer <accessToken>` at minimum; writes additionally need MANAGER or ADMIN.

| Method | Path | Auth |
|---|---|---|
| POST | `/auth/register` | public |
| POST | `/auth/login` | public |
| POST | `/auth/refresh` | public |
| POST | `/auth/logout` | public |
| POST | `/api/projects` | MANAGER+ |
| GET | `/api/projects/{id}` | any authenticated user |
| GET | `/api/projects` | any authenticated user, paginated |
| PUT | `/api/projects/{id}` | MANAGER+ |
| DELETE | `/api/projects/{id}` | MANAGER+ |
| POST | `/api/tasks` | MANAGER+ |
| GET | `/api/tasks/{id}` | any authenticated user |
| GET | `/api/tasks` | any authenticated user, paginated |
| GET | `/api/tasks/project/{projectId}` | any authenticated user, paginated |
| PUT | `/api/tasks/{id}` | MANAGER+ |
| DELETE | `/api/tasks/{id}` | MANAGER+ |
| GET | `/api/admin/users` | ADMIN only, paginated |
| PUT | `/api/admin/users/{id}/role` | ADMIN only — body: `{"role": "MANAGER"}` |

("MANAGER+" = MANAGER or ADMIN, via the role hierarchy — see [Design decisions](#design-decisions-living-section-updated-as-the-project-grows).)

### Try it out

```bash
# Register a new account
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "ahdy@taskflow.dev", "password": "Sup3rSecret"}'

# Weak password — rejected by the custom @StrongPassword validator
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "someone@taskflow.dev", "password": "weak"}'

# Same email twice — 409 Conflict
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "ahdy@taskflow.dev", "password": "Sup3rSecret"}'

# Log in with the account just registered
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "ahdy@taskflow.dev", "password": "Sup3rSecret"}'
# -> {"accessToken": "...", "refreshToken": "...", "tokenType": "Bearer"}

# Wrong password — same generic message as "no such account", on purpose
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "ahdy@taskflow.dev", "password": "wrongpassword"}'

# Refresh (swap in the refreshToken from login) — returns a brand new pair
# and revokes the one you just sent, so reusing it a second time now fails
curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "<paste refreshToken here>"}'

# Logout — revokes the token, always 204 even if it's already invalid
curl -i -X POST http://localhost:8080/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "<paste refreshToken here>"}'
```

`docker compose up -d` + `mvn spring-boot:run` also seeds two fixture users via `data.sql`:
- `seed.manager@taskflow.dev` (id `1`) — leftover from Day 3, placeholder password hash, **can't log in**.
- `seed.admin@taskflow.dev` / `Admin123!` (id `2`) — added today with a real bcrypt hash. This solves RBAC's bootstrap problem: `PUT /api/admin/users/{id}/role` is ADMIN-only, but every self-registered account is forced to USER (Day 4), so there'd otherwise be no way to create the very first ADMIN at all.

```bash
# Register + log in as a normal user — still USER role, can't create projects yet
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "manager-to-be@taskflow.dev", "password": "Sup3rSecret"}'
# -> id 3 on a fresh DB (1 and 2 are the seed fixtures above)

curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "manager-to-be@taskflow.dev", "password": "Sup3rSecret"}'
# -> save the accessToken as USER_TOKEN

# Trying to create a project as a plain USER — 403 Forbidden
curl -i -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"name": "Website Redesign", "ownerId": 3}'

# Log in as the seeded ADMIN — the only account that can promote anyone
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "seed.admin@taskflow.dev", "password": "Admin123!"}'
# -> save the accessToken as ADMIN_TOKEN

# Promote the new user to MANAGER
curl -X PUT http://localhost:8080/api/admin/users/3/role \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"role": "MANAGER"}'

# The SAME USER_TOKEN from before now works — roles are looked up live
# against the DB on every request (see Design decisions), not baked into
# the JWT, so there's no need to log in again after a promotion
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -d '{"name": "Website Redesign", "ownerId": 3}'

# No token at all — 401 Unauthorized, same ApiErrorResponse shape as every other error
curl -i http://localhost:8080/api/projects
```

## Project structure (Day 7)

```
src/main/java/com/ahdyahmed/taskflow/
├── TaskflowApplication.java             # @ConfigurationPropertiesScan
├── config/
│   ├── JpaAuditingConfig.java           # @EnableJpaAuditing
│   ├── PasswordEncoderConfig.java       # BCryptPasswordEncoder bean
│   ├── JwtProperties.java               # app.jwt.* bound as a record
│   ├── SecurityConfig.java              # stateless sessions, JWT filter, only /auth/* public
│   └── MethodSecurityConfig.java        # @EnableMethodSecurity + role hierarchy (ADMIN > MANAGER > USER)
├── security/
│   ├── JwtService.java                  # generate/validate access + refresh tokens
│   ├── JwtAuthenticationFilter.java     # OncePerRequestFilter, parses Bearer tokens
│   ├── AppUserPrincipal.java            # UserDetails wrapping the domain User
│   ├── CustomUserDetailsService.java    # UserDetailsService backed by UserRepository
│   ├── TokenHasher.java                 # SHA-256, used for refresh-token-at-rest storage
│   ├── SecurityErrorResponseWriter.java # shared JSON error writer for 401/403
│   ├── RestAuthenticationEntryPoint.java# 401 — no/invalid token
│   └── RestAccessDeniedHandler.java     # 403 — valid token, wrong role
├── controller/
│   ├── AuthController.java              # POST /auth/register, /login, /refresh, /logout
│   ├── AdminUserController.java         # ADMIN-only: list users, change role
│   ├── ProjectController.java
│   └── TaskController.java
├── service/
│   ├── AuthService.java
│   ├── AdminUserService.java            # class-level @PreAuthorize("hasRole('ADMIN')")
│   ├── ProjectService.java              # writes @PreAuthorize("hasRole('MANAGER')")
│   └── TaskService.java                 # writes @PreAuthorize("hasRole('MANAGER')")
├── mapper/
│   ├── UserMapper.java
│   ├── ProjectMapper.java
│   └── TaskMapper.java
├── validation/
│   ├── StrongPassword.java              # custom bean-validation annotation
│   └── StrongPasswordValidator.java
├── dto/
│   ├── request/   # RegisterRequest, LoginRequest, RefreshRequest, ChangeRoleRequest, ProjectCreateRequest, ProjectUpdateRequest, TaskCreateRequest, TaskUpdateRequest
│   └── response/  # UserResponse, AuthResponse, ProjectResponse, TaskResponse, ApiErrorResponse
├── exception/
│   ├── ResourceNotFoundException.java
│   ├── EmailAlreadyInUseException.java
│   ├── InvalidCredentialsException.java
│   ├── InvalidTokenException.java
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice, consistent JSON error shape
├── domain/
│   ├── entity/                          # BaseEntity, User, Project, Task, RefreshToken
│   └── enums/                           # Role, TaskStatus, TaskPriority
└── repository/
    ├── UserRepository.java
    ├── ProjectRepository.java
    ├── TaskRepository.java
    └── RefreshTokenRepository.java
```

## Roadmap

| Day(s) | Focus | Status |
|---|---|---|
| 1 | Project skeleton, Docker Postgres, domain entities | ✅ |
| 2 | Entity relationships, JPA auditing | ✅ |
| 3 | Basic CRUD (pre-security) | ✅ |
| 4 | Registration, BCrypt password hashing, custom password validator | ✅ |
| 5 | Spring Security config, JWT generation/validation | ✅ |
| 6 | Login, refresh token, logout | ✅ |
| 7 | Role-based access control (RBAC), custom 401/403 handlers | ✅ |
| 8-9 | Ownership rules, edge cases |  |
| 10-11 | Account lockout, rate limiting on auth endpoints |  |
| 12-13 | Email verification, password reset (mocked email) |  |
| 14-16 | Unit + integration tests, security test matrix |  |
| 17-18 | OpenAPI docs, architecture diagram, final README |  |

## Design decisions (living section, updated as the project grows)

- **Relationships deferred to Day 2:** Day 1 entities used plain foreign-key-style `Long` fields instead of `@ManyToOne`/`@ManyToMany` so the domain model and the JPA relationship layer landed as two distinct, reviewable commits.
- **Postgres on host port 5433:** avoids clashing with a default local Postgres install; see `docker-compose.yml` for the rationale inline.
- **No collection back-references on `User`:** `Project`/`Task` point at `User` via `@ManyToOne`, but `User` doesn't carry `@OneToMany` collections back (e.g. no `ownedProjects` list). Bidirectional collections on a "central" entity like `User` are a common source of accidental full-table fetches and Lombok `toString`/`equals` recursion. Lookups like "all projects owned by this user" are explicit repository queries instead (`ProjectRepository.findByOwner_Id`) — one extra method, much more predictable performance.
- **All associations are `FetchType.LAZY`:** loading a `Task` should never silently pull its `Project` and both `User`s along with it. Anywhere eager loading is actually wanted (e.g. a task list view), it'll be an explicit `@Query` with `JOIN FETCH` or a projection — not a change to the entity's default fetch type.
- **`BaseEntity` for id + auditing:** `id`, `createdAt`, `updatedAt` live in one `@MappedSuperclass` so every entity gets them consistently, and adding a new entity later can't forget to wire up auditing.
- **Equality is `id`-only:** entities use `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on just `id` (inherited via `callSuper = true`), which is the safe default for JPA — comparing every field would break as soon as lazy fields aren't loaded on one side.
- **`SecurityConfig` replaced `TemporaryOpenSecurityConfig` outright, as promised (Day 5):** the Day 1-4 placeholder is gone, not extended. That day's chain added `JwtAuthenticationFilter` and switched to `SessionCreationPolicy.STATELESS`, but every endpoint was still `permitAll()` — there was no login endpoint yet to obtain a token. See the Day 7 entry below for when `permitAll()` actually went away.
- **The JWT filter fails open, not closed:** a missing, malformed, expired, or wrong-type (refresh-as-access) token just leaves the request unauthenticated — it never rejects the request itself. Enforcement is authorization's job (the `authorizeHttpRequests` rules), not the filter's; conflating the two would make the filter impossible to reason about once real access rules land Day 7-9.
- **Access and refresh tokens carry a `type` claim:** both are HS256 JWTs signed with the same key, distinguished only by `type: "access"` / `type: "refresh"` and a different expiry. Without that claim, a leaked long-lived refresh token could be used directly as a short-lived access token — `JwtService.isAccessToken()` is checked explicitly wherever a token is expected to be an access token.
- **`AppUserPrincipal` wraps the domain `User`, not a generic Spring Security `User`:** ownership checks on Day 8-9 can call `principal.getUser()` and get the real id/email/role immediately, instead of every `@PreAuthorize` expression re-querying `UserRepository` by username.
- **`JwtProperties` as a `@ConfigurationProperties` record, not scattered `@Value`:** every JWT setting is declared once, is immutable, and fails fast at startup if `app.jwt.secret` is missing — instead of surfacing as an NPE the first time a token is signed.
- **Dev-only JWT secret has a real fallback, but it's flagged loudly:** `app.jwt.secret` defaults to a hardcoded string via `${JWT_SECRET:...}` so the app runs out of the box, but the yaml comment and this note both say explicitly: override it via `JWT_SECRET` for anything beyond local dev. HS256 needs ≥256 bits (32 bytes); the default is comfortably longer.
- **`ownerId`/`createdById` trusted from the request body:** there's no authenticated principal yet to derive them from (that changes once login exists and endpoints start reading `Authentication`). Called out with a doc comment directly on the affected DTO fields, not just here, so it's visible at the point of use.
- **Seed data via `data.sql`:** added on Day 3 back when there was no way to create a `User` through the API at all. Now that `/auth/register` exists (Day 4), this file is technically redundant — kept anyway as a quick fixed-id fixture for manual testing, and it's a one-line removal whenever that stops being useful.
- **PUT means full replace:** `TaskUpdateRequest`/`ProjectUpdateRequest` overwrite the fields they carry — in particular, omitting `assigneeId` on a task update clears the assignee rather than leaving it untouched. A PATCH-style partial update can be added later if that proves too blunt in practice.
- **No mapping framework yet:** `ProjectMapper`/`TaskMapper` are hand-written, not MapStruct. At two entities with non-overlapping shapes, a mapping library is more ceremony than it saves; revisit if the DTO count grows.
- **Global error shape:** every error — 404, validation failure, or unexpected exception — comes back as the same `ApiErrorResponse` JSON shape via `@RestControllerAdvice`. Unexpected exceptions are logged server-side with the full stack trace but never leak their message to the client.
- **Custom `@StrongPassword` validator, not a pile of generic annotations:** the brief specifically calls for demonstrating a custom Bean Validation constraint, and `@StrongPassword` (min length + upper/lower/digit) reads clearly at the point of use on `RegisterRequest.password`, rather than being logic someone has to reconstruct from several chained annotations.
- **No `role` field on `RegisterRequest`:** every self-registered account is hardcoded to `Role.USER` in `AuthService`. Letting the client pass its own role at signup is a classic privilege-escalation bug; assigning MANAGER/ADMIN goes through `AdminUserService` instead — a separate, ADMIN-only path (Day 7).
- **Registration confirms duplicate emails (409), login won't:** telling a signup form "that email's taken" is normal, expected UX. Login deliberately returns the same generic error whether the email or the password was wrong — confirming account existence there is what enables user enumeration attacks.
- **`enabled = true` by default, temporarily:** until Day 12 wires up email verification, new accounts are usable immediately so registration → login → everything else stays testable end-to-end in the meantime. This default flips to `false` the same day the verify endpoint ships — a registered-but-unverified account existing with no way to verify it would just be broken, not more secure.
- **`UserResponse` never carries `passwordHash`:** obvious, but worth stating — it's the kind of thing that's easy to leak by accident if a DTO ever gets built by copying entity fields instead of being deliberately composed.
- **Login goes through Spring's `AuthenticationManager`, not a hand-rolled password check:** `AuthService.login()` calls `authenticationManager.authenticate(...)`, which delegates to a `DaoAuthenticationProvider` built from `CustomUserDetailsService` + the `PasswordEncoder` bean. That provider checks `UserDetails.isEnabled()`/`isAccountNonLocked()` *before* it even compares passwords — so once Day 10-11 (lockout) and Day 12 (email verification) start setting those flags for real, login automatically respects them, with zero changes to `AuthService`.
- **Every login failure produces the same `InvalidCredentialsException`:** wrong password, unknown email, disabled account, locked account (soon) — all caught as `AuthenticationException` and rethrown as one generic "Invalid email or password". Distinguishing them in the response is exactly what enables user enumeration and account-probing attacks.
- **Refresh tokens are stored hashed (`TokenHasher.sha256Hex`), never raw:** `refresh_tokens.token_hash` is what's persisted and indexed — the same reasoning as never storing plaintext passwords. A leaked table doesn't hand out replayable tokens.
- **Refresh rotates the token, it doesn't just extend it:** every call to `/auth/refresh` revokes the presented token and issues a brand-new pair. A stolen refresh token that gets replayed after the legitimate owner has already refreshed once simply fails — rotation turns "valid for 7 days no matter what" into "valid until first use".
- **Logout is idempotent:** an unknown, already-revoked, or malformed `refreshToken` still returns `204`. There's no legitimate reason for a client to learn "that token wasn't valid anyway" from a logout call, and a UI retrying logout on a flaky connection shouldn't have to handle an error.
- **The Day 3 seed user can never log in:** `data.sql`'s `password_hash` is a placeholder string, not a real bcrypt hash. `BCryptPasswordEncoder.matches()` handles that gracefully (returns `false`, doesn't throw) — worth knowing so a failed login attempt against the seed fixture doesn't look like a bug.
- **`permitAll()` is finally gone (except the four auth endpoints):** `SecurityConfig` now requires authentication on `anyRequest()`. There's a genuine chicken-and-egg problem this creates — `PUT /api/admin/users/{id}/role` is ADMIN-only, but self-registration always creates a USER (Day 4), so nothing in the API can ever create the *first* ADMIN. `data.sql` now seeds `seed.admin@taskflow.dev` with a real bcrypt hash specifically to break that cycle for local dev; a real deployment would solve this with a one-time bootstrap script or a manual DB insert instead, not a checked-in seed file.
- **A `RoleHierarchy` bean, not `hasAnyRole('MANAGER','ADMIN')` everywhere:** `MethodSecurityConfig` declares ADMIN implies MANAGER implies USER, so every `@PreAuthorize` states the *minimum* role a method needs (`hasRole('MANAGER')`) and higher roles satisfy it automatically. The alternative — spelling out every allowed role at every call site — drifts out of sync the moment a new role is added.
- **Authorization lives on service methods, not controllers:** `ProjectService`/`TaskService` writes and all of `AdminUserService` carry the `@PreAuthorize` checks, not `ProjectController`/`TaskController`/`AdminUserController`. A controller here is a thin adapter; if another entry point ever called these services directly (a batch job, an internal tool, a future GraphQL layer), the authorization would still hold. This is the difference the project brief means by "not just add `@PreAuthorize` cosmetically."
- **Custom `AuthenticationEntryPoint`/`AccessDeniedHandler`, not Spring Security's defaults:** exceptions inside the security filter chain never reach `@RestControllerAdvice` — `GlobalExceptionHandler` simply never sees them. Without `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`, a 401 or 403 would come back in Spring Security's own default shape, breaking the "every error looks the same" guarantee for exactly the two status codes that matter most in a security-focused project.
- **Roles are checked live against the DB, not baked into the JWT:** the access token only carries `sub` (email) and `type` — no role claim. `JwtAuthenticationFilter` calls `CustomUserDetailsService` fresh on every request, so a role change (or, later, a lockout) takes effect on the very next request with the same still-valid token, no re-login required. The cost is one extra DB read per authenticated request; the benefit is that access can be revoked in real time instead of waiting out a token's remaining lifetime.
- **Reads stay coarse-grained; writes are the RBAC boundary today:** any authenticated user, regardless of role, can currently view any project or task — including ones they have nothing to do with. That's intentional for Day 7 (pure role-based rules) and is exactly what Day 8-9's ownership/membership checks narrow, without touching what's built today.
- More decisions (lockout duration, rate-limit approach, email verification flow) will be documented here as each lands.

## License

MIT
