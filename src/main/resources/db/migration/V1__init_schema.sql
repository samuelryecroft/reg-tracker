CREATE TABLE homes (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    address         VARCHAR(500),
    local_authority VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(100) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    full_name  VARCHAR(255) NOT NULL,
    role       VARCHAR(30) NOT NULL,
    home_id    BIGINT REFERENCES homes (id),
    enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE children (
    id                    BIGSERIAL PRIMARY KEY,
    first_name            VARCHAR(255) NOT NULL,
    last_name             VARCHAR(255) NOT NULL,
    date_of_birth         DATE NOT NULL,
    home_id               BIGINT NOT NULL REFERENCES homes (id),
    local_case_reference  VARCHAR(255),
    created_at            TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE interview_requests (
    id                      BIGSERIAL PRIMARY KEY,
    child_id                BIGINT NOT NULL REFERENCES children (id),
    home_id                 BIGINT NOT NULL REFERENCES homes (id),
    requested_by_id         BIGINT NOT NULL REFERENCES users (id),
    status                  VARCHAR(30) NOT NULL,
    allocated_contractor_id BIGINT REFERENCES users (id),
    scheduled_at            TIMESTAMP,
    returned_at             TIMESTAMP,
    notes                   TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_interview_requests_home_id ON interview_requests (home_id);
CREATE INDEX idx_interview_requests_allocated_contractor_id ON interview_requests (allocated_contractor_id);
CREATE INDEX idx_interview_requests_status ON interview_requests (status);

CREATE TABLE interview_reports (
    id                          BIGSERIAL PRIMARY KEY,
    interview_request_id        BIGINT NOT NULL REFERENCES interview_requests (id),
    contractor_id               BIGINT NOT NULL REFERENCES users (id),
    interview_date               DATE NOT NULL,
    interview_location            VARCHAR(500) NOT NULL,
    persons_present             VARCHAR(1000),
    childs_account               TEXT,
    push_factors                 TEXT,
    pull_factors                 TEXT,
    wishes_and_feelings           TEXT,
    risks_identified             TEXT,
    actions_and_referrals         TEXT,
    contractor_recommendations   TEXT,
    generated_document_path      VARCHAR(500),
    submitted_at                 TIMESTAMP NOT NULL,
    created_at                   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_interview_reports_request UNIQUE (interview_request_id)
);
