-- Task 345 (story S4-06): every stage change of an application after it was received, written together with
-- job_applications.stage while the requisition and application rows are locked. Sprint 4 writes only the candidate's
-- own withdrawal (CANDIDATE, no account); Sprint 5 stage changes (S5-02) add STAFF rows and the timeline (S5-07) reads
-- them. job_applications keeps its V26 columns.
CREATE TABLE application_stage_events (
    id UUID PRIMARY KEY,
    -- CASCADE: the history goes with its application (manual cleanup, deletion on request).
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    stage_before VARCHAR(20) NOT NULL,
    stage_after VARCHAR(20) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    -- RESTRICT like every staff reference: no API deletes accounts.
    actor_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_stage_events_valid_stages CHECK (
        stage_before IN ('NEW','SCREENING','INTERVIEW','OFFER','HIRED','REJECTED','WITHDRAWN')
        AND stage_after IN ('NEW','SCREENING','INTERVIEW','OFFER','HIRED','REJECTED','WITHDRAWN')
        AND stage_before <> stage_after),
    CONSTRAINT application_stage_events_valid_actor CHECK (
        (actor_type = 'CANDIDATE' AND actor_id IS NULL) OR (actor_type = 'STAFF' AND actor_id IS NOT NULL)),
    CONSTRAINT application_stage_events_candidate_withdrawal CHECK (actor_type <> 'CANDIDATE'
        OR (stage_after = 'WITHDRAWN' AND stage_before IN ('NEW','SCREENING','INTERVIEW','OFFER'))),
    CONSTRAINT application_stage_events_visible_reason CHECK (reason IS NULL OR reason ~ '[^[:space:]]')
);
CREATE INDEX application_stage_events_application_idx ON application_stage_events(application_id, occurred_at);
CREATE INDEX application_stage_events_actor_idx ON application_stage_events(actor_id) WHERE actor_id IS NOT NULL;
-- WITHDRAWN is final: one withdrawal per application, the last line of defence behind the row lock.
CREATE UNIQUE INDEX application_stage_events_one_withdrawal_idx ON application_stage_events(application_id)
    WHERE stage_after = 'WITHDRAWN';
-- A saved event is never changed. DELETE stays possible for the CASCADE from job_applications.
CREATE FUNCTION reject_application_stage_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'application_stage_events rows are never changed' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER application_stage_events_immutable
    BEFORE UPDATE ON application_stage_events
    FOR EACH ROW EXECUTE FUNCTION reject_application_stage_event_change();
