CREATE TABLE offers (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE RESTRICT,
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    position_id UUID NOT NULL REFERENCES positions(id) ON DELETE RESTRICT,
    candidate_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    position_title VARCHAR(255) NOT NULL,
    salary BIGINT NOT NULL CHECK (salary BETWEEN 0 AND 1000000000000),
    allowance BIGINT NOT NULL CHECK (allowance BETWEEN 0 AND 1000000000000),
    start_date DATE NOT NULL,
    response_deadline TIMESTAMPTZ NOT NULL,
    explanation VARCHAR(2000),
    band_min BIGINT NOT NULL CHECK (band_min >= 0),
    band_max BIGINT NOT NULL CHECK (band_max >= band_min),
    outside_band BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN
        ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT','ACCEPTED','DECLINED','REJECTED','EXPIRED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    approval_round INTEGER NOT NULL DEFAULT 0 CHECK (approval_round >= 0),
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    submitted_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    hr_approver_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    higher_approver_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT offers_identity_application UNIQUE(id,application_id),
    CONSTRAINT offers_outside_explanation CHECK (NOT outside_band OR (explanation IS NOT NULL AND btrim(explanation) <> '')),
    CONSTRAINT offers_different_approvers CHECK (higher_approver_id IS NULL OR higher_approver_id <> hr_approver_id),
    CONSTRAINT offers_no_self_approval CHECK (
        (hr_approver_id IS NULL OR (hr_approver_id <> created_by AND hr_approver_id <> submitted_by))
        AND (higher_approver_id IS NULL OR (higher_approver_id <> created_by AND higher_approver_id <> submitted_by)))
);
CREATE UNIQUE INDEX offers_one_active_candidate ON offers(candidate_id)
    WHERE status IN ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT','ACCEPTED');
CREATE INDEX offers_application_idx ON offers(application_id,created_at,id);
CREATE INDEX offers_expiration_idx ON offers(response_deadline,id)
    WHERE status IN ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT');

CREATE FUNCTION protect_submitted_offer_terms() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.application_id,NEW.candidate_id,NEW.position_id,NEW.created_by,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.application_id,OLD.candidate_id,OLD.position_id,OLD.created_by,OLD.created_at)
    THEN
        RAISE EXCEPTION 'offer identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status NOT IN ('DRAFT','CHANGES_REQUESTED') AND
        ROW(NEW.application_id,NEW.candidate_id,NEW.position_id,NEW.candidate_name,NEW.email,NEW.position_title,
            NEW.salary,NEW.allowance,NEW.start_date,NEW.response_deadline,NEW.explanation,NEW.band_min,NEW.band_max,
            NEW.outside_band,NEW.approval_round,NEW.submitted_by,NEW.hr_approver_id,NEW.higher_approver_id)
        IS DISTINCT FROM
        ROW(OLD.application_id,OLD.candidate_id,OLD.position_id,OLD.candidate_name,OLD.email,OLD.position_title,
            OLD.salary,OLD.allowance,OLD.start_date,OLD.response_deadline,OLD.explanation,OLD.band_min,OLD.band_max,
            OLD.outside_band,OLD.approval_round,OLD.submitted_by,OLD.hr_approver_id,OLD.higher_approver_id)
    THEN
        RAISE EXCEPTION 'submitted offer terms are frozen' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER offers_frozen_terms BEFORE UPDATE ON offers
    FOR EACH ROW EXECUTE FUNCTION protect_submitted_offer_terms();

CREATE TABLE offer_events (
    id UUID PRIMARY KEY,
    offer_id UUID NOT NULL REFERENCES offers(id) ON DELETE RESTRICT,
    approval_round INTEGER NOT NULL CHECK (approval_round >= 0),
    event_type VARCHAR(40) NOT NULL,
    actor_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    actor_name VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL,
    comment VARCHAR(2000),
    terms_snapshot JSONB
);
CREATE INDEX offer_events_offer_idx ON offer_events(offer_id,occurred_at,id);
CREATE FUNCTION reject_offer_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'offer_events rows are never changed or deleted' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER offer_events_immutable BEFORE UPDATE OR DELETE ON offer_events
    FOR EACH ROW EXECUTE FUNCTION reject_offer_event_change();
