-- Hashes grant access to one candidate's current appointment. Plaintext capabilities exist only in encrypted mail.
CREATE TABLE interview_invitations (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    schedule_version BIGINT NOT NULL CHECK (schedule_version >= 0),
    token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','CONFIRMED','RESCHEDULE_REQUESTED')),
    response_note VARCHAR(2000),
    responded_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT interview_invitation_session FOREIGN KEY (interview_id,application_id)
        REFERENCES interview_sessions(id,application_id) ON DELETE CASCADE,
    CONSTRAINT interview_invitation_expiry CHECK (expires_at > created_at),
    CONSTRAINT interview_invitation_response CHECK (
        (status='PENDING' AND responded_at IS NULL AND response_note IS NULL)
        OR (status='CONFIRMED' AND responded_at IS NOT NULL)
        OR (status='RESCHEDULE_REQUESTED' AND responded_at IS NOT NULL
            AND response_note IS NOT NULL AND btrim(response_note)<>''))
);
CREATE UNIQUE INDEX interview_invitation_current_idx ON interview_invitations(interview_id) WHERE revoked_at IS NULL;
CREATE INDEX interview_invitation_history_idx ON interview_invitations(interview_id,created_at,id);

-- One recipient per row. No cleartext address, name, action token or email body is stored in this outbox.
CREATE TABLE interview_invitation_outbox (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    schedule_version BIGINT NOT NULL CHECK (schedule_version >= 0),
    invitation_id UUID REFERENCES interview_invitations(id) ON DELETE CASCADE,
    recipient_user_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INVITATION','UPDATED','CANCELLED','REMOVED')),
    dedupe_key CHAR(64) NOT NULL UNIQUE CHECK (dedupe_key ~ '^[0-9a-f]{64}$'),
    key_id VARCHAR(64),
    nonce BYTEA,
    ciphertext BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','RETRY_WAIT','SENT','FAILED','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 6),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_failure VARCHAR(32) CHECK (last_failure IN
        ('OBSOLETE','RECIPIENT_UNAVAILABLE','RECIPIENT_REFUSED','PAYLOAD_INVALID','SMTP_TEMPORARY',
         'RETRIES_EXHAUSTED','DELIVERY_UNCERTAIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT interview_outbox_session FOREIGN KEY (interview_id,application_id)
        REFERENCES interview_sessions(id,application_id) ON DELETE CASCADE,
    CONSTRAINT interview_outbox_payload CHECK (
        (status IN ('PENDING','PROCESSING','RETRY_WAIT') AND key_id IS NOT NULL
            AND nonce IS NOT NULL AND octet_length(nonce)=12
            AND ciphertext IS NOT NULL AND octet_length(ciphertext) BETWEEN 16 AND 32768)
        OR (status IN ('SENT','FAILED','CANCELLED') AND key_id IS NULL AND nonce IS NULL AND ciphertext IS NULL)),
    CONSTRAINT interview_outbox_lease CHECK (
        (status='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT interview_outbox_sent CHECK ((status='SENT')=(sent_at IS NOT NULL)),
    CONSTRAINT interview_outbox_candidate_invitation CHECK (
        recipient_user_id IS NOT NULL OR kind='CANCELLED' OR invitation_id IS NOT NULL)
);
CREATE INDEX interview_outbox_due_idx ON interview_invitation_outbox(next_attempt_at,id)
    WHERE status IN ('PENDING','RETRY_WAIT','PROCESSING');
CREATE INDEX interview_outbox_interview_idx ON interview_invitation_outbox(interview_id,status);
