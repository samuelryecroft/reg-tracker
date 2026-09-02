-- Export capability (roadmap 2.5 / D-6). Extracting records as a portable file is a different act
-- from reading them in the application, so it is a separate permission rather than something read
-- access implies.
--
-- Deliberately NOT a new permission model: one boolean on the account. Role eligibility is a hard
-- ceiling enforced in ExportCapability (HOME_STAFF, VISITOR and REVIEWER can never export, whatever
-- this column says); the column is what lets an organisation grant extraction to a named
-- safeguarding lead rather than to everyone who can already read the records.
ALTER TABLE users ADD COLUMN can_export BOOLEAN NOT NULL DEFAULT FALSE;

-- Default off, because the whole point is that extraction is granted deliberately. Existing
-- platform admins are the exception: they already hold every capability in the product, and
-- somebody has to be able to grant it to the first safeguarding lead.
UPDATE users SET can_export = TRUE
WHERE id IN (SELECT user_id FROM user_roles WHERE role = 'ADMIN');
