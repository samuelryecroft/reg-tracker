-- T353c: the self-service password-reset token (design shared-handoff/T353-DESIGN-password-reset.md
-- §3). One row per reset request. The token itself is 32 bytes of SecureRandom, base64url, and is
-- NEVER stored - only its SHA-256 hash, so a database read, a backup, or a stray log cannot yield a
-- usable reset link.
--
-- NO DEPLOY-WINDOW PROBLEM (cf. V19/V21, and like V22/V23): this is a NEW table the old jar never
-- reads and never writes, so it is inert until the jar that knows about it arrives. There is no
-- column an old reader could omit and no default that has to be right for two readers at once.
--
-- WHY THE HASH IS SHA-256, NOT BCRYPT, and it is the mirror image of the sign-in code (T322): the
-- code is six digits - low entropy - so it needs a slow hash to be worth anything. A 256-bit random
-- token is high entropy: it cannot be brute-forced regardless, a slow hash buys nothing, and BECAUSE
-- THE LOOKUP IS BY HASH, a per-row BCrypt salt would make the hash unindexable and force a scan of
-- every live row. Same table shape, opposite reasoning, and the reason is entropy, not taste.

CREATE TABLE password_reset_tokens (
    id                    BIGSERIAL    PRIMARY KEY,

    -- ON DELETE CASCADE, like login_challenges and for the same reason: a reset token is ephemeral
    -- authentication state with no evidentiary value (what happened is in audit_events), so a token
    -- belonging to a removed user is meaningless and should go with them.
    user_id               BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 hex of the emailed token. UNIQUE both to enforce single-issue and because the lookup
    -- is BY this column - the unique index is the lookup index. 64 hex chars; sized with room.
    token_hash            VARCHAR(100) NOT NULL,

    created_at            TIMESTAMP    NOT NULL,

    -- TTL 30 minutes (the link). Distinct from the reset CODE's 10-minute validity, which lives on
    -- the login_challenges row this token points at via challenge_id.
    expires_at            TIMESTAMP    NOT NULL,

    -- Set when the reset is applied. Single-use is enforced by this column, not by deleting the row,
    -- so a replay meets a CONSUMED token (which the endpoint renders identically to expired and to
    -- never-existed - the difference is only useful to someone probing).
    consumed_at           TIMESTAMP,

    -- The chosen new password, already BCrypt-encoded, held here between step C (submit) and step D
    -- (apply). NEVER plaintext, and never on users until the code verifies. This is the same class
    -- of secret as users.password and no worse - but it is a password that MAY NEVER BE ADOPTED, so
    -- it MUST be nulled on consume and swept with the row on expiry. Null until step C.
    pending_password_hash VARCHAR(100),

    -- The reset-scoped login_challenges row minted in step C (purpose = PASSWORD_RESET, T353b). The
    -- verifier requires the submitted code to be THIS challenge, not merely a live reset code for the
    -- user. Null until step C. No FK-cascade concerns beyond login_challenges' own ON DELETE CASCADE.
    challenge_id          BIGINT       REFERENCES login_challenges(id) ON DELETE SET NULL,

    -- The source IP of the request, for rate-limit forensics (the per-IP throttle itself is T353d,
    -- in-memory). Not authentication state; purely evidential for abuse investigation.
    requested_ip          VARCHAR(45),

    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash)
);

-- Expired rows are swept on a schedule (the sweep is application code, T353c); this is the index it
-- reads. The lookup-by-hash is already served by the UNIQUE constraint's index.
CREATE INDEX idx_password_reset_tokens_expires ON password_reset_tokens (expires_at);
