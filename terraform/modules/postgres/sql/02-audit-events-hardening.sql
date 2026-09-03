-- reg-tracker (return-home-tracker) -- post-migration grant hardening (WS-G).
--
-- Run AS the SERVER ADMIN, AFTER Flyway has applied all migrations (audit_events is created by
-- V11, so it does not exist when 01-roles-and-grants.sql runs) and before/at the jar swap.
-- IDEMPOTENT: safe to re-run on every deploy.
--
-- Two jobs:
--   1. Backstop: GRANT DML on any migrator-created tables that predate the ALTER DEFAULT PRIVILEGES
--      in 01 (an existing DB). No-op on a clean deploy where default privileges already applied.
--   2. audit_events is append-only (AUDIT-PLAN.md). The V11 plpgsql trigger already rejects
--      UPDATE/DELETE at runtime; this REVOKE removes the privilege too, so the runtime role is
--      INSERT/SELECT-only on that table -- a tamper attempt fails on the grant, not just the
--      trigger (defense in depth).

\set ON_ERROR_STOP on

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES   IN SCHEMA public TO rht_app;
GRANT USAGE, SELECT                 ON ALL SEQUENCES IN SCHEMA public TO rht_app;

REVOKE UPDATE, DELETE ON audit_events FROM rht_app;
