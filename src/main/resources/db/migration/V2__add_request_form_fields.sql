ALTER TABLE interview_requests
    -- Details of the Young Person
    ADD COLUMN legal_status               VARCHAR(255),
    ADD COLUMN missing_since              TIMESTAMP,
    ADD COLUMN known_risks                TEXT,
    ADD COLUMN childs_comments            TEXT,
    ADD COLUMN missing_episode_details    TEXT,
    ADD COLUMN missing_in_last_6_months   BOOLEAN,
    ADD COLUMN missing_5_times_in_30_days BOOLEAN,
    ADD COLUMN strategy_meeting_requested BOOLEAN,
    ADD COLUMN important_people           TEXT,
    ADD COLUMN about_young_person         TEXT,

    -- Details of Professionals
    ADD COLUMN social_worker_details          TEXT,
    ADD COLUMN consent_provided               BOOLEAN,
    ADD COLUMN placing_local_authority        VARCHAR(255),
    ADD COLUMN police_mfh_coordinator_details TEXT,
    ADD COLUMN parents_details                TEXT,
    ADD COLUMN other_professionals            TEXT,

    -- Your Details (the submitter)
    ADD COLUMN submitter_organisation      VARCHAR(255),
    ADD COLUMN submitter_name_and_role     VARCHAR(255),
    ADD COLUMN relationship_to_young_person VARCHAR(255),
    ADD COLUMN submitter_address           VARCHAR(500),
    ADD COLUMN submitter_contact_details   VARCHAR(255),
    ADD COLUMN best_times_to_visit         VARCHAR(255);
