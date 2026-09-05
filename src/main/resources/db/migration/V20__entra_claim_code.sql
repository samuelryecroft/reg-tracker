-- T197: admin-issued one-time claim code, so onboarding needs no portal trip and no GUID paste.
--
-- The identity key does not change. idp_subject remains the only thing sign-in matches on; this is
-- purely the mechanism by which an oid first gets pinned onto a user the admin already created.
-- Valid login stays necessary-but-not-sufficient, and there is still no just-in-time provisioning.
--
-- ONLY A HASH IS STORED. The code is a credential: it is shown to the admin once, at issue, and is
-- unrecoverable afterwards - an admin can reissue, never reveal. The same rule that keeps a
-- decrypted date of birth out of telemetry (T179) applies to it, because App Insights feeds a Log
-- Analytics workspace shared across the platform with no field-level encryption.
--
-- On users rather than in a side table: a code is a property of one user, at most one at a time, and
-- reissuing replaces it. A side table would allow two live codes for one account, which is a state
-- with no meaning here and one more thing for the redemption query to have to exclude.
ALTER TABLE users ADD COLUMN claim_code_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN claim_code_issued_at TIMESTAMP;
ALTER TABLE users ADD COLUMN claim_code_expires_at TIMESTAMP;
ALTER TABLE users ADD COLUMN claim_code_consumed_at TIMESTAMP;

-- Redemption looks a code up by its hash across all users, so it is the lookup that needs the index.
-- Not unique: a hash collision is not the concern, but two rows briefly sharing one is not a state
-- worth failing a migration over, and uniqueness here would be a constraint expressing nothing the
-- application relies on. What the application relies on is uq_users_idp_subject, which V14 already
-- provides and which is what actually refuses a second account claiming one identity.
CREATE INDEX idx_users_claim_code_hash ON users (claim_code_hash);
