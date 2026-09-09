# TaskFlow — Auth & Authorization Service

A secure REST API demonstrating JWT authentication, role-based access control, and ownership-based authorization in Spring Boot. This is Project 2 of a 3-project backend portfolio (Core REST API → **Auth & Authorization** → Production-grade Booking/Order System).

**Status:** 🚧 Day 1 — project bootstrap. Domain model, JWT auth, RBAC, ownership rules, and tests land over the following days (see [Roadmap](#roadmap) below).

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

## Project structure (Day 1)

```
src/main/java/com/ahdyahmed/taskflow/
├── TaskflowApplication.java
└── domain/
    ├── entity/       # User, Project, Task (fields only — relationships land Day 2)
    └── enums/        # Role, TaskStatus, TaskPriority
```

## Roadmap

| Day(s) | Focus |
|---|---|
| 1 | Project skeleton, Docker Postgres, domain entities |
| 2-3 | Entity relationships, auditing, basic CRUD (pre-security) |
| 4-6 | Registration, Spring Security config, JWT generation, login/refresh/logout |
| 7-9 | Role-based access control, ownership rules, edge cases |
| 10-11 | Account lockout, rate limiting on auth endpoints |
| 12-13 | Email verification, password reset (mocked email) |
| 14-16 | Unit + integration tests, security test matrix |
| 17-18 | OpenAPI docs, architecture diagram, final README |

## Design decisions (living section, updated as the project grows)

- **Relationships deferred to Day 2:** Day 1 entities use plain foreign-key-style `Long` fields instead of `@ManyToOne`/`@ManyToMany` so the domain model and the JPA relationship layer land as two distinct, reviewable commits.
- **Postgres on host port 5433:** avoids clashing with a default local Postgres install; see `docker-compose.yml` for the rationale inline.
- More decisions (refresh-token storage strategy, lockout duration, rate-limit approach) will be documented here as each lands.

## License

MIT
