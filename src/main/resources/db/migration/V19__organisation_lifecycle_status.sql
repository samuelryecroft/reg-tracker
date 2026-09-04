-- T168(b): one organisation lifecycle field, designed once (Kevin, 2026-09-05) so that the KEK
-- activation guard, T170's archive/soft-delete and T166 §5's auto-provision all read the same
-- column rather than each growing its own flag.
--
-- ORDER MATTERS HERE, and both halves are deliberate:
--
-- 1. ADD COLUMN ... DEFAULT 'ACTIVE' NOT NULL. Existing organisations are in use and must stay
--    usable: backfilling them to PENDING would block child creation across the entire estate the
--    moment this deploys. The default does the backfill in one statement, which is also why NOT
--    NULL can be declared here - adding it before a backfill fails on a populated table.
--
-- 2. DROP DEFAULT immediately afterwards. A column that keeps 'ACTIVE' as its default fails OPEN in
--    the one direction that matters: any future insert that forgets to set a status silently
--    produces a usable organisation and the guard becomes decorative without anything failing.
--    Without a default it is a NOT NULL violation - loud, immediate, and impossible to ship past.
--    Organisation.status also initialises to PENDING in Java, which is what covers the ordinary
--    path; this covers everything that bypasses the entity.
--
-- Stored as varchar rather than a native enum to match how org_type is already persisted
-- (@Enumerated(STRING)): one convention in this table, not two.

ALTER TABLE organisations
    ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;

ALTER TABLE organisations
    ALTER COLUMN status DROP DEFAULT;
