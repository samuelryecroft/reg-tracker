-- T168(b): one organisation lifecycle field, designed once (Kevin, 2026-09-05) so that the KEK
-- activation guard, T170's archive/soft-delete and T166 §5's auto-provision all read the same
-- column rather than each growing its own flag.
--
-- TWO STATEMENTS, DOING TWO DIFFERENT JOBS. The first sets what EXISTING rows get; the second sets
-- what FUTURE inserts get. Conflating them is what makes this migration look like a one-liner.
--
-- 1. ADD COLUMN ... DEFAULT 'ACTIVE' NOT NULL. Existing organisations are in use and must stay
--    usable: backfilling them to PENDING would block child creation across the entire estate the
--    moment this deploys. The default does the backfill in one statement - on Postgres a constant
--    default is a metadata-only add, no table rewrite - which is also why NOT NULL can be declared
--    here, since adding it before a backfill fails on a populated table.
--
-- 2. SET DEFAULT 'PENDING'. This is the interesting one, and it exists because of a real deploy
--    window rather than as tidiness.
--
--    deploy.yml runs the DB-plane job to Succeeded BEFORE the new jar goes live, so for a few
--    minutes the OLD jar is talking to this NEW schema. That jar knows nothing about `status`, so
--    an organisation INSERT from it omits the column entirely. With no default that is a NOT NULL
--    violation: an admin's org-create 500s mid-onboarding, which is the same shape as the incident
--    this whole ticket exists to remove. The window is not avoidable - but WHAT THE OLD JAR WRITES
--    DURING IT IS ENTIRELY OUR CHOICE.
--
--    PENDING is the safe choice AND the correct one. An organisation created during the window
--    lands pending, shows as pending in the admin list, and is activated through the KEK gate like
--    any other. The window stops being a hazard to tolerate and starts producing exactly the state
--    the feature wants.
--
--    It also makes the database and the entity AGREE: Organisation.status initialises to PENDING in
--    Java. A column defaulting to ACTIVE while the entity defaults to PENDING would be two sources
--    for one fact, disagreeing, in the direction that fails open.
--
--    NOTE WHAT THIS IS NOT. Splitting the drop into a later migration was considered and does not
--    work here: run-db-plane.sh line 99 invokes `flyway ... migrate` with NO -target, so every
--    pending migration applies in that single job run, seconds apart, all of it before the jar
--    swaps. A follow-up migration in the same release would land inside the very window it claims
--    to protect. Verified in the script rather than assumed.
--
-- THE END STATE WE STILL WANT is no default at all, so that an insert bypassing the entity fails
-- LOUDLY rather than merely landing safe. That belongs in a later release, once no old jar can be
-- running - and the point of PENDING here is that if it is never written, we lose loudness, not
-- safety. Make forgetting harmless rather than make remembering reliable.
--
-- Stored as varchar rather than a native enum to match how org_type is already persisted
-- (@Enumerated(STRING)): one convention in this table, not two.

ALTER TABLE organisations
    ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL;

ALTER TABLE organisations
    ALTER COLUMN status SET DEFAULT 'PENDING';
