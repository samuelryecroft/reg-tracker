-- T97: measure the 72-hour window instead of asking whether it was met.
--
-- V14 belongs to the Entra identity link (PR #12). This is V15 by arrangement so the two in-flight
-- branches do not collide; nothing here depends on V14.

-- When the interview was actually held. This is the END of the statutory clock, and with
-- returned_at below it is what the compliance rate is computed from.
--
-- DELIBERATELY NOT ENCRYPTED, and it must stay that way. The rate filters and aggregates on this
-- column across a whole organisation; encrypting it would make the measurement this migration
-- exists to produce impossible to compute in the database. It is a bare timestamp with no name,
-- no location and no narrative attached - tier 3 in COLUMN-ENCRYPTION-OPTIONS.md, where encrypting
-- does not harden the system, it deletes it.
ALTER TABLE interview_reports ADD COLUMN held_at TIMESTAMP;

-- When a coordinator allocated the request to a visitor. Same reasoning: an unencrypted timestamp,
-- so allocation latency is measurable rather than merely displayable one record at a time.
ALTER TABLE interview_requests ADD COLUMN allocated_at TIMESTAMP;

-- The 72-hour clock's START becomes required.
--
-- Cheap now, expensive later, and that asymmetry is the whole reason to do it before go-live: the
-- database is empty, so this is a plain NOT NULL. After real data exists it becomes a backfill with
-- a policy decision attached - "what return time do we invent for records that never had one?" -
-- and there is no honest answer to that for a statutory record. Same argument as the field
-- encryption timing in COLUMN-ENCRYPTION-OPTIONS.md §5.
--
-- Consequence worth stating: a request can no longer exist without a clock, so the "no return time"
-- remedy path (the standalone record-return-time form) is unreachable and goes with this change.
ALTER TABLE interview_requests ALTER COLUMN returned_at SET NOT NULL;

-- The self-declared answer goes.
--
-- It asked the person who conducted the interview to grade their own compliance, and its third
-- state ("Unknown", stored as NULL) was being counted as a breach while still sitting in the
-- denominator - so an unanswered question depressed an organisation's rate exactly as a real
-- failure would. Nothing derived from a recorded fact can drift like that: within-72-hours is now
-- computed from returned_at and held_at, and where it cannot be computed the interview is excluded
-- from the rate rather than scored against it.
ALTER TABLE interview_reports DROP COLUMN within_72_hours;
