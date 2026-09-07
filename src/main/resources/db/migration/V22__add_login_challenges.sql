-- T322: the second factor's state. A one-time code issued after a correct password and before the
-- session becomes authenticated.
--
-- IN THE DATABASE, AND THAT IS THE WHOLE POINT OF THE TABLE. LoginAttemptService holds the failed-
-- login throttle in memory and says why: a single App Service instance, ~20 users, counters reset on
-- restart. That is a sound trade FOR A THROTTLE, because losing the state loses only an attacker's
-- accumulated penalty - it degrades toward "a few more tries", never toward "wrong answer".
--
-- The identical choice for a CHALLENGE is not a security trade-off, it is a CORRECTNESS BUG, and a
-- well-disguised one. A code issued by instance A cannot be verified by instance B, and a restart
-- between "send" and "type it in" invalidates a code the user is holding in their hand. The symptom
-- is "the code doesn't work sometimes" - which reads exactly like a mail-delivery problem and would
-- be debugged as one, in the mail layer, for a long time. The failure is silent, intermittent, and
-- points away from itself.
--
-- NO DEPLOY-WINDOW PROBLEM, unlike V19 and V21. The DB plane runs Flyway to completion BEFORE the
-- new jar is swapped in, so the OLD jar briefly meets this schema - but this is a NEW table that the
-- old jar never reads and never inserts into. There is no column it could omit and no default that
-- has to be right for two different readers. It is inert until the jar that knows about it arrives.
--
-- WHAT IS DELIBERATELY NOT HERE: the code itself. Only a hash is stored, so a database read, a
-- backup, or a stray log cannot yield a live code. And no email address column - the address is
-- read from users.email at send time, so a challenge can never be delivered to an address the
-- account no longer has.

CREATE TABLE login_challenges (
    id           BIGSERIAL    PRIMARY KEY,

    -- ON DELETE CASCADE, and it is the opposite of the choice audit_events makes on purpose. An
    -- audit row must outlive its actor, so its FK is nullable and never cascades. A challenge is
    -- ephemeral authentication state with no evidentiary value whatsoever - what happened is
    -- recorded in audit_events, not here - so a challenge belonging to a removed user is simply
    -- meaningless and should go with them.
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- BCrypt output is 60 chars; sized with room rather than to the byte.
    code_hash    VARCHAR(100) NOT NULL,

    created_at   TIMESTAMP    NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,

    -- Set when the code is accepted. A consumed challenge is never reusable: single-use is enforced
    -- by this column, not by deleting the row, so a replay attempt is a visible fact rather than a
    -- missing one.
    consumed_at  TIMESTAMP,

    -- Counted UP and checked against a cap, so the challenge BURNS rather than merely slowing down.
    -- Six digits is 10^6, which a challenge that survives its own failures hands over eventually; a
    -- rate limit alone only decides how long that takes.
    attempts     INT          NOT NULL DEFAULT 0
);

-- The hot path: "the newest live challenge for this user".
CREATE INDEX idx_login_challenges_user ON login_challenges (user_id, created_at DESC);

-- Expired rows are swept on a schedule; this is the index that sweep reads.
CREATE INDEX idx_login_challenges_expires ON login_challenges (expires_at);
