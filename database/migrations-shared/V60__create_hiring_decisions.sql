-- Decisions keep the exact submitted scorecards and catalog wording used at decision time.
-- V56 scorecards may be reopened later; neither that nor catalog editing rewrites this evidence.
CREATE TABLE hiring_decisions (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE,
    interview_id UUID NOT NULL,
    stage_event_id UUID NOT NULL UNIQUE REFERENCES application_stage_events(id) ON DELETE RESTRICT,
    decision VARCHAR(8) NOT NULL CHECK (decision IN ('OFFER', 'REJECT')),
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    actor_name VARCHAR(255) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(500),
    rejection_reason_id UUID REFERENCES recruitment_catalog_items(id) ON DELETE RESTRICT,
    rejection_reason_name VARCHAR(255),
    evidence_hash CHAR(64) NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    evidence_snapshot JSONB NOT NULL CHECK (jsonb_typeof(evidence_snapshot) = 'array'
        AND jsonb_array_length(evidence_snapshot) > 0),
    CONSTRAINT hiring_decisions_interview_fk FOREIGN KEY (interview_id, application_id)
        REFERENCES interview_sessions(id, application_id) ON DELETE RESTRICT,
    CONSTRAINT hiring_decisions_reason CHECK (
        (decision = 'OFFER' AND rejection_reason_id IS NULL AND rejection_reason_name IS NULL)
        OR (decision = 'REJECT' AND rejection_reason_id IS NOT NULL AND rejection_reason_name IS NOT NULL))
);
CREATE INDEX hiring_decisions_actor_idx ON hiring_decisions(actor_id, decided_at);

CREATE FUNCTION reject_hiring_decision_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'hiring_decisions rows are never changed or deleted' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER hiring_decisions_immutable BEFORE UPDATE OR DELETE ON hiring_decisions
    FOR EACH ROW EXECUTE FUNCTION reject_hiring_decision_change();
