-- Audit trail, phase 1 (see AUDIT-PLAN.md §B.2). One row per audited action: who did what,
-- to which entity, in which organisation/home, and when.
--
-- Deliberately NOT a second copy of the report data (AUDIT-PLAN.md §B.5 - hard GDPR rule):
-- `metadata` carries status transitions and reference ids only, never interview/report content.
CREATE TABLE audit_events (
    id                     BIGSERIAL PRIMARY KEY,
    event_type             VARCHAR(60) NOT NULL,
    occurred_at            TIMESTAMP NOT NULL,

    -- Actor FK is nullable so a user can later be deleted/anonymised under GDPR without
    -- breaking the audit row, and so failed logins (no such user) can still be recorded.
    -- The denormalised username/roles snapshot keeps the row readable if the user row goes.
    actor_id               BIGINT REFERENCES users (id),
    actor_username_at_time VARCHAR(100),
    actor_roles_at_time    VARCHAR(200),

    target_type            VARCHAR(40),
    target_id              BIGINT,

    -- Stamped the same way OrganisationAccessService resolves scope, so a future /admin/audit
    -- screen can reuse its rules unchanged (AUDIT-PLAN.md §B.3).
    organisation_id        BIGINT REFERENCES organisations (id),
    home_id                BIGINT REFERENCES homes (id),

    metadata               VARCHAR(1000)
);

CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_events_actor_id ON audit_events (actor_id);
CREATE INDEX idx_audit_events_event_type ON audit_events (event_type);
CREATE INDEX idx_audit_events_organisation_id ON audit_events (organisation_id);
CREATE INDEX idx_audit_events_target ON audit_events (target_type, target_id);

-- Immutability (AUDIT-PLAN.md §B.4): the table is INSERT/SELECT-only. Enforced in the DB rather
-- than relying solely on the repository exposing no update method, so a bug (or a direct psql
-- session) cannot quietly rewrite history. Raising an exception rather than a DO INSTEAD NOTHING
-- rule so an attempted tamper fails loudly instead of silently succeeding.
CREATE OR REPLACE FUNCTION audit_events_reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update_or_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_mutation();
