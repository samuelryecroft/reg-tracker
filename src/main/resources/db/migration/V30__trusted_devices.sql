-- T365 stage 1: the trusted-device table (design shared-handoff/T365-DESIGN-trusted-device.md §8).
-- A trusted-device token is a bearer credential that WAIVES THE SIGN_IN SECOND FACTOR for a device
-- for a fixed window. This migration is the schema for it and NOTHING ELSE reads or writes it yet:
-- stage 1 is the inert core (no success-handler branch, no UI, no per-org flag), so - exactly like
-- V27 (password_reset_tokens) - this is a NEW table the shipped jar never touches until the code
-- that knows about it arrives. There is no deploy-window problem and no default an old reader could
-- get wrong, because there is no reader.
--
-- WHY THE HASH IS SHA-256, NOT BCRYPT (identical reasoning to V27): the token is 256 bits of
-- SecureRandom, so it cannot be brute-forced and a slow per-row salt buys nothing while making the
-- by-hash lookup unindexable. The token itself is NEVER stored - only TokenHashing.sha256Hex of it -
-- so a database read, a backup, or a stray log line cannot yield a usable cookie.

CREATE TABLE trusted_devices (
    id                 BIGSERIAL    PRIMARY KEY,

    -- ON DELETE CASCADE, like password_reset_tokens and login_challenges: a trusted-device row is
    -- ephemeral authentication state with no evidentiary value (what happened is in audit_events),
    -- so a trust belonging to a removed user is meaningless and goes with them.
    user_id            BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 hex of the cookie token. UNIQUE both to enforce single-issue and because the lookup is
    -- BY this column - the unique index IS the lookup index. 64 hex chars; sized with room.
    token_hash         VARCHAR(100) NOT NULL,

    -- First-trust instant. It is carried FORWARD unchanged across rotations (below), so it always
    -- names when the DEVICE was first trusted, not when the current token value was minted - that is
    -- the "trusted 2 Sept" the account page shows (design §2.2). Rotation advances token_hash and
    -- last_used_at; it never touches this.
    created_at         TIMESTAMP    NOT NULL,

    -- ABSOLUTE expiry, 30 days from first trust, and it NEVER slides (design §4). Sliding would give
    -- the longest life to the attacker who uses a stolen cookie the most; absolute guarantees a
    -- re-proof on a known date and is what "trust this device for 30 days" honestly means. Rotation
    -- carries this value forward unchanged - a token used on day 29 still dies on day 30.
    expires_at         TIMESTAMP    NOT NULL,

    -- When this token value was last presented. Advisory (for the "last used today" display); never
    -- an input to expiry, precisely because expiry is absolute.
    last_used_at       TIMESTAMP,

    -- The coarse browser/OS string only (e.g. "Chrome on Windows"), for the Devices list. NEVER a
    -- device fingerprint: the audit trail is kept forever and is admin-readable, and a fingerprint in
    -- it is a tracking record of a member of staff arriving in a child's audit context (design §5).
    user_agent_summary VARCHAR(200),

    -- Set when this token value is retired: either superseded by a rotation (every use mints a fresh
    -- token and revokes its predecessor) or revoked outright (password change, "forget device", etc).
    -- A revoked row is KEPT, not deleted, so that a later presentation of that same token is FOUND -
    -- a presented-but-already-revoked token is positive evidence of theft (design §4), which a
    -- missing row could not distinguish from ordinary garbage. Expired rows are swept in bulk later.
    revoked_at         TIMESTAMP,

    CONSTRAINT uq_trusted_devices_token_hash UNIQUE (token_hash)
);

-- The by-user lookup (revoke-all-for-user, the Devices list). Lookup-by-hash is already served by
-- the UNIQUE constraint's index.
CREATE INDEX idx_trusted_devices_user_id ON trusted_devices (user_id);
