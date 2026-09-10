-- TEMPORARY, dev-only seed data.
--
-- Registration doesn't exist until Day 4-6, but Project.owner and
-- Task.createdBy are NOT NULL foreign keys to `users` (see Day 2). This
-- row exists purely so the CRUD endpoints built on Day 3 have a real
-- user id to point at when tested manually (curl/Postman/Swagger).
--
-- password_hash is a placeholder, not a real bcrypt hash — login isn't
-- wired up yet, so nothing checks it. This file gets deleted once
-- registration makes it obsolete.
INSERT INTO users (email, password_hash, role, enabled, failed_login_attempts, created_at, updated_at)
VALUES ('seed.manager@taskflow.dev', 'placeholder-not-a-real-hash', 'MANAGER', true, 0, now(), now())
ON CONFLICT (email) DO NOTHING;
