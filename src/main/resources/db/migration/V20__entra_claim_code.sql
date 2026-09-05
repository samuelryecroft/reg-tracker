-- T197: admin-issued one-time claim code, so onboarding needs no portal trip and no GUID paste.
--
-- The identity key does not change. idp_subject remains the only thing sign-in matches on; this is
-- purely the mechanism by which an oid first gets pinned onto a user the admin already created.
-- Valid login stays necessary-but-not-sufficient, and there is still no just-in-time provisioning.
--
-- THE CODE IS SPLIT: a public SELECTOR and a secret VERIFIER, rendered to the user as one code,
-- XXXXX-XXXXX. The design fixes a SHORT code (~50 bits) protected by a strict attempt lockout and a
-- SLOW hash - and a slow hash is salted, so it cannot be looked up. The selector is what the lookup
-- uses; only the verifier is hashed. This is the standard shape for exactly this problem, and it is
-- what makes "5 attempts per code" countable at all: without a selector there is no code to count
-- attempts against until after you have found it.
--
-- ONLY THE VERIFIER HASH IS STORED, and it is a credential: the code is shown to the admin once, at
-- issue, and is unrecoverable afterwards - an admin can reissue, never reveal. The same rule that
-- keeps a decrypted date of birth out of telemetry (T179) applies to it, because App Insights feeds
-- a Log Analytics workspace shared across the platform with no field-level encryption.
--
-- On users rather than in a side table: a code is a property of one user, at most one at a time, and
-- reissuing replaces it. A side table would allow two live codes for one account - a state with no
-- meaning here and one more thing the redemption query would have to exclude.
ALTER TABLE users ADD COLUMN claim_code_selector VARCHAR(16);
ALTER TABLE users ADD COLUMN claim_code_verifier_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN claim_code_issued_at TIMESTAMP;
ALTER TABLE users ADD COLUMN claim_code_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN claim_code_consumed_at TIMESTAMP;

-- Attempts against THIS code. At five the code is dead and must be reissued: with ~50 bits the
-- lockout is the control, not the entropy, so this column is load-bearing rather than telemetry.
ALTER TABLE users ADD COLUMN claim_code_attempts INTEGER NOT NULL DEFAULT 0;

-- UNIQUE because the selector is what redemption looks a code up by, and two live codes sharing one
-- would make that lookup ambiguous at the moment it must not be. Partial, so the many users with no
-- outstanding code do not all collide on NULL.
CREATE UNIQUE INDEX uq_users_claim_code_selector ON users (claim_code_selector)
    WHERE claim_code_selector IS NOT NULL;
