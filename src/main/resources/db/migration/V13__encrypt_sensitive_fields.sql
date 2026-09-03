-- Field-level encryption, phase 1 (COLUMN-ENCRYPTION-OPTIONS.md).
--
-- Every column here holds AES-256-GCM ciphertext, encrypted in the application under the owning
-- organisation's field data key, which is itself wrapped by that organisation's Key Vault KEK. The
-- database never sees a key and never sees plaintext for these columns.
--
-- WHY THE PLAINTEXT COLUMNS ARE DROPPED RATHER THAN KEPT ALONGSIDE. The design document sets out an
-- expand/contract migration - add, dual-write, backfill, flip, drop - because it assumes existing
-- rows. This runs against an EMPTY database, before any real record exists, which is the entire
-- reason for doing it now: there is nothing to backfill and nothing to dual-write. Keeping the
-- plaintext columns would leave writable columns that nothing reads, which is precisely how a system
-- ends up 'encrypted' with plaintext still on disk. Dropping them here means that cannot happen.
--
-- It also means this migration is DESTRUCTIVE if it ever meets a populated database. It must not be
-- applied to one. Against real data the expand/contract path in COLUMN-ENCRYPTION-OPTIONS.md §5 is
-- the correct procedure, including the VACUUM FULL that actually removes the old tuples.
--
-- Runs as rht_migrator (WS-G). The migrator has no Key Vault identity and must never be given one:
-- only the application can encrypt, which is also why no data movement happens here.

-- One wrapped data key per organisation. The unwrapped key exists only in application memory.
CREATE TABLE org_field_key (
    id              BIGSERIAL   PRIMARY KEY,
    organisation_id BIGINT      NOT NULL UNIQUE REFERENCES organisations (id),
    key_name        TEXT        NOT NULL,
    key_version     TEXT        NOT NULL,
    wrap_algorithm  TEXT        NOT NULL,
    wrapped_key     BYTEA       NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE org_field_key IS
    'Per-organisation field data keys, stored wrapped by the organisation KEK in Key Vault. '
    'The UNIQUE constraint on organisation_id is load-bearing: a second key for one organisation '
    'would silently orphan every column encrypted under the first.';


-- Tier 2: child identifiers. The initials are deliberately NOT encrypted - see below.
ALTER TABLE children
    DROP COLUMN first_name,
    ADD COLUMN first_name_enc TEXT NOT NULL,
    DROP COLUMN last_name,
    ADD COLUMN last_name_enc TEXT NOT NULL,
    DROP COLUMN date_of_birth,
    ADD COLUMN date_of_birth_enc TEXT NOT NULL,
    DROP COLUMN local_case_reference,
    ADD COLUMN local_case_reference_enc TEXT,
    ADD COLUMN first_name_initial VARCHAR(1),
    ADD COLUMN last_name_initial VARCHAR(1);

-- date_of_birth becomes TEXT because ciphertext is not a date. Nothing queries or sorts by it
-- today; an age-range filter would need the blind-index approach in the design document, not this
-- column back.
COMMENT ON COLUMN children.first_name_initial IS
    'PLAINTEXT, on purpose. Lets lists and headings show "J.S." without unwrapping a key and '
    'decrypting every row, and keeps those screens working when a name cannot be decrypted. The '
    'first-letter leak is the accepted price of a usable interface.';


-- Tier 1: request narrative. Queried only by id and foreign key, never filtered or sorted on, so
-- encrypting them changes no query, screen or dashboard tile.
ALTER TABLE interview_requests
    DROP COLUMN notes,
    ADD COLUMN notes_enc TEXT,
    DROP COLUMN known_risks,
    ADD COLUMN known_risks_enc TEXT,
    DROP COLUMN childs_comments,
    ADD COLUMN childs_comments_enc TEXT,
    DROP COLUMN missing_episode_details,
    ADD COLUMN missing_episode_details_enc TEXT,
    DROP COLUMN important_people,
    ADD COLUMN important_people_enc TEXT,
    DROP COLUMN about_young_person,
    ADD COLUMN about_young_person_enc TEXT,
    DROP COLUMN social_worker_details,
    ADD COLUMN social_worker_details_enc TEXT,
    DROP COLUMN police_mfh_coordinator_details,
    ADD COLUMN police_mfh_coordinator_details_enc TEXT,
    DROP COLUMN legal_status,
    ADD COLUMN legal_status_enc TEXT,
    DROP COLUMN placing_local_authority,
    ADD COLUMN placing_local_authority_enc TEXT;

-- Tier 1: report narrative - the child's own account. Queried only by id and foreign key, never filtered or sorted on, so
-- encrypting them changes no query, screen or dashboard tile.
ALTER TABLE interview_reports
    DROP COLUMN if_not_why_late,
    ADD COLUMN if_not_why_late_enc TEXT,
    DROP COLUMN consultation_with_home_staff,
    ADD COLUMN consultation_with_home_staff_enc TEXT,
    DROP COLUMN interview_declined_reason,
    ADD COLUMN interview_declined_reason_enc TEXT,
    DROP COLUMN where_were_you_while_missing,
    ADD COLUMN where_were_you_while_missing_enc TEXT,
    DROP COLUMN who_were_you_with_while_missing,
    ADD COLUMN who_were_you_with_while_missing_enc TEXT,
    DROP COLUMN what_made_you_go_missing,
    ADD COLUMN what_made_you_go_missing_enc TEXT,
    DROP COLUMN what_can_be_done_to_address_reasons,
    ADD COLUMN what_can_be_done_to_address_reasons_enc TEXT,
    DROP COLUMN what_did_you_do_while_missing,
    ADD COLUMN what_did_you_do_while_missing_enc TEXT,
    DROP COLUMN what_happened_when_returned,
    ADD COLUMN what_happened_when_returned_enc TEXT,
    DROP COLUMN prevent_future_missing_suggestions,
    ADD COLUMN prevent_future_missing_suggestions_enc TEXT,
    DROP COLUMN additional_comments_from_young_person,
    ADD COLUMN additional_comments_from_young_person_enc TEXT,
    DROP COLUMN additional_info_from_parent_carer,
    ADD COLUMN additional_info_from_parent_carer_enc TEXT,
    DROP COLUMN risks_identified_during_episode,
    ADD COLUMN risks_identified_during_episode_enc TEXT,
    DROP COLUMN risks_increase_future_episodes,
    ADD COLUMN risks_increase_future_episodes_enc TEXT,
    DROP COLUMN safeguarding_concerns_to_explore,
    ADD COLUMN safeguarding_concerns_to_explore_enc TEXT,
    DROP COLUMN info_to_help_locate_future,
    ADD COLUMN info_to_help_locate_future_enc TEXT,
    DROP COLUMN interview_location,
    ADD COLUMN interview_location_enc TEXT;
