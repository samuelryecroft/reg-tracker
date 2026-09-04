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
-- 2. THE DEFAULT IS KEPT HERE, and dropped in V20. That split is the expand/contract half of this
--    change and it is deliberate. Our deploy has a real coexistence window - the migration job runs
--    to completion BEFORE the new jar is serving - during which the OLD jar is talking to this new
--    schema. It never writes `status`, so an organisation INSERT in that window would fail NOT NULL
--    if the default were already gone. Every other migration in this estate is old-jar compatible;
--    "do not create an organisation for ten minutes" is operational discipline, and by this
--    codebase's own standard that is not a control.
--
--    The default is DORMANT in the meantime: Organisation.status initialises to PENDING in Java, so
--    the new jar always writes a value and never sees it. It exists solely to keep the window safe.
--
-- Stored as varchar rather than a native enum to match how org_type is already persisted
-- (@Enumerated(STRING)): one convention in this table, not two.

ALTER TABLE organisations
    ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;
