-- A form freezes the framework at creation. Source framework/criterion ids are deliberately not foreign keys:
-- changing or replacing a framework must never change the wording, weights or results of an existing form.
CREATE TABLE evaluation_forms (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL,
    application_id UUID NOT NULL,
    evaluator_id UUID NOT NULL,
    framework_id UUID NOT NULL,
    framework_code VARCHAR(50) NOT NULL,
    framework_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    conclusion VARCHAR(16),
    total_score NUMERIC(4,2),
    first_submitted_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT evaluation_forms_panel_fk FOREIGN KEY (interview_id, evaluator_id)
        REFERENCES interview_participants(interview_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT evaluation_forms_application_fk FOREIGN KEY (interview_id, application_id)
        REFERENCES interview_sessions(id, application_id) ON DELETE CASCADE,
    CONSTRAINT evaluation_forms_owner_key UNIQUE (interview_id, evaluator_id),
    CONSTRAINT evaluation_forms_identity_key UNIQUE (id, interview_id, application_id),
    CONSTRAINT evaluation_forms_status CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT evaluation_forms_conclusion CHECK (conclusion IS NULL OR conclusion IN ('HIRE', 'CONSIDER', 'NO_HIRE')),
    CONSTRAINT evaluation_forms_score CHECK (total_score IS NULL OR total_score BETWEEN 1 AND 5),
    CONSTRAINT evaluation_forms_submission CHECK (status <> 'SUBMITTED' OR
        (conclusion IS NOT NULL AND total_score IS NOT NULL AND submitted_at IS NOT NULL AND first_submitted_at IS NOT NULL)),
    CONSTRAINT evaluation_forms_submission_order CHECK (submitted_at IS NULL OR
        (first_submitted_at IS NOT NULL AND submitted_at >= first_submitted_at))
);
CREATE INDEX evaluation_forms_application_idx ON evaluation_forms(application_id, interview_id);

CREATE TABLE evaluation_form_items (
    form_id UUID NOT NULL REFERENCES evaluation_forms(id) ON DELETE CASCADE,
    criterion_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    weight NUMERIC(5,2) NOT NULL CHECK (weight > 0 AND weight <= 100),
    sort_order INTEGER NOT NULL CHECK (sort_order > 0),
    score SMALLINT CHECK (score BETWEEN 1 AND 5),
    comment VARCHAR(2000),
    PRIMARY KEY (form_id, criterion_id),
    UNIQUE (form_id, sort_order)
);

-- Immutable submitted payloads preserve what was submitted before a permitted reopening.
-- The timeline selects only identifiers, actor and time, never this confidential payload or reopening reason.
CREATE TABLE evaluation_events (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL,
    application_id UUID NOT NULL,
    form_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN ('EVALUATION_SUBMITTED', 'EVALUATION_REOPENED')),
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(2000),
    submission_snapshot JSONB,
    CONSTRAINT evaluation_events_form_fk FOREIGN KEY (form_id, interview_id, application_id)
        REFERENCES evaluation_forms(id, interview_id, application_id) ON DELETE CASCADE,
    CONSTRAINT evaluation_events_payload CHECK (
        (event_type = 'EVALUATION_SUBMITTED' AND submission_snapshot IS NOT NULL AND reason IS NULL)
        OR (event_type = 'EVALUATION_REOPENED' AND submission_snapshot IS NULL
            AND reason IS NOT NULL AND btrim(reason) <> ''))
);
CREATE INDEX evaluation_events_application_idx ON evaluation_events(application_id, occurred_at, id);
CREATE FUNCTION reject_evaluation_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'evaluation_events rows are never changed' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER evaluation_events_immutable BEFORE UPDATE ON evaluation_events
    FOR EACH ROW EXECUTE FUNCTION reject_evaluation_event_change();
