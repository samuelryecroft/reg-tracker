ALTER TABLE interview_reports RENAME COLUMN risks_identified TO risks_identified_during_episode;
ALTER TABLE interview_reports RENAME COLUMN contractor_recommendations TO recommendations;

ALTER TABLE interview_reports
    DROP COLUMN persons_present,
    DROP COLUMN childs_account,
    DROP COLUMN push_factors,
    DROP COLUMN pull_factors,
    DROP COLUMN wishes_and_feelings,
    DROP COLUMN actions_and_referrals;

ALTER TABLE interview_reports
    -- Details
    ADD COLUMN within_72_hours                     BOOLEAN,
    ADD COLUMN if_not_why_late                      TEXT,
    ADD COLUMN consultation_with_home_staff         TEXT,
    ADD COLUMN previously_missing                   BOOLEAN,
    ADD COLUMN missing_occasions_last_30_days       INTEGER,
    ADD COLUMN confidentiality_explained            BOOLEAN,

    -- Return Home Interview
    ADD COLUMN interview_accepted                   BOOLEAN,
    ADD COLUMN interview_declined_reason            TEXT,
    ADD COLUMN where_were_you_while_missing         TEXT,
    ADD COLUMN who_were_you_with_while_missing      TEXT,
    ADD COLUMN what_made_you_go_missing             TEXT,
    ADD COLUMN what_can_be_done_to_address_reasons  TEXT,
    ADD COLUMN considered_self_missing              BOOLEAN,
    ADD COLUMN what_did_you_do_while_missing        TEXT,
    ADD COLUMN what_happened_when_returned          TEXT,
    ADD COLUMN prevent_future_missing_suggestions   TEXT,
    ADD COLUMN additional_comments_from_young_person TEXT,
    ADD COLUMN additional_info_from_parent_carer    TEXT,

    -- Future Incidents
    ADD COLUMN risks_increase_future_episodes       TEXT,
    ADD COLUMN safeguarding_concerns_to_explore     TEXT,
    ADD COLUMN info_to_help_locate_future           TEXT,

    -- Interviewer's Comments / Recommendations / Declaration
    ADD COLUMN interviewer_comments                 TEXT,
    ADD COLUMN conducted_by_statement                TEXT,
    ADD COLUMN date_report_shared                    DATE;
