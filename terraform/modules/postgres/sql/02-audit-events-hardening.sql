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

-- Read-only backstop (T352): after Flyway, SELECT on every table for rht_readonly, matching the app
-- backstop above. 01's ALTER DEFAULT PRIVILEGES already covers tables the migrator creates; this
-- catches any that slipped past it, so the jump-box read role survives a rebuild by construction.
-- SELECT only -- it does not touch audit_events' append-only model (readonly may read it, never mutate).
-- Guarded so a server where the role does not yet exist re-runs cleanly instead of erroring.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rht_readonly') THEN
    EXECUTE 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO rht_readonly';
  END IF;
END
$$;

REVOKE UPDATE, DELETE ON audit_events FROM rht_app;
