CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id),
    role    VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_roles (user_id, role)
SELECT id, CASE WHEN role = 'CONTRACTOR' THEN 'VISITOR' ELSE role END
FROM users;

ALTER TABLE users DROP COLUMN role;

ALTER TABLE interview_requests RENAME COLUMN allocated_contractor_id TO allocated_visitor_id;
ALTER TABLE interview_reports RENAME COLUMN contractor_id TO visitor_id;

ALTER INDEX idx_interview_requests_allocated_contractor_id RENAME TO idx_interview_requests_allocated_visitor_id;
