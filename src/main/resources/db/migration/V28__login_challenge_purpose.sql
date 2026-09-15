-- T353b: what a login_challenges row was issued FOR.
--
-- Until now every row on this table was a sign-in second factor (T322), so the verifier could take
-- "the newest challenge for this user" and issuing one could retire every other live challenge for
-- that user. Self-service password reset (T353) mints its code on this SAME table to inherit the
-- burn/resend/audit machinery - and the moment two flows share it, purpose-blindness is a live hole:
-- a sign-in code would satisfy a reset verifier and vice versa, and starting a reset would silently
-- consume a live sign-in code. This column is what the lookup and the consume-outstanding query
-- filter on, so a code is only ever matched or retired within its own flow.
--
-- DEPLOY WINDOW (the DB plane runs Flyway to completion before the new jar is swapped in, so the OLD
-- jar briefly meets this new column): DEFAULT 'SIGN_IN' is what makes that safe. The old jar knows
-- nothing about purpose and only ever issues sign-in codes, so a row it inserts defaulting to
-- SIGN_IN is correctly labelled. Every existing row is a sign-in challenge for the same reason, so
-- the backfill is that same default. The default is kept, not dropped in a second step (unlike
-- V19/V20): SIGN_IN is the CORRECT value for any writer that does not set the column, not merely a
-- placeholder - and the new jar's entity makes purpose NOT NULL and always sets it, so the default
-- is a deploy-window safety net rather than a value the application ever relies on.
--
-- No CHECK constraint on the value, matching the codebase's other @Enumerated(STRING) columns: the
-- set of purposes is owned by the ChallengePurpose enum, and a CHECK here would be a second place to
-- edit when one is added and a way to make existing rows unreadable when one is removed.

ALTER TABLE login_challenges
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'SIGN_IN';

-- The hot-path lookup is now "the newest LIVE challenge for this user IN THIS FLOW", so purpose
-- joins the index between the user and the time order. This subsumes idx_login_challenges_user
-- (a user_id prefix still serves a user-only lookup), so that index is replaced rather than kept
-- alongside as a redundant second copy of the same leading column.
DROP INDEX idx_login_challenges_user;
CREATE INDEX idx_login_challenges_user_purpose
    ON login_challenges (user_id, purpose, created_at DESC);
