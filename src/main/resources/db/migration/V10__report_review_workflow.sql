-- Reports now go through a review step (DRAFT -> SUBMITTED -> REJECTED -> SUBMITTED -> APPROVED)
-- before a Home/Viewer can see them. Pre-existing reports were already "final" under the old
-- one-step submit flow, so they backfill straight to APPROVED.
ALTER TABLE interview_reports ADD COLUMN status VARCHAR(20);
UPDATE interview_reports SET status = 'APPROVED';
ALTER TABLE interview_reports ALTER COLUMN status SET NOT NULL;

ALTER TABLE interview_reports ADD COLUMN review_comments TEXT;
ALTER TABLE interview_reports ADD COLUMN reviewed_by_id BIGINT REFERENCES users (id);
ALTER TABLE interview_reports ADD COLUMN reviewed_at TIMESTAMP;

-- Drafts are saved before every field is filled in, so these can no longer be NOT NULL.
ALTER TABLE interview_reports ALTER COLUMN interview_date DROP NOT NULL;
ALTER TABLE interview_reports ALTER COLUMN interview_location DROP NOT NULL;
ALTER TABLE interview_reports ALTER COLUMN submitted_at DROP NOT NULL;

-- A Viewer (Care Provider-side) sees reports only for the specific homes they've been granted,
-- rather than their whole organisation the way ORG_ADMIN does.
CREATE TABLE user_viewer_homes (
    user_id BIGINT NOT NULL REFERENCES users (id),
    home_id BIGINT NOT NULL REFERENCES homes (id),
    PRIMARY KEY (user_id, home_id)
);
