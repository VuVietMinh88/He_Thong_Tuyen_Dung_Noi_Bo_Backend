-- Interview appointments are independent of the application's pipeline stage.
CREATE TABLE interview_sessions (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    round_name VARCHAR(120) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    mode VARCHAR(16) NOT NULL,
    room VARCHAR(200),
    normalized_room VARCHAR(200),
    meeting_url VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    ever_submitted_at TIMESTAMPTZ,
    CONSTRAINT interview_sessions_round CHECK (round_name = btrim(round_name) AND round_name <> ''),
    CONSTRAINT interview_sessions_times CHECK (ends_at > starts_at AND ends_at <= starts_at + INTERVAL '8 hours'),
    CONSTRAINT interview_sessions_mode CHECK (
        (mode = 'ONSITE' AND room IS NOT NULL AND btrim(room) <> '' AND normalized_room IS NOT NULL
            AND btrim(normalized_room) <> '' AND meeting_url IS NULL)
        OR (mode = 'ONLINE' AND room IS NULL AND normalized_room IS NULL
            AND meeting_url IS NOT NULL AND meeting_url LIKE 'https://%')),
    CONSTRAINT interview_sessions_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    CONSTRAINT interview_sessions_version CHECK (version >= 0),
    CONSTRAINT interview_sessions_id_application UNIQUE (id, application_id)
);
CREATE INDEX interview_sessions_application_idx ON interview_sessions(application_id, starts_at, id);
CREATE INDEX interview_sessions_active_time_idx ON interview_sessions(starts_at, ends_at) WHERE status = 'SCHEDULED';
CREATE INDEX interview_sessions_room_idx ON interview_sessions(normalized_room, starts_at) WHERE status = 'SCHEDULED';

-- Keep removed assignments for form/history references; only active assignments grant access.
CREATE TABLE interview_participants (
    interview_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (interview_id, user_id)
);
CREATE INDEX interview_participants_user_idx ON interview_participants(user_id, interview_id) WHERE active;

CREATE TABLE interview_events (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    event_type VARCHAR(32) NOT NULL,
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    round_name VARCHAR(120) NOT NULL,
    reason VARCHAR(2000),
    conflict_override BOOLEAN NOT NULL DEFAULT FALSE,
    override_reason VARCHAR(2000),
    CONSTRAINT interview_events_session_application FOREIGN KEY (interview_id,application_id)
        REFERENCES interview_sessions(id,application_id) ON DELETE CASCADE,
    CONSTRAINT interview_events_type CHECK (event_type IN
        ('INTERVIEW_SCHEDULED', 'INTERVIEW_RESCHEDULED', 'INTERVIEW_CANCELLED')),
    CONSTRAINT interview_events_reason CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT interview_events_override CHECK (
        (conflict_override AND override_reason IS NOT NULL AND btrim(override_reason) <> '')
        OR (NOT conflict_override AND override_reason IS NULL)),
    CONSTRAINT interview_events_cancel CHECK (event_type <> 'INTERVIEW_CANCELLED' OR reason IS NOT NULL)
);
CREATE INDEX interview_events_application_idx ON interview_events(application_id, occurred_at, id);
CREATE FUNCTION reject_interview_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'interview_events rows are never changed' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER interview_events_immutable BEFORE UPDATE ON interview_events
    FOR EACH ROW EXECUTE FUNCTION reject_interview_event_change();
