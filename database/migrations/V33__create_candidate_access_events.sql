-- Task 363 (story S4-09): every read of a candidate profile or CV ("mọi truy cập được ghi nhật ký"). A granted read
-- writes one GRANTED row per application it disclosed, in the read's transaction; a refused read of an existing record
-- (403) writes one DENIED row in its own transaction after the read ended. The scope cannot be rebuilt later
-- (department managers have no history), so the rows say exactly what was shown. Ids only, no personal data. No foreign
-- keys on purpose: the record outlives the data (S5-01 merge, deletion on request) and never blocks it. Like V20/V23 it
-- is only ever added to; TRUNCATE (table owner only; tests use it to clean up) fires no row trigger and stays possible.
CREATE TABLE candidate_access_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID NOT NULL,
    candidate_id UUID NOT NULL,
    application_id UUID,
    access_kind VARCHAR(10) NOT NULL,
    access_scope VARCHAR(10) NOT NULL,
    outcome VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT candidate_access_events_kind CHECK (access_kind IN ('PROFILE', 'CV')),
    CONSTRAINT candidate_access_events_scope CHECK (access_scope IN ('ALL', 'SCOPED')),
    CONSTRAINT candidate_access_events_outcome CHECK (outcome IN ('GRANTED', 'DENIED')),
    -- Only a refused profile read (nothing readable, so nothing shown) names no application.
    CONSTRAINT candidate_access_events_application CHECK (
        application_id IS NOT NULL OR (access_kind = 'PROFILE' AND outcome = 'DENIED')),
    -- A reader with CANDIDATES_READ_ALL is never refused.
    CONSTRAINT candidate_access_events_denied_scope CHECK (outcome = 'GRANTED' OR access_scope = 'SCOPED')
);
CREATE INDEX candidate_access_events_candidate_idx ON candidate_access_events(candidate_id, occurred_at DESC);
CREATE INDEX candidate_access_events_actor_idx ON candidate_access_events(actor_id, occurred_at DESC);
CREATE FUNCTION reject_candidate_access_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'candidate_access_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = '23514';
END $$;
CREATE TRIGGER candidate_access_events_append_only
    BEFORE UPDATE OR DELETE ON candidate_access_events
    FOR EACH ROW EXECUTE FUNCTION reject_candidate_access_event_change();
