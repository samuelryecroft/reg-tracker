ALTER TABLE users ADD COLUMN organisation_id BIGINT REFERENCES organisations (id);
CREATE INDEX idx_users_organisation_id ON users (organisation_id);

-- Existing coordinator/contractor accounts belong to the seeded default Supplier org.
UPDATE users SET organisation_id = 1 WHERE role IN ('COORDINATOR', 'CONTRACTOR');
