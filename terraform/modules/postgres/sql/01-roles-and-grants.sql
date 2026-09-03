-- reg-tracker (return-home-tracker) -- Postgres role split for least privilege (WS-G).
--
-- RUN ORDER (pre-deploy pipeline step, WS-E wires it):
--   1. THIS FILE            as the SERVER ADMIN  -- create roles + baseline grants
--   2. Flyway migrate       as rht_migrator      -- applies V1..Vn (DDL)
--   3. jar swap             app runs as rht_app  -- DML only
--   4. 02-audit-events-hardening.sql  as admin   -- append-only backstop on audit_events
--
-- WHERE IT RUNS: from INSIDE the VNet. On the prod/private path the server has
-- public_network_access_enabled=false, so it is unreachable from a hosted `terraform apply`;
-- role creation therefore runs in the same VNet-connected pre-deploy step as Flyway, not from CI.
--
-- IDEMPOTENT: safe to re-run on every deploy.
--
-- PASSWORDS ARE NOT LITERALS. psql substitutes them at run time from the Key Vault secrets that
-- Terraform provisions (MIGRATOR-DB-PASSWORD / RUNTIME-DB-PASSWORD), e.g.:
--   psql "host=... dbname=return_home_tracker user=<admin> sslmode=require" \
--        -v migrator_pw="$MIGRATOR_PW" -v runtime_pw="$RUNTIME_PW" \
--        -f 01-roles-and-grants.sql
-- (Nothing secret is committed here; the -v values come from Key Vault, read by the pre-deploy job.)

\set ON_ERROR_STOP on

-- ---- MIGRATOR role: owns the schema, runs migrations (DDL). NEVER used at runtime. ----
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rht_migrator') THEN
    CREATE ROLE rht_migrator LOGIN;
  END IF;
END
$$;
ALTER ROLE rht_migrator WITH LOGIN PASSWORD :'migrator_pw';

-- ---- RUNTIME role: the application. DML only, never DDL. ----
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rht_app') THEN
    CREATE ROLE rht_app LOGIN;
  END IF;
END
$$;
ALTER ROLE rht_app WITH LOGIN PASSWORD :'runtime_pw';

-- Migrator may create objects in public: tables, indexes, and plpgsql functions (V11 creates a
-- plpgsql function + trigger). Trigger creation needs no extra grant -- the migrator owns the
-- tables it creates, and ownership carries the right to create triggers on them.
GRANT USAGE, CREATE ON SCHEMA public TO rht_migrator;

-- Runtime may resolve the schema but MUST NOT create in it (no CREATE  ->  no DDL from the app).
GRANT USAGE ON SCHEMA public TO rht_app;

-- DML on everything the migrator has ALREADY created. No-op on a fresh DB (nothing exists yet -- the
-- default privileges below cover objects Flyway is about to create); on an existing DB this catches
-- tables created before this role split was introduced.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES   IN SCHEMA public TO rht_app;
GRANT USAGE, SELECT                 ON ALL SEQUENCES IN SCHEMA public TO rht_app;

-- Future objects the migrator creates (Flyway, the very next step) become DML-accessible to the
-- runtime role automatically -- so a new migration never needs a follow-up GRANT for the app.
ALTER DEFAULT PRIVILEGES FOR ROLE rht_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES   TO rht_app;
ALTER DEFAULT PRIVILEGES FOR ROLE rht_migrator IN SCHEMA public
  GRANT USAGE, SELECT                 ON SEQUENCES TO rht_app;

-- NOTE (fresh vs existing DB): this forward model assumes Flyway runs as rht_migrator, so the
-- migrator owns the schema objects and can add the V11 FK REFERENCES to users/organisations/homes.
-- On a DB whose earlier tables were created by the admin, run a one-time ownership handover
-- (REASSIGN OWNED BY <admin> TO rht_migrator, or ALTER TABLE ... OWNER TO rht_migrator) before the
-- first migrator-run migration. See terraform/README.md (WS-G).
