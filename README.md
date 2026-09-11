# TaskFlow — Auth & Authorization Service

A secure REST API demonstrating JWT authentication, role-based access control, and ownership-based authorization in Spring Boot. This is Project 2 of a 3-project backend portfolio (Core REST API → **Auth & Authorization** → Production-grade Booking/Order System).

**Status:** 🚧 Day 4 — registration with BCrypt password hashing and a custom password-strength validator. Spring Security config, JWT, RBAC, ownership rules, and tests land over the following days (see [Roadmap](#roadmap) below).

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

## API (Day 4)

All endpoints are open with no auth for now (see [Design decisions](#design-decisions-living-section-updated-as-the-project-grows) — this is temporary, not an oversight).

| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | body: `email`, `password` (min 8 chars, upper + lower + digit) — always creates a USER, role can't be self-assigned |
| POST | `/api/projects` | body: `name`, `description?`, `ownerId` |
| GET | `/api/projects/{id}` | |
| GET | `/api/projects` | paginated (`?page=&size=&sort=`) |
| PUT | `/api/projects/{id}` | full replace: `name`, `description?` |
| DELETE | `/api/projects/{id}` | |
| POST | `/api/tasks` | body: `title`, `description?`, `status?`, `priority?`, `projectId`, `assigneeId?`, `createdById` |
| GET | `/api/tasks/{id}` | |
| GET | `/api/tasks` | paginated, all tasks |
| GET | `/api/tasks/project/{projectId}` | paginated, scoped to one project |
| PUT | `/api/tasks/{id}` | full replace, including `assigneeId` (omit = unassign) |
| DELETE | `/api/tasks/{id}` | |

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
```

`docker compose up -d` + `mvn spring-boot:run` also seeds one user (`seed.manager@taskflow.dev`, id `1` on a fresh DB) via `data.sql` — a leftover from Day 3, kept around as a quick fixed-id fixture, though registering through `/auth/register` above works just as well now:

```bash
# Create a project owned by the seeded user
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Website Redesign", "description": "Q4 marketing site refresh", "ownerId": 1}'

# Create a task in that project (swap 1 for the id the previous call returned)
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title": "Wireframe homepage", "projectId": 1, "createdById": 1}'

# List tasks in that project
curl http://localhost:8080/api/tasks/project/1

# A validation error (blank name) — returns the ApiErrorResponse shape
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"ownerId": 1}'
```

## Project structure (Day 4)

```
src/main/java/com/ahdyahmed/taskflow/
├── TaskflowApplication.java
├── config/
│   ├── JpaAuditingConfig.java           # @EnableJpaAuditing
│   ├── PasswordEncoderConfig.java       # BCryptPasswordEncoder bean
│   └── TemporaryOpenSecurityConfig.java # permitAll() until JWT auth lands (Day 5-6)
├── controller/
│   ├── AuthController.java              # POST /auth/register
│   ├── ProjectController.java
│   └── TaskController.java
├── service/
│   ├── AuthService.java
│   ├── ProjectService.java
│   └── TaskService.java
├── mapper/
│   ├── UserMapper.java
│   ├── ProjectMapper.java
│   └── TaskMapper.java
├── validation/
│   ├── StrongPassword.java              # custom bean-validation annotation
│   └── StrongPasswordValidator.java
├── dto/
│   ├── request/   # RegisterRequest, ProjectCreateRequest, ProjectUpdateRequest, TaskCreateRequest, TaskUpdateRequest
│   └── response/  # UserResponse, ProjectResponse, TaskResponse, ApiErrorResponse
├── exception/
│   ├── ResourceNotFoundException.java
│   ├── EmailAlreadyInUseException.java
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice, consistent JSON error shape
├── domain/
│   ├── entity/                          # BaseEntity, User, Project, Task
│   └── enums/                           # Role, TaskStatus, TaskPriority
└── repository/
    ├── UserRepository.java
    ├── ProjectRepository.java
    └── TaskRepository.java
```

## Roadmap

| Day(s) | Focus | Status |
|---|---|---|
| 1 | Project skeleton, Docker Postgres, domain entities | ✅ |
| 2 | Entity relationships, JPA auditing | ✅ |
| 3 | Basic CRUD (pre-security) | ✅ |
| 4 | Registration, BCrypt password hashing, custom password validator | ✅ |
| 5 | Spring Security config, JWT generation/validation |  |
| 6 | Login, refresh token, logout |  |
| 7-9 | Role-based access control, ownership rules, edge cases |  |
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
- **Security starter left wide open for now (`TemporaryOpenSecurityConfig`):** `spring-boot-starter-security` has been on the classpath since Day 1. Without an explicit `SecurityFilterChain`, Spring Boot secures every endpoint behind HTTP Basic with a random generated password — correct for production, but it would make the CRUD/auth endpoints built so far untestable before JWT auth exists. This config bean is explicitly named "Temporary" and gets replaced outright (not extended) once Day 5-6 lands.
- **`ownerId`/`createdById` trusted from the request body:** same reasoning as above — there's no authenticated principal yet to derive them from. Called out with a doc comment directly on the affected DTO fields, not just here, so it's visible at the point of use.
- **Seed data via `data.sql`:** added on Day 3 back when there was no way to create a `User` through the API at all. Now that `/auth/register` exists (Day 4), this file is technically redundant — kept anyway as a quick fixed-id fixture for manual testing, and it's a one-line removal whenever that stops being useful.
- **PUT means full replace:** `TaskUpdateRequest`/`ProjectUpdateRequest` overwrite the fields they carry — in particular, omitting `assigneeId` on a task update clears the assignee rather than leaving it untouched. A PATCH-style partial update can be added later if that proves too blunt in practice.
- **No mapping framework yet:** `ProjectMapper`/`TaskMapper` are hand-written, not MapStruct. At two entities with non-overlapping shapes, a mapping library is more ceremony than it saves; revisit if the DTO count grows.
- **Global error shape:** every error — 404, validation failure, or unexpected exception — comes back as the same `ApiErrorResponse` JSON shape via `@RestControllerAdvice`. Unexpected exceptions are logged server-side with the full stack trace but never leak their message to the client.
- **Custom `@StrongPassword` validator, not a pile of generic annotations:** the brief specifically calls for demonstrating a custom Bean Validation constraint, and `@StrongPassword` (min length + upper/lower/digit) reads clearly at the point of use on `RegisterRequest.password`, rather than being logic someone has to reconstruct from several chained annotations.
- **No `role` field on `RegisterRequest`:** every self-registered account is hardcoded to `Role.USER` in `AuthService`. Letting the client pass its own role at signup is a classic privilege-escalation bug; assigning MANAGER/ADMIN will go through a separate, privileged path once RBAC exists (Day 7-9).
- **Registration confirms duplicate emails (409), login won't:** telling a signup form "that email's taken" is normal, expected UX. Login (Day 6) will deliberately return the same generic error whether the email or the password was wrong — confirming account existence there is what enables user enumeration attacks.
- **`enabled = true` by default, temporarily:** until Day 12 wires up email verification, new accounts are usable immediately so registration → login → everything else stays testable end-to-end in the meantime. This default flips to `false` the same day the verify endpoint ships — a registered-but-unverified account existing with no way to verify it would just be broken, not more secure.
- **`UserResponse` never carries `passwordHash`:** obvious, but worth stating — it's the kind of thing that's easy to leak by accident if a DTO ever gets built by copying entity fields instead of being deliberately composed.
- More decisions (JWT storage strategy, lockout duration, rate-limit approach) will be documented here as each lands.

## License

MIT
