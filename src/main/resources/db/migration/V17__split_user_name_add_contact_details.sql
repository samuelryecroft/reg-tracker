-- T127: a user's name becomes first_name + last_name, and the profile gains an email address and a
-- contact phone number.
--
-- WHY THE SPLIT IS ON THE FIRST SPACE, NOT THE LAST. Both are wrong sometimes; they are wrong on
-- different data. Splitting on the last space reads "Sam de la Cruz" as "Sam de la" + "Cruz", which
-- mangles the compound surnames that are common among the families and staff this system serves.
-- Splitting on the first space reads it as "Sam" + "de la Cruz", which is right, and its failure
-- case is a middle name landing in the surname ("Mary Jane Watson" -> "Mary" + "Jane Watson") -
-- visibly odd on screen and fixable by an admin in one edit, rather than a surname quietly losing
-- its prefix. Neither is lossless; this one loses less, and loses it where a human will notice.
--
-- A single-token name goes wholly to last_name with a null first_name. Nothing is invented and
-- nothing is dropped, and last_name can therefore stay NOT NULL: full_name is NOT NULL and
-- @NotBlank-validated on every write path, so btrim of it is always non-empty.
--
-- NOT DESTRUCTIVE, unlike V13. full_name is read before it is dropped, so this may be applied to a
-- populated database.

ALTER TABLE users
    ADD COLUMN first_name    VARCHAR(255),
    ADD COLUMN last_name     VARCHAR(255),
    ADD COLUMN email         VARCHAR(320),
    ADD COLUMN contact_phone VARCHAR(30);

UPDATE users
SET first_name = CASE
                     WHEN position(' ' in btrim(full_name)) > 0
                         THEN btrim(substring(btrim(full_name) from 1 for position(' ' in btrim(full_name)) - 1))
                     END,
    last_name  = CASE
                     WHEN position(' ' in btrim(full_name)) > 0
                         THEN btrim(substring(btrim(full_name) from position(' ' in btrim(full_name)) + 1))
                     ELSE btrim(full_name)
                 END;

ALTER TABLE users ALTER COLUMN last_name SET NOT NULL;

ALTER TABLE users DROP COLUMN full_name;

-- email and contact_phone are deliberately NULLABLE at the database level and required by the
-- forms. Rows that predate this migration have neither, and inventing a placeholder to satisfy a
-- constraint would put fiction in a statutory record. An admin supplies both on the next edit.
--
-- NO UNIQUE CONSTRAINT ON email, deliberately. username remains the login key and carries the
-- uniqueness that matters; shared mailboxes are ordinary in this sector, so uniqueness here would
-- reject legitimate accounts.
--
-- Indexed on lower(email) because email is the admin-entered identifier the one-time Entra link
-- will look up (see User.idpSubject's javadoc) - case-insensitively, since addresses are quoted in
-- whatever case a person happens to type.
CREATE INDEX idx_users_email ON users (lower(email));

-- NOT ENCRYPTED, and the reason is structural rather than a judgement that these are not sensitive.
-- Field encryption here is per-organisation: EncryptedEntity.owningOrganisationId() picks the key,
-- and a null return must be treated as a failure, never as a default key. users.organisation_id is
-- legitimately null - a platform ADMIN belongs to no organisation, and HOME_STAFF are scoped by
-- homes which since V16 may span several organisations - so there is no key to encrypt those rows
-- under. Encrypting only the rows that happen to have an organisation would be worse than not
-- encrypting: it looks like a protected column and is not one.
--
-- The users table also has no encrypted columns at all today (V13 covered children and
-- interview_requests), and COLUMN-ENCRYPTION-OPTIONS.md section 2 Tier 3 deliberately leaves
-- username in plaintext, describing it as "a field that is a work email address". Encrypting email
-- while username holds the same address in plaintext one column away would be theatre.
--
-- Encrypting this table is worth doing as ONE unit - first_name, last_name, email, contact_phone
-- and the full_name that Tier 2 had already pencilled in - once a platform-scoped key exists.
-- Column-at-a-time is how a table ends up half-encrypted, which is worse than either end state.
