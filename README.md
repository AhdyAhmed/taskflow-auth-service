# TaskFlow — Auth & Authorization Service

A secure REST API demonstrating JWT authentication, role-based access control, and ownership-based authorization in Spring Boot. This is Project 2 of a 3-project backend portfolio (Core REST API → **Auth & Authorization** → Production-grade Booking/Order System).

**Status:** 🚧 Day 13 — password reset is live, closing out Phase 5 ("Real-World Auth Flows"). Resetting a password now also revokes every refresh token the account currently holds. See [What's new in Day 13](#whats-new-in-day-13) below for the details, and [Roadmap](#roadmap) for what's still ahead.

---

## What's new in Day 13

The last piece of the "real-world auth flows" phase — a forgotten password shouldn't mean a locked-out account forever:

- **New `POST /auth/forgot-password`** — always `204`, always identical whether the email exists or not. Unlike Day 9's deliberately-honest register `409` or Day 12's resend-verification, this is the one place in the API where revealing account existence has a real, textbook attacker payoff and essentially no offsetting UX need, so it stays uniform no matter what happened server-side.
- **New `POST /auth/reset-password`** — validates the token (same hashed/expiring/single-use shape as `VerificationToken`, via the new `PasswordResetToken`), updates the password, and — the roadmap's explicit requirement — revokes every refresh token the account currently holds, in the same transaction. Without that last part, anyone who'd stolen a refresh token before the reset (often exactly the scenario prompting one) would keep working access via that token, completely unaffected by the password having changed underneath them.
- **A genuinely subtle bug caught and fixed before it could ship silently broken:** the bulk refresh-token revocation is a `@Modifying` JPQL `UPDATE`, which — unlike a `SELECT` — does **not** auto-flush pending entity changes first. `resetPassword()` sets the new password hash and marks the reset token used *before* calling that bulk update; without `flushAutomatically = true` on the query, those two pending writes would get silently discarded the moment `clearAutomatically = true` detaches the persistence context afterward. Both flags are required, for different reasons — full explanation on `RefreshTokenRepository.revokeAllForUser`'s javadoc. This is exactly the kind of thing that passes a lazy manual test (the password *looks* like it changed, in that request) and then loses data the moment someone checks two requests later.
- **Password-reset tokens expire in 1 hour, not verification's 24** — a leaked reset link is a more immediately dangerous find than a leaked verification link (it grants a password change, not just account activation), so the window is kept deliberately tight. Configurable via the new [`PasswordResetProperties`](src/main/java/com/ahdyahmed/taskflow/config/PasswordResetProperties.java) (`app.security.password-reset.token-expiration-hours`).
- **A successful reset also clears any active account lockout.** Proving email ownership is a stronger identity signal than a correct login password — if that's enough to change the password, it's enough to lift a lockout that exists specifically to slow down someone who *doesn't* have that kind of access.
- **`/auth/forgot-password` and `/auth/reset-password` join Day 11's rate limiter and Day 12's public-endpoint list** — same reasoning both times: reachable without a token, so it needs the same throttling and the same open access as `/auth/verify`/`/auth/resend-verification`.
- **This is the third token entity of an identical shape** (`RefreshToken`, `VerificationToken`, now `PasswordResetToken`), and deliberately not collapsed into one generic `Token` table with a "purpose" column — see [Design decisions](#design-decisions-living-section-updated-as-the-project-grows) for why three small, purpose-specific tables won out over one polymorphic one.

Commit for today: `feat: password reset flow with refresh-token invalidation`

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

## API (Day 13)

Only `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/verify`, `/auth/resend-verification`, `/auth/forgot-password`, and `/auth/reset-password` are public. Everything else needs `Authorization: Bearer <accessToken>` at minimum. Beyond that, most rules are no longer role-only — see the "Auth" column below, and [What's new in Day 13](#whats-new-in-day-13) for what changed most recently.

| Method | Path | Auth |
|---|---|---|
| POST | `/auth/register` | public — rate-limited to 20 req/min per IP (Day 11); new account starts unverified (Day 12) |
| POST | `/auth/login` | public — locks the account for 15 min after 5 failed attempts (Day 10); rejects unverified accounts (Day 12); rate-limited to 20 req/min per IP (Day 11) |
| POST | `/auth/refresh` | public |
| POST | `/auth/logout` | public |
| GET | `/auth/verify` | public — `?token=...`, activates the account (Day 12); rate-limited |
| POST | `/auth/resend-verification` | public — always `204`, regardless of outcome (Day 12); rate-limited |
| POST | `/auth/forgot-password` | public — always `204`, regardless of outcome (Day 13); rate-limited |
| POST | `/auth/reset-password` | public — validates the token, updates the password, revokes all refresh tokens (Day 13); rate-limited |
| POST | `/api/projects` | MANAGER+ (becomes the project's owner) |
| GET | `/api/projects/{id}` | ADMIN, or project owner/member |
| GET | `/api/projects` | any authenticated user — ADMIN sees all, everyone else sees their own, paginated & sortable |
| PUT | `/api/projects/{id}` | ADMIN, or project owner |
| DELETE | `/api/projects/{id}` | ADMIN, or project owner |
| POST | `/api/projects/{id}/members/{userId}` | ADMIN, or project owner |
| DELETE | `/api/projects/{id}/members/{userId}` | ADMIN, or project owner — 409 if the member has active task assignments in this project (Day 9) |
| POST | `/api/tasks` | MANAGER+ and a member of the target project |
| GET | `/api/tasks/{id}` | ADMIN, or a member of the task's project |
| GET | `/api/tasks` | any authenticated user — ADMIN sees all, everyone else sees their own, paginated & sortable |
| GET | `/api/tasks/project/{projectId}` | ADMIN, or a member of that project, paginated & sortable |
| PUT | `/api/tasks/{id}` | ADMIN, or the task's project owner/creator/assignee |
| DELETE | `/api/tasks/{id}` | ADMIN, or the task's project owner |
| GET | `/api/admin/users` | ADMIN only, paginated |
| PUT | `/api/admin/users/{id}/role` | ADMIN only — body: `{"role": "MANAGER"}` |

("MANAGER+" = MANAGER or ADMIN, via the role hierarchy — see [Design decisions](#design-decisions-living-section-updated-as-the-project-grows).)

**Pagination/sorting (Day 9):** every "paginated & sortable" endpoint above accepts the usual `?page=&size=&sort=` params, but `sort` is checked against a per-endpoint allowlist — see [What's new in Day 9](#whats-new-in-day-9). Projects: `id`, `name`, `createdAt`, `updatedAt`. Tasks: `id`, `title`, `status`, `priority`, `createdAt`, `updatedAt`. Anything else in `sort` comes back as `400`, not a crash.

**Rate limiting (Day 11):** `/auth/login` and `/auth/register` each allow 20 requests per 60 seconds per client IP, tracked independently of each other. Past that, the response is `429 Too Many Requests` with a `Retry-After: <seconds>` header. See [What's new in Day 11](#whats-new-in-day-11).

**Email verification (Day 12):** new accounts start disabled. `LoggingEmailService` logs the verification email to the application console instead of sending it — check server output for a line starting `Mock email — to: ...` containing the `/auth/verify?token=...` link. See [What's new in Day 12](#whats-new-in-day-12).

**Password reset (Day 13):** same mocked-email mechanism as verification, 1-hour token lifetime instead of 24. Resetting a password revokes every refresh token the account currently holds — anyone still logged in elsewhere is logged out. See [What's new in Day 13](#whats-new-in-day-13).

## Full end-to-end test sequence

This walks the whole API in order, from an empty database to ownership/membership enforcement, one feature area at a time. Every block is labeled with the day that introduced what it's exercising, so it doubles as a guided tour of the project's history — run it top to bottom against a fresh `docker compose up -d` + `mvn spring-boot:run`.

Every command below is copy-paste runnable as-is in a POSIX-ish shell (bash/zsh/git-bash on Windows) — responses are captured into shell variables with plain `sed`, not `jq`, so there's nothing extra to install. **Run each block in the same shell session, top to bottom** — later blocks depend on variables set earlier ones. If a variable ever comes back empty (check with `echo "$ALICE_TOKEN"`), the block that set it didn't run, or didn't succeed — check the actual `curl` output above it before continuing, since every later step's 401/403 becomes meaningless if the token behind it is blank.

`data.sql` seeds two fixture users on every startup:
- `seed.manager@taskflow.dev` (id `1`) — leftover from Day 3, placeholder password hash, **can't log in**. Harmless, kept as a cheap fixture.
- `seed.admin@taskflow.dev` / `Admin123!` (id `2`) — a real ADMIN account with a real bcrypt hash. It exists solely to solve RBAC's bootstrap problem: `PUT /api/admin/users/{id}/role` is ADMIN-only, but every self-registered account is forced to USER (Day 4), so nothing in the API could ever create the *first* ADMIN otherwise.

### Day 4 — registration

```bash
# Three real accounts we'll use for the rest of the walkthrough. Each
# response is captured, then its "id" field pulled out with sed instead
# of hardcoding 3/4/5 — works the same whether the DB is freshly wiped
# or already has other users in it.
ALICE_REG=$(curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "alice@taskflow.dev", "password": "Sup3rSecret"}')
ALICE_ID=$(echo "$ALICE_REG" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Alice id: $ALICE_ID"   # sanity check — should be a number, not empty

BOB_REG=$(curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "bob@taskflow.dev", "password": "Sup3rSecret"}')
BOB_ID=$(echo "$BOB_REG" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Bob id: $BOB_ID"

CAROL_REG=$(curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "carol@taskflow.dev", "password": "Sup3rSecret"}')
CAROL_ID=$(echo "$CAROL_REG" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Carol id: $CAROL_ID"

# Weak password — rejected by the custom @StrongPassword validator, 400
curl -i -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "someone@taskflow.dev", "password": "weak"}'

# Same email twice — 409 Conflict
curl -i -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "alice@taskflow.dev", "password": "Sup3rSecret"}'
```

> **Day 12 addition — these accounts now need verifying before Day 6's logins below will work.** Registration didn't gate login behind email verification until Day 12; the rest of this walkthrough assumes today's code, so that step has to happen here even though it's a later day's feature. See [What's new in Day 12](#whats-new-in-day-12) for the full explanation — the short version is that `alice`/`bob`/`carol` all started `enabled: false` just now, and each got a mocked verification email logged to the **application's console output**, not sent anywhere. Check the terminal running `mvn spring-boot:run` for three lines starting `Mock email — to: ...`, each containing a link like `http://localhost:8080/auth/verify?token=<uuid>`. Copy each token and verify all three accounts:

```bash
curl -i "http://localhost:8080/auth/verify?token=<ALICE_TOKEN_FROM_CONSOLE>"
curl -i "http://localhost:8080/auth/verify?token=<BOB_TOKEN_FROM_CONSOLE>"
curl -i "http://localhost:8080/auth/verify?token=<CAROL_TOKEN_FROM_CONSOLE>"
```

Each returns `200` with the account's `UserResponse` showing `"enabled":true`. Now Day 6 below will actually work.

### Day 6 — login, refresh, logout

```bash
# Same capture pattern as registration, but pulling accessToken/refreshToken
ALICE_LOGIN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "alice@taskflow.dev", "password": "Sup3rSecret"}')
ALICE_TOKEN=$(echo "$ALICE_LOGIN" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
ALICE_REFRESH=$(echo "$ALICE_LOGIN" | sed -E 's/.*"refreshToken":"([^"]+)".*/\1/')
echo "Alice token starts with: ${ALICE_TOKEN:0:20}..."   # sanity check — should NOT be empty

BOB_LOGIN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" -d '{"email": "bob@taskflow.dev", "password": "Sup3rSecret"}')
BOB_TOKEN=$(echo "$BOB_LOGIN" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

CAROL_LOGIN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" -d '{"email": "carol@taskflow.dev", "password": "Sup3rSecret"}')
CAROL_TOKEN=$(echo "$CAROL_LOGIN" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

# Wrong password — same generic message as "no such account", on purpose, 401
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "alice@taskflow.dev", "password": "wrongpassword"}'

# Refresh — returns a brand new pair and revokes the one just sent, so
# reusing $ALICE_REFRESH a second time now fails
curl -i -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$ALICE_REFRESH\"}"

# Logout — revokes the token, always 204 even if it's already invalid
curl -i -X POST http://localhost:8080/auth/logout \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$ALICE_REFRESH\"}"
```

### Day 7 — role-based access control

```bash
# Alice is still plain USER — creating a project needs MANAGER+ -> 403
curl -i -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE_TOKEN" \
  -d '{"name": "Website Redesign"}'

# Log in as the seeded ADMIN — the only account that can promote anyone
ADMIN_LOGIN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "seed.admin@taskflow.dev", "password": "Admin123!"}')
ADMIN_TOKEN=$(echo "$ADMIN_LOGIN" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
echo "Admin token starts with: ${ADMIN_TOKEN:0:20}..."

# Promote Alice and Carol to MANAGER (Carol stays an outsider to Alice's
# project on purpose — Day 8 below shows MANAGER alone isn't enough)
curl -i -X PUT http://localhost:8080/api/admin/users/$ALICE_ID/role \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"role": "MANAGER"}'
curl -i -X PUT http://localhost:8080/api/admin/users/$CAROL_ID/role \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"role": "MANAGER"}'

# The SAME ALICE_TOKEN from before now works for a MANAGER-only endpoint —
# roles are checked live against the DB on every request, not baked into
# the JWT, so there's no need to log in again after a promotion
PROJECT=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE_TOKEN" \
  -d '{"name": "Website Redesign"}')
PROJECT_ID=$(echo "$PROJECT" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Project id: $PROJECT_ID"   # owner is Alice, taken from her token — Day 8

# No token at all — 401 Unauthorized, same ApiErrorResponse shape as every other error
curl -i http://localhost:8080/api/projects
```

### Day 8 — ownership and project-membership rules

```bash
# Bob isn't a member of Alice's project yet -> 403, even though he's authenticated
curl -i http://localhost:8080/api/projects/$PROJECT_ID \
  -H "Authorization: Bearer $BOB_TOKEN"

# Carol is a MANAGER, but not this project's owner -> 403 (Day 7's "any
# MANAGER can edit" rule is exactly what Day 8 removes)
curl -i -X PUT http://localhost:8080/api/projects/$PROJECT_ID \
  -H "Content-Type: application/json" -H "Authorization: Bearer $CAROL_TOKEN" \
  -d '{"name": "Website Redesign v2"}'

# Alice (owner) adds Bob as a project member
curl -i -X POST http://localhost:8080/api/projects/$PROJECT_ID/members/$BOB_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"

# Bob can view the project now that he's a member -> 200
curl -i http://localhost:8080/api/projects/$PROJECT_ID -H "Authorization: Bearer $BOB_TOKEN"

# Alice creates a task in her project and assigns it to Bob
TASK=$(curl -s -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE_TOKEN" \
  -d "{\"title\": \"Set up CI\", \"projectId\": $PROJECT_ID, \"assigneeId\": $BOB_ID}")
TASK_ID=$(echo "$TASK" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Task id: $TASK_ID"   # createdBy is Alice, taken from her token — Day 8

# Carol is a MANAGER but not a member of this project -> 403 creating a
# task here too, not just editing the project itself
curl -i -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" -H "Authorization: Bearer $CAROL_TOKEN" \
  -d "{\"title\": \"Sneak a task in\", \"projectId\": $PROJECT_ID}"

# Bob is still plain USER, but he's the assignee -> 200, he can update his own task
curl -i -X PUT http://localhost:8080/api/tasks/$TASK_ID \
  -H "Content-Type: application/json" -H "Authorization: Bearer $BOB_TOKEN" \
  -d "{\"title\": \"Set up CI\", \"status\": \"IN_PROGRESS\", \"assigneeId\": $BOB_ID}"

# Carol still can't touch it -> 403 (not the project owner, creator, or assignee)
curl -i -X PUT http://localhost:8080/api/tasks/$TASK_ID \
  -H "Content-Type: application/json" -H "Authorization: Bearer $CAROL_TOKEN" \
  -d '{"title": "Hijacked", "status": "DONE"}'

# Bob can edit his task, but can't delete it -> 403 (delete is project-owner-only, not assignee)
curl -i -X DELETE http://localhost:8080/api/tasks/$TASK_ID -H "Authorization: Bearer $BOB_TOKEN"

# Alice (project owner) can delete it -> 204
curl -i -X DELETE http://localhost:8080/api/tasks/$TASK_ID -H "Authorization: Bearer $ALICE_TOKEN"

# Alice removes Bob from the project
curl -i -X DELETE http://localhost:8080/api/projects/$PROJECT_ID/members/$BOB_ID -H "Authorization: Bearer $ALICE_TOKEN"

# Bob is back to a 403 on the project he just lost membership to
curl -i http://localhost:8080/api/projects/$PROJECT_ID -H "Authorization: Bearer $BOB_TOKEN"

# Listing projects is scoped per caller: Carol (unrelated MANAGER) doesn't
# see Alice's project at all...
curl -i http://localhost:8080/api/projects -H "Authorization: Bearer $CAROL_TOKEN"
# ...but the ADMIN sees every project in the system, including it
curl -i http://localhost:8080/api/projects -H "Authorization: Bearer $ADMIN_TOKEN"
```

### Day 9 — edge cases: member removal, sort validation

```bash
# Bob was removed as a member at the end of the Day 8 block above —
# add him back so we can test the removal guard properly this time.
curl -i -X POST http://localhost:8080/api/projects/$PROJECT_ID/members/$BOB_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"

# Alice creates a fresh task, assigned to Bob, still open
TASK2=$(curl -s -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE_TOKEN" \
  -d "{\"title\": \"Write integration tests\", \"projectId\": $PROJECT_ID, \"assigneeId\": $BOB_ID}")
TASK2_ID=$(echo "$TASK2" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "Task 2 id: $TASK2_ID"

# Alice tries to remove Bob while he's still assigned this open task -> 409,
# not 204 — this is the edge case the roadmap flagged and Day 8 left open
curl -i -X DELETE http://localhost:8080/api/projects/$PROJECT_ID/members/$BOB_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"

# Mark the task DONE (keeping Bob as assignee — PUT is a full replace, so
# assigneeId has to be repeated here or it'd unassign him instead)
curl -i -X PUT http://localhost:8080/api/tasks/$TASK2_ID \
  -H "Content-Type: application/json" -H "Authorization: Bearer $ALICE_TOKEN" \
  -d "{\"title\": \"Write integration tests\", \"status\": \"DONE\", \"assigneeId\": $BOB_ID}"

# A DONE task doesn't block removal -> now it succeeds
curl -i -X DELETE http://localhost:8080/api/projects/$PROJECT_ID/members/$BOB_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"

# Sorting: a normal, allowed sort -> 200
curl -i "http://localhost:8080/api/projects?sort=name,asc" -H "Authorization: Bearer $ALICE_TOKEN"

# An unsupported sort property (a relationship traversal, in this case) -> 400, not a crash
curl -i "http://localhost:8080/api/projects?sort=owner.email,asc" -H "Authorization: Bearer $ALICE_TOKEN"

# Same check on the task listing endpoint -> 400
curl -i "http://localhost:8080/api/tasks?sort=nonsense,asc" -H "Authorization: Bearer $ALICE_TOKEN"
```

### Day 10 — account lockout

```bash
# A fresh account, independent of everything above, so we don't disturb
# Alice's login history or any of the project/task state.
curl -s -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "dave@taskflow.dev", "password": "Sup3rSecret"}' > /dev/null
```

> **Day 12 addition — Dave needs verifying too, and it matters *why* here.** Spring Security's `PreAuthenticationChecks` run `isAccountNonLocked()` before `isEnabled()`, both before the password is ever compared. An unverified Dave would fail every attempt below with "please verify your email" (403) instead of ever reaching password comparison at all — none of the 5 attempts would count as a *failed login*, so the lockout this block exists to demonstrate would simply never trigger. Verify him first, same as Alice/Bob/Carol above (check the console for his `Mock email` line):

```bash
curl -i "http://localhost:8080/auth/verify?token=<DAVE_TOKEN_FROM_CONSOLE>"
```

```bash
# 5 wrong-password attempts in a row. Each comes back 401 with the same
# generic message as always — nothing about the response changes as the
# account gets closer to being locked.
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "attempt $i: %{http_code}\n" -X POST http://localhost:8080/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email": "dave@taskflow.dev", "password": "wrongpassword"}'
done

# The account is locked now. A 6th attempt with the CORRECT password
# still fails — same 401, same "Invalid email or password" message as
# every wrong-password attempt above. Nothing in the response reveals
# that this failure is for a different reason than the first five.
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "dave@taskflow.dev", "password": "Sup3rSecret"}'
```

The lockout clears itself automatically 15 minutes after that 5th failure — nothing has to be manually reset for login to work again. To see that without actually waiting 15 minutes, temporarily set `app.security.lockout.lockout-duration-minutes: 1` in `application.yml`, restart, repeat the block above, then wait ~1 minute and retry the correct-password login — it succeeds, and `failedLoginAttempts`/`lockedUntil` both clear on that successful login.

### Day 11 — rate limiting

```bash
# By this point in the walkthrough, /auth/register has already been hit
# several times (Day 4, Day 10) and that consumption counts — the bucket
# is per (IP, path) for the app's whole uptime, not reset per test block.
# This pushes it well past its limit regardless of exactly how much
# headroom was left; watch the status codes shift from 201/409 to 429
# partway through. (If you're running this block on its own rather than
# after the full walkthrough, expect the first ~20 to succeed instead.)
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "attempt $i: %{http_code}\n" -X POST http://localhost:8080/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email": "ratelimit-demo@taskflow.dev", "password": "Sup3rSecret"}'
done

# Once you're seeing 429s above, this shows the actual response — the
# Retry-After header tells the caller how many seconds to back off
curl -i -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "ratelimit-demo@taskflow.dev", "password": "Sup3rSecret"}'
```

`/auth/login` has its own separate bucket, keyed by path as well as IP, so it isn't affected by anything the block above just did to `/auth/register`'s. The bucket for a given (IP, path) refills gradually over the configured window rather than resetting instantly, so if you re-run the whole test sequence from the top without restarting the app, give it ~60 seconds between full runs — or just restart, which clears every in-memory bucket immediately.

### Day 12 — email verification

```bash
# ratelimit-demo@taskflow.dev was registered back in Day 11's block
# (its very first iteration, before the rate limit kicked in) and never
# verified. Logging in with the right password still fails — 403, not
# 401, and a message that actually explains why.
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "ratelimit-demo@taskflow.dev", "password": "Sup3rSecret"}'
```

Check the console running `mvn spring-boot:run` for this account's `Mock email — to: ratelimit-demo@taskflow.dev ...` line and copy its token, same as the Day 4/Day 10 additions above:

```bash
curl -i "http://localhost:8080/auth/verify?token=<RATELIMIT_DEMO_TOKEN_FROM_CONSOLE>"
# -> 200, {"enabled":true, ...}

# The exact same login call that returned 403 a moment ago now succeeds
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "ratelimit-demo@taskflow.dev", "password": "Sup3rSecret"}'
```

Resend-verification always looks the same from the outside — whether the account is already verified (as ratelimit-demo now is) or doesn't exist at all:

```bash
curl -i -X POST http://localhost:8080/auth/resend-verification \
  -H "Content-Type: application/json" \
  -d '{"email": "ratelimit-demo@taskflow.dev"}'
# -> 204, no new email logged (already verified, so issueVerificationToken never runs)

curl -i -X POST http://localhost:8080/auth/resend-verification \
  -H "Content-Type: application/json" \
  -d '{"email": "totally-fake-address@taskflow.dev"}'
# -> 204, identical response — no way to tell these two calls apart from the outside
```

### Day 13 — password reset

```bash
# Alice's only refresh token so far (from Day 6) was already used up by
# that block's refresh + logout demo, so log her in fresh here — we need
# a genuinely active refresh token to demonstrate reset-password killing it.
ALICE_LOGIN2=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" -d '{"email": "alice@taskflow.dev", "password": "Sup3rSecret"}')
ALICE_REFRESH2=$(echo "$ALICE_LOGIN2" | sed -E 's/.*"refreshToken":"([^"]+)".*/\1/')

# forgot-password — identical 204 whether the account exists or not
curl -i -X POST http://localhost:8080/auth/forgot-password \
  -H "Content-Type: application/json" -d '{"email": "alice@taskflow.dev"}'
curl -i -X POST http://localhost:8080/auth/forgot-password \
  -H "Content-Type: application/json" -d '{"email": "totally-fake-address@taskflow.dev"}'
```

Check the console for Alice's `Mock email — to: alice@taskflow.dev | subject: Reset your TaskFlow password` line and copy its token:

```bash
curl -i -X POST http://localhost:8080/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token": "<ALICE_RESET_TOKEN_FROM_CONSOLE>", "newPassword": "NewSup3rSecret"}'
# -> 204

# The refresh token Alice was holding before the reset is dead now, even
# though it hadn't actually reached its own 7-day expiry
curl -i -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" -d "{\"refreshToken\": \"$ALICE_REFRESH2\"}"
# -> 401, "Refresh token is invalid or expired"

# Her old password no longer works...
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" -d '{"email": "alice@taskflow.dev", "password": "Sup3rSecret"}'
# -> 401, generic "Invalid email or password" — same message as always

# ...but the new one does
curl -i -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" -d '{"email": "alice@taskflow.dev", "password": "NewSup3rSecret"}'
# -> 200, a fresh access/refresh pair
```

## Project structure (Day 13)

```
src/main/java/com/ahdyahmed/taskflow/
├── TaskflowApplication.java             # @ConfigurationPropertiesScan
├── config/
│   ├── JpaAuditingConfig.java           # @EnableJpaAuditing
│   ├── PasswordEncoderConfig.java       # BCryptPasswordEncoder bean
│   ├── AppProperties.java               # Day 12: app.base-url bound as a record
│   ├── JwtProperties.java               # app.jwt.* bound as a record
│   ├── LockoutProperties.java           # Day 10: app.security.lockout.* bound as a record
│   ├── RateLimitProperties.java         # Day 11: app.security.rate-limit.* bound as a record
│   ├── VerificationProperties.java      # Day 12: app.security.verification.* bound as a record
│   ├── PasswordResetProperties.java     # Day 13: app.security.password-reset.* bound as a record
│   ├── SecurityConfig.java              # stateless sessions, JWT + rate-limit filters, only /auth/* public
│   └── MethodSecurityConfig.java        # @EnableMethodSecurity + role hierarchy (ADMIN > MANAGER > USER)
├── email/
│   ├── EmailService.java                # Day 12: send(to, subject, body) — transport-only interface
│   └── LoggingEmailService.java         # Day 12: the only impl — logs instead of sending
├── security/
│   ├── JwtService.java                  # generate/validate access + refresh tokens
│   ├── JwtAuthenticationFilter.java     # OncePerRequestFilter, parses Bearer tokens
│   ├── RateLimitingFilter.java          # Day 11: Bucket4j token bucket per (IP, path); Day 12/13: + verify/resend/forgot/reset paths
│   ├── AppUserPrincipal.java            # UserDetails wrapping the domain User; Day 10: real isAccountNonLocked(); Day 12: real isEnabled()
│   ├── CustomUserDetailsService.java    # UserDetailsService backed by UserRepository
│   ├── TokenHasher.java                 # SHA-256, used for refresh/verification/reset-token-at-rest storage
│   ├── SecurityErrorResponseWriter.java # shared JSON error writer for 401/403/429
│   ├── RestAuthenticationEntryPoint.java# 401 — no/invalid token
│   ├── RestAccessDeniedHandler.java     # 403 — valid token, wrong role/not owner/not member
│   ├── AuthenticatedUser.java           # Day 8: Authentication -> domain User, shared helper
│   ├── ProjectSecurity.java             # Day 8: @projectSecurity.isOwner/isMember for @PreAuthorize
│   └── TaskSecurity.java                # Day 8: @taskSecurity.isOwnerOrAssignee/isProjectOwner/isProjectMember
├── controller/
│   ├── AuthController.java              # + Day 12: GET /verify, POST /resend-verification; Day 13: POST /forgot-password, /reset-password
│   ├── AdminUserController.java         # ADMIN-only: list users, change role
│   ├── ProjectController.java           # + Day 8: POST/DELETE /{id}/members/{userId}
│   └── TaskController.java
├── service/
│   ├── AuthService.java                 # Day 10: delegates lockout tracking; Day 12: verification flow; Day 13: password reset flow
│   ├── LoginAttemptService.java         # Day 10: lockout bookkeeping, deliberately its own bean — see javadoc
│   ├── AdminUserService.java            # class-level @PreAuthorize("hasRole('ADMIN')")
│   ├── ProjectService.java              # Day 8: ownership/membership-scoped; Day 9: member-removal guard + sort validation
│   └── TaskService.java                 # Day 8: ownership/membership-scoped; Day 9: sort validation
├── web/
│   └── SortValidation.java              # Day 9: allowlist for client-supplied ?sort= on listing endpoints
├── mapper/
│   ├── UserMapper.java
│   ├── ProjectMapper.java
│   └── TaskMapper.java
├── validation/
│   ├── StrongPassword.java              # custom bean-validation annotation
│   └── StrongPasswordValidator.java
├── dto/
│   ├── request/   # RegisterRequest, LoginRequest, RefreshRequest, ChangeRoleRequest, EmailRequest, ResetPasswordRequest, ProjectCreateRequest, ProjectUpdateRequest, TaskCreateRequest, TaskUpdateRequest
│   │              # (Day 8: ProjectCreateRequest/TaskCreateRequest no longer take ownerId/createdById — derived from the authenticated principal)
│   │              # (Day 12: EmailRequest backs both /auth/resend-verification and Day 13's /auth/forgot-password)
│   └── response/  # UserResponse, AuthResponse, ProjectResponse, TaskResponse, ApiErrorResponse
├── exception/
│   ├── ResourceNotFoundException.java
│   ├── EmailAlreadyInUseException.java
│   ├── InvalidCredentialsException.java
│   ├── InvalidTokenException.java       # Day 13: also covers an invalid/expired password-reset token
│   ├── AccountNotVerifiedException.java # Day 12: unverified-account login rejection
│   ├── MemberHasActiveAssignmentsException.java  # Day 9: the member-removal guard
│   └── GlobalExceptionHandler.java      # @RestControllerAdvice, consistent JSON error shape; Day 9/12: + 409/403/400 handlers above
├── domain/
│   ├── entity/                          # BaseEntity, User, Project, Task, RefreshToken, VerificationToken, PasswordResetToken
│   └── enums/                           # Role, TaskStatus, TaskPriority
└── repository/
    ├── UserRepository.java
    ├── ProjectRepository.java
    ├── TaskRepository.java
    ├── RefreshTokenRepository.java      # Day 13: + revokeAllForUser bulk update
    ├── VerificationTokenRepository.java # Day 12
    └── PasswordResetTokenRepository.java # Day 13
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
| 8-9 | Ownership rules, edge cases | ✅ |
| 10-11 | Account lockout, rate limiting on auth endpoints | ✅ |
| 12-13 | Email verification, password reset (mocked email) | ✅ |
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
- **`ownerId`/`createdById` trusted from the request body:** there's no authenticated principal yet to derive them from (that changes once login exists and endpoints start reading `Authentication`). Called out with a doc comment directly on the affected DTO fields, not just here, so it's visible at the point of use. **Resolved Day 8** — see the Day 8 entries below; both fields are gone from the request DTOs now.
- **Seed data via `data.sql`:** added on Day 3 back when there was no way to create a `User` through the API at all. Now that `/auth/register` exists (Day 4), this file is technically redundant — kept anyway as a quick fixed-id fixture for manual testing, and it's a one-line removal whenever that stops being useful.
- **PUT means full replace:** `TaskUpdateRequest`/`ProjectUpdateRequest` overwrite the fields they carry — in particular, omitting `assigneeId` on a task update clears the assignee rather than leaving it untouched. A PATCH-style partial update can be added later if that proves too blunt in practice.
- **No mapping framework yet:** `ProjectMapper`/`TaskMapper` are hand-written, not MapStruct. At two entities with non-overlapping shapes, a mapping library is more ceremony than it saves; revisit if the DTO count grows.
- **Global error shape:** every error — 404, validation failure, or unexpected exception — comes back as the same `ApiErrorResponse` JSON shape via `@RestControllerAdvice`. Unexpected exceptions are logged server-side with the full stack trace but never leak their message to the client.
- **Custom `@StrongPassword` validator, not a pile of generic annotations:** the brief specifically calls for demonstrating a custom Bean Validation constraint, and `@StrongPassword` (min length + upper/lower/digit) reads clearly at the point of use on `RegisterRequest.password`, rather than being logic someone has to reconstruct from several chained annotations.
- **No `role` field on `RegisterRequest`:** every self-registered account is hardcoded to `Role.USER` in `AuthService`. Letting the client pass its own role at signup is a classic privilege-escalation bug; assigning MANAGER/ADMIN goes through `AdminUserService` instead — a separate, ADMIN-only path (Day 7).
- **Registration confirms duplicate emails (409), login won't:** telling a signup form "that email's taken" is normal, expected UX. Login deliberately returns the same generic error whether the email or the password was wrong — confirming account existence there is what enables user enumeration attacks.
- **`enabled = true` by default, temporarily:** until Day 12 wires up email verification, new accounts are usable immediately so registration → login → everything else stays testable end-to-end in the meantime. This default flips to `false` the same day the verify endpoint ships — a registered-but-unverified account existing with no way to verify it would just be broken, not more secure.
- **`UserResponse` never carries `passwordHash`:** obvious, but worth stating — it's the kind of thing that's easy to leak by accident if a DTO ever gets built by copying entity fields instead of being deliberately composed.
- **Login goes through Spring's `AuthenticationManager`, not a hand-rolled password check:** `AuthService.login()` calls `authenticationManager.authenticate(...)`, which delegates to a `DaoAuthenticationProvider` built from `CustomUserDetailsService` + the `PasswordEncoder` bean. That provider checks `UserDetails.isEnabled()`/`isAccountNonLocked()` *before* it even compares passwords — so once Day 10-11 (lockout) and Day 12 (email verification) start setting those flags for real, login automatically respects them, with zero changes to `AuthService`. **Confirmed Day 10** — `isAccountNonLocked()` is real now (see below), and `login()` needed exactly zero changes to start respecting it, as predicted here back on Day 6.
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
- **Reads stay coarse-grained; writes are the RBAC boundary today:** any authenticated user, regardless of role, can currently view any project or task — including ones they have nothing to do with. That's intentional for Day 7 (pure role-based rules) and is exactly what Day 8-9's ownership/membership checks narrow, without touching what's built today. **Narrowed Day 8** — see below; reads are now membership-scoped too, not just writes.
- **`ProjectSecurity`/`TaskSecurity` as separate beans, not one shared "ownership" bean (Day 8):** a `Project` and a `Task` have different shapes of "who's allowed" (a project has an owner and members; a task additionally has a creator and an assignee, and inherits its project's owner/members). Splitting them keeps each bean's methods named for what they actually check (`isOwner`, `isMember`, `isOwnerOrAssignee`, `isProjectOwner`, `isProjectMember`) instead of one bean with parameters describing which entity type it's looking at.
- **A denied ownership/membership check resolves to 403, not 404, for a nonexistent project/task (Day 8):** `ProjectSecurity`/`TaskSecurity` return `false` when `findById` comes back empty, same as when the resource exists but the caller isn't authorized. `@PreAuthorize` runs before the service method body, so the method's own `ResourceNotFoundException` never gets a chance to fire for a denied caller — they get a 403 either way, which avoids letting a non-member enumerate which project/task ids exist by comparing 403 vs 404 responses. Because `hasRole('ADMIN') or ...` short-circuits, an ADMIN skips the bean entirely and still gets a real 404 for something that's truly missing.
- **`ownerId`/`createdById` derived from `Authentication`, not the request body (Day 8):** closes the gap flagged since Day 1/3 — `ProjectService.create`/`TaskService.create` now take an `Authentication` parameter (threaded through from the controller, same as the ownership-check beans use) and read the caller's own `User` off it via the new `AuthenticatedUser` helper, instead of trusting a client-supplied id for who owns/created something.
- **Task `create` requires project membership on top of the MANAGER role (Day 8):** `hasRole('MANAGER')` alone let any manager create tasks in any project, including ones they'd never touched. The `@PreAuthorize` expression is now `hasRole('ADMIN') or (hasRole('MANAGER') and @projectSecurity.isMember(#request.projectId, authentication))` — a compound check combining a role condition with an ownership-bean condition in one expression, rather than two separate checks.
- **Task `update` drops the role check in favor of ownership entirely (Day 8):** it's `hasRole('ADMIN') or @taskSecurity.isOwnerOrAssignee(...)` — no `hasRole('MANAGER')` branch at all. A MANAGER with no relationship to a specific task (not its project's owner, not its creator, not its assignee) no longer gets a free pass just for holding the role; the role only matters for creating new tasks and projects, not for editing arbitrary existing ones.
- **Task `delete` is narrower than `update` on purpose (Day 8):** `update` accepts the project owner, the task's creator, *or* its assignee; `delete` only accepts the project owner (or ADMIN). Being assigned a task is a reason to be able to update its status/description, not to unilaterally delete it — that stays a project-owner-level decision.
- **Project membership management added, gated the same as project update/delete (Day 8):** `POST`/`DELETE /api/projects/{id}/members/{userId}` didn't exist before today — there was no way to add a member at all, which would have made every membership check above untestable. Both endpoints require the project's owner or ADMIN, same `@PreAuthorize` expression as `update`/`delete`, since managing who has access to a project is itself a project-level write.
- **`findAll` branches on role in the method body instead of a second `@PreAuthorize` (Day 8):** `ProjectService.findAll`/`TaskService.findAll` return every row for ADMIN and a caller-scoped subset (via `findAccessibleTo`) for everyone else. This is filtering, not access denial — there's no "wrong" role for calling "list my projects/tasks", just a different result set — so it reads more clearly as an `if` on the caller's role than as a security-expression trick.
- **Removing a project member doesn't touch their existing task assignments (Day 8, open question):** if Bob is removed as a member but is still the assignee on a task in that project, `TaskSecurity.isOwnerOrAssignee` still lets him update it — assignment and membership are checked independently, on purpose, so removing membership doesn't need to cascade into reassigning or blocking tasks. Whether that's the right behavior long-term (vs. auto-unassigning, or blocking removal while assignments exist) is explicitly the question Day 9's roadmap entry ("what happens when a MANAGER removes a member who's assigned tasks?") exists to settle — not decided yet. **Resolved Day 9** — see below; removal is now blocked outright while active assignments exist, rather than left to silently diverge.
- **Member removal refuses to proceed while active assignments exist, rather than auto-unassigning (Day 9):** `ProjectService.removeMember` now checks `TaskRepository.findByProject_IdAndAssignee_IdAndStatusNot(projectId, userId, DONE)` before touching the membership set, and throws (409) if that comes back non-empty. The alternative — silently detaching the assignee from those tasks so removal always succeeds — was considered and rejected: it's a data-mutating side effect the caller didn't ask for, buried inside what looks like a pure membership operation. Refusing and naming the blocking task ids in the error message puts the actual decision (reassign? wait for completion? force it some other way?) back with a human, which felt like the more honest default for a tool that doesn't yet have a product opinion on the right answer.
- **`DONE` tasks are excluded from the removal-block check (Day 9):** only non-`DONE` tasks count as "active" for the purposes of blocking removal. A completed task's assignee field is historical record (who did this), not a live obligation — there's nothing left for them to do, so their having once been assigned shouldn't keep them stuck as a project member.
- **Client-supplied `?sort=` is now allowlisted per endpoint instead of trusted as-is (Day 9):** Spring Data binds `Pageable`'s `sort` param straight from the query string and only resolves it against the entity at query-execution time, inside the repository layer — an invalid property previously surfaced as a raw `PropertyReferenceException`, caught only by the generic `Exception` handler and reported as a `500`. `SortValidation.requireAllowed` checks the requested sort against an explicit per-service allowlist (`ProjectService`/`TaskService`'s `SORT_PROPERTIES`) before the query ever runs, turning that into a clean `400`. It also blocks relationship-traversal sorts like `owner.email` on principle, not because anything sensitive leaks through an `ORDER BY`, but because letting a query param dictate an implicit `JOIN` isn't a shape of control this API means to expose.
- **DTO email/role exposure audited, nothing changed (Day 9):** the roadmap's "ensure DTOs never leak other users' emails/roles unnecessarily" is deliberately phrased as an audit, and that's what today's pass was — going through every response DTO and mapper (`grep -rn "passwordHash" dto/ mapper/` confirms it never appears in either) and every place `Role` is returned (only `UserResponse`, only reachable via the ADMIN-only `AdminUserController`). Conclusion: no unnecessary exposure to fix, because Day 8 already gates who can reach `ProjectResponse`/`TaskResponse` in the first place — the emails they do expose (owner/assignee/creator) are only visible to someone already scoped to see that project or task.
- **`/auth/register` confirms whether an email is already taken; `/auth/login` deliberately doesn't (documented Day 9, behavior unchanged since Day 4/6):** registration returns a distinct `409` for a duplicate email, which does let someone enumerate registered addresses one at a time — a real trade-off, not an oversight. It's kept because the alternative (a vague "something went wrong" on every registration attempt) makes the ordinary signup flow confusing for no real gain: an attacker who can already submit arbitrary emails to `/auth/register` learns almost the same thing more slowly by then trying to log in with a guessed password and reading timing/response differences anyway. Login stays intentionally generic (see the Day 6 entry above) because there the cost/benefit flips — confirming a valid login email materially helps a credential-stuffing attacker, in a way confirming a *registration* email doesn't help nearly as much.
- **`LoginAttemptService` is a dedicated bean, not private methods on `AuthService` (Day 10):** the failed-attempt counter has to persist even though `login()` throws right after recording it, which means it needs `@Transactional(propagation = REQUIRES_NEW)` to survive the rollback that throw triggers. `REQUIRES_NEW` only works through Spring's proxy for the bean it's declared on — a private, self-invoked method (`this.registerFailedAttempt(...)`) never goes through that proxy, so the same annotation on a private `AuthService` method would silently do nothing, and every failed-login count would vanish the moment it mattered. This is a real, easy-to-miss Spring AOP gap, not a stylistic preference — see the class javadoc for the full explanation.
- **A lock that's expired counts as a fresh start, not "one more strike" (Day 10):** if `lockedUntil` is in the past when a new failure comes in, `LoginAttemptService.registerFailedAttempt` resets the counter to 1 instead of incrementing whatever it was before locking (which would already be `maxFailedAttempts`). Without this, a single failed login any time after a lockout naturally expires would immediately re-lock the account — technically "5 failures locks you out" but in practice behaving like "1 failure locks you out, forever, in 15-minute increments" for anyone who mistypes their password even once post-lockout. An already-*active* lock still refuses to extend itself on repeated attempts, though — hammering a locked account doesn't make the lockout longer, it just keeps failing.
- **Locked-account rejection happens on username alone, before the password is even checked (Day 10):** this is Spring Security's own `DaoAuthenticationProvider` behavior (`PreAuthenticationChecks` runs before `AdditionalAuthenticationChecks`), not something this project added — but it matters for the security story: a locked account can't be "cracked through" by someone who happens to know the real password, because the password never gets compared while the lock is active.
- **The lockout message is identical to the Day 6 generic message, on purpose (Day 10):** the roadmap asks for "a distinct error... without revealing whether it was the email or password that was wrong," and the way this reads that requirement is: distinct from a raw error/500, not distinct *per failure cause*. A cause-specific response (a different message, or a `423 Locked` status instead of `401`) would tell an attacker two things the plain wrong-password case already withholds — that the account exists, and that it's failed 5 times recently. Same status, same body, every time.
- **Bucket4j over a hand-rolled counter (Day 11):** a token bucket (vs. a naive "count requests in the last N seconds" counter) allows some burstiness — a legitimate user who mistypes their password twice in quick succession isn't treated differently from one request every few seconds — while still enforcing a hard average rate over time. Rolling a custom version of this correctly (thread-safe, no race conditions on concurrent requests to the same bucket) is exactly the kind of thing worth reaching for a well-tested library over, rather than a bespoke `AtomicInteger` + timestamp scheme that looks right until it's tested under real concurrency.
- **Rate limiting is a separate filter from the account lockout in `LoginAttemptService`, not merged into one mechanism (Day 11):** they answer different questions — "is this source making too many requests" vs. "has this specific account failed too many times" — and conflating them would mean an attacker spreading attempts across many accounts from one IP gets caught by rate limiting but never by lockout (correctly, since no single account is under attack), while an attacker rotating IPs against one account gets caught by lockout but never by rate limiting (also correctly). Keeping them independent means each does its one job without trying to also do the other's.
- **Keyed by `(IP, path)`, not `IP` alone (Day 11):** `/auth/login` and `/auth/register` get separate budgets per client, so exhausting one doesn't block the other — someone genuinely struggling to log in shouldn't lose their ability to register a second account (or vice versa) as a side effect.
- **In-memory and single-instance, documented rather than hidden (Day 11):** the bucket state lives in a `ConcurrentHashMap` on the filter instance. That's fine for one instance and wrong the moment there's more than one behind a load balancer — an attacker gets roughly N× the intended limit, split across instances by whichever one each request happens to hit. `RateLimitingFilter`'s javadoc calls this out explicitly and points at Redis (which Bucket4j supports natively) as the fix, matching the roadmap's own note that this would need to move to Redis in a multi-instance deployment — this project just isn't a multi-instance deployment yet.
- **20 requests/minute, not something stricter (Day 11):** tuned partly for the actual security goal (still meaningfully throttles brute-forcing, since 20 guesses/minute is orders of magnitude below what an unthrottled endpoint allows) and partly so this README's own [test sequence](#full-end-to-end-test-sequence) — which calls `/auth/login` and `/auth/register` a couple dozen times across Day 4/6/7/10 before Day 11 even starts — can be run start to finish without accidentally tripping the limiter. Both numbers are one config change away (`app.security.rate-limit.*`) if a stricter limit is ever wanted; this is a starting point, not a claim that 20/min is the objectively correct number.
- **`EmailService` is transport-only; content-building stays in `AuthService` (Day 12):** the interface is exactly `send(to, subject, body)` — no `sendVerificationEmail(...)`, no knowledge of tokens or links. Putting content-building in the interface would mean every new kind of email (verification today, password reset on Day 13, maybe others later) needs either a new interface method or a generic "template + params" scheme bolted on. Keeping the interface dumb means `LoggingEmailService` never has to change, and a future real implementation (SMTP, SES, Postmark, whatever) only ever has to solve "deliver this text," never "understand what this project's emails mean."
- **Verification tokens follow `RefreshToken`'s exact shape — hashed at rest, expiring, single-use (Day 12):** `VerificationToken` is structurally the same entity with `used` playing `RefreshToken.revoked`'s role. This is reuse of an already-reasoned-through pattern (see the Day 6 `TokenHasher` note), not a coincidence — there was no reason to invent a second way to represent "a server-issued, one-time, expiring credential" when one already existed and was already correct.
- **`GET /auth/verify` mutates state, which breaks strict REST semantics on purpose (Day 12):** a verification link has to be clickable directly from an email client, and a link triggers a `GET` — there's no realistic way to make "click this link" issue a `POST` instead. This is the same trade-off essentially every mainstream email-verification flow (Google, GitHub, etc.) makes; flagging it here rather than pretending the API is purely RESTful everywhere.
- **Unverified-account login gets its own message, breaking from the "always generic" pattern lockout established (Day 12):** Day 10 was explicit that locked-vs-wrong-password shouldn't be distinguishable, because the distinction is attack-relevant (it tells an attacker they've found a real, actively-targeted account). "This account isn't verified yet" is a different kind of fact — it's not learned by guessing passwords (the check runs before the password is even compared, so it fires on a correct password too), and Day 9 already established that `register()`'s 409 reveals email existence anyway. The incremental leak from also revealing "and it's unverified" is small and not attack-useful, while hiding it would make onboarding confusing for zero security benefit. Also why this path is explicitly excluded from `LoginAttemptService.registerFailedAttempt` — presenting the *correct* password isn't credential-guessing behavior and shouldn't accumulate toward a lockout.
- **`/auth/resend-verification` always returns `204`, the mirror image of `register()`'s honest `409` (Day 12):** register has a real, immediate UX need to confirm a duplicate (so the person doesn't lose their half-filled signup form to a confusing generic error); resend has no equivalent need — its caller already believes they have an account and is just trying to get a working link. Turning it into a second, repeatable existence-check oracle would cost real safety for no real UX gain, so unlike register, this one stays uniform regardless of what actually happened server-side.
- **`/auth/verify` and `/auth/resend-verification` reuse Day 11's `RateLimitingFilter` rather than a second mechanism (Day 12):** both are exactly the shape of endpoint that filter already exists for — reachable without a token, and abusable (token-guessing against `/verify`, inbox-spam via repeated `/resend-verification` calls). Adding two path strings to an existing `Set` beat writing a second rate limiter that would've needed the exact same reasoning already written down for the first one.
- **CVE-2026-22746 is a known, documented limitation, not something this project works around (Day 12):** Spring Security's `DaoAuthenticationProvider` checks `isEnabled()`/`isAccountNonLocked()`/`isAccountNonExpired()` before comparing passwords — the exact mechanism Day 10's lockout and Day 12's verification both depend on — and a disclosed timing side-channel (LOW severity, April 2026) means the *response time* for a locked/disabled/unverified account can theoretically differ from a wrong-password one, even though the response *body* is identical either way. Spring Boot 3.3.5 (this project's version) is on the affected Spring Security line (6.3.4), and the free OSS fix requires upgrading past 6.5.10 — not a drop-in patch-version bump for this Boot version. Documented rather than silently left unfixed: a portfolio project doesn't need same-day remediation of a LOW-severity timing side-channel, but pretending it doesn't apply here would be dishonest, since the exact pattern this README already documents as a security feature (Day 10's identical-message defense) is precisely what the CVE partially undermines.
- **`PasswordResetToken` is a third copy of the same shape, not a generalized `Token` entity (Day 13):** by Day 13 there are three tables — `RefreshToken`, `VerificationToken`, `PasswordResetToken` — that are all structurally "hashed value, owning user, expiry, single-use flag." Collapsing them into one `Token` entity with a `purpose` enum was a real option, considered and rejected: the three already have slightly different consumption semantics (`RefreshToken` gets rotated and re-issued on every refresh; `VerificationToken`/`PasswordResetToken` are used exactly once and never replaced), different lifetimes, and different downstream effects on `use` (a verify sets `enabled`; a reset changes a password and cascades into revoking *other* tokens). A generic table would need nullable, purpose-specific columns or a lookup into other tables anyway — three small, obviously-named tables are easier to query, index, and reason about than one polymorphic one pretending to be simple.
- **`/auth/forgot-password` is the one endpoint in this API that goes fully uniform, no exceptions (Day 13):** contrast with `register()`'s honest `409` (Day 9) and `resend-verification`'s "204 either way" (Day 12, but still only reachable by someone who already believes they have an account). Forgot-password is reachable by literally anyone with an email address to type in, with zero legitimate need to know whether it's registered — "check your inbox" is a complete, honest-enough response whether or not an inbox is actually getting anything. This is the standard industry pattern for a reason: of the three email-collecting endpoints in this API, this one has the worst ratio of attacker value to legitimate UX need for revealing existence.
- **Password reset revokes refresh tokens in the same transaction as the password change, not as a follow-up step (Day 13):** the roadmap calls this out explicitly, and the reasoning is concrete, not abstract — a stolen refresh token is exactly the kind of thing a password reset is often issued *in response to*. A reset that changes the password but leaves existing refresh tokens live means an attacker who already has one keeps working access indefinitely, unaffected by the very action meant to lock them out. Bundling both in one `@Transactional` method means there's no window where one succeeded without the other.
- **The bulk-revoke query needs `flushAutomatically = true` *and* `clearAutomatically = true` together, and this was a real bug caught during review, not a hypothetical (Day 13):** `resetPassword()` sets the new password hash and marks the reset token used — both pending, unflushed entity changes — immediately before calling the bulk `@Modifying` `UPDATE` that revokes refresh tokens. A JPQL bulk update does not auto-flush pending entity state first (unlike a `SELECT`, which Hibernate's `FlushMode.AUTO` does reason about); without `flushAutomatically = true`, those two pending writes would still be sitting unflushed the moment `clearAutomatically = true` detached them from the persistence context — silently discarding the password change and leaving the reset token reusable, while the refresh-token revocation itself (a direct SQL `UPDATE`, unaffected by any of this) would still have succeeded. That combination — one part of a "single atomic operation" silently failing while the rest silently succeeds — is a bad way for a security-critical flow to break, and the specific reason it's called out this explicitly here.
- **A successful reset also clears `failedLoginAttempts`/`lockedUntil` (Day 13):** not part of the roadmap's literal ask, but a natural extension of the same reasoning Day 10 already established for successful logins — proving control of the account (here, of its email inbox, arguably a stronger signal than a remembered password) is a legitimate reason to lift a lockout that exists to slow down someone who lacks that kind of access.
- **`forgot-password`/`reset-password` don't check `enabled` (Day 13, deliberate scope boundary):** an unverified account can request and complete a password reset just like a verified one — clicking an emailed reset link is itself a proof of email ownership, arguably as strong a signal as clicking a verification link. What it does *not* do is also flip `enabled` to `true` as a side effect; that would be a reasonable follow-on feature (finishing a reset for a never-verified account could count as verifying it too), but it's not something the roadmap asked for on Day 13, and conflating "I proved I own this email" with "this account is now fully activated" is a real product decision worth making deliberately rather than as an incidental side effect of this change. Noted here so it reads as a boundary, not a gap: today, a successfully-reset-but-still-unverified account still can't log in until it's separately verified.
- More decisions will be documented here as new phases (testing, docs/polish) begin to raise them.

## License

MIT
