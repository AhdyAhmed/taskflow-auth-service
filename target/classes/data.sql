-- Dev-only seed data.
--
-- seed.manager@taskflow.dev: added Day 3 so Project/Task creation had a
-- real user id to reference before registration existed. Its
-- password_hash is a placeholder, not a real bcrypt hash, so it can
-- never log in — register a real account via /auth/register instead.
--
-- seed.admin@taskflow.dev: added Day 7 to solve RBAC's bootstrap
-- problem — PUT /api/admin/users/{id}/role is ADMIN-only, but there's
-- no way to create the very first ADMIN through the API at all (every
-- self-registered account is forced to USER, on purpose — see Day 4).
-- This row has a REAL bcrypt hash for the password "Admin123!" so it
-- can actually log in and promote other accounts. Obviously this would
-- never ship as a real seed in a production system; it exists purely so
-- the RBAC flow is exercisable end-to-end locally.
INSERT INTO users (email, password_hash, role, enabled, failed_login_attempts, created_at, updated_at)
VALUES ('seed.manager@taskflow.dev', 'placeholder-not-a-real-hash', 'MANAGER', true, 0, now(), now())
ON CONFLICT (email) DO NOTHING;

INSERT INTO users (email, password_hash, role, enabled, failed_login_attempts, created_at, updated_at)
VALUES ('seed.admin@taskflow.dev', '$2b$10$r/sDsBzw9ER0ZXKknbH2wO0jo9QEv174aLwicWK/DRJVokaqFHwpW', 'ADMIN', true, 0, now(), now())
ON CONFLICT (email) DO NOTHING;
