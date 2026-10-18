-- A sent offer is queued; delivery status is tracked separately, with no cleartext capability in storage.
ALTER TABLE offers ADD COLUMN sent_at TIMESTAMPTZ,
    ADD COLUMN responded_at TIMESTAMPTZ,
    ADD COLUMN response_note VARCHAR(2000);
ALTER TABLE offers ADD CONSTRAINT offer_response_state CHECK (
    (status IN ('ACCEPTED','DECLINED') AND responded_at IS NOT NULL AND sent_at IS NOT NULL)
    OR (status NOT IN ('ACCEPTED','DECLINED') AND responded_at IS NULL AND response_note IS NULL));

CREATE TABLE offer_response_tokens (
    id UUID PRIMARY KEY,
    offer_id UUID NOT NULL UNIQUE,
    application_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    recipient_hash CHAR(64) NOT NULL CHECK (recipient_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    UNIQUE (id,offer_id,application_id),
    FOREIGN KEY (offer_id,application_id) REFERENCES offers(id,application_id) ON DELETE CASCADE,
    CHECK (expires_at > created_at)
);

CREATE TABLE offer_delivery_outbox (
    id UUID PRIMARY KEY,
    offer_id UUID NOT NULL UNIQUE,
    application_id UUID NOT NULL,
    offer_version BIGINT NOT NULL CHECK (offer_version >= 0),
    token_id UUID NOT NULL,
    key_id VARCHAR(64), nonce BYTEA, ciphertext BYTEA,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','RETRY_WAIT','SENT','FAILED','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 6),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID, lease_until TIMESTAMPTZ, sent_at TIMESTAMPTZ,
    last_failure VARCHAR(32) CHECK (last_failure IN ('OBSOLETE','RECIPIENT_UNAVAILABLE','RECIPIENT_REFUSED',
        'PAYLOAD_INVALID','SMTP_TEMPORARY','RETRIES_EXHAUSTED','DELIVERY_UNCERTAIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (offer_id,application_id) REFERENCES offers(id,application_id) ON DELETE CASCADE,
    FOREIGN KEY (token_id,offer_id,application_id) REFERENCES offer_response_tokens(id,offer_id,application_id) ON DELETE CASCADE,
    CONSTRAINT offer_outbox_payload CHECK (
        (status IN ('PENDING','PROCESSING','RETRY_WAIT') AND key_id IS NOT NULL
            AND nonce IS NOT NULL AND octet_length(nonce)=12
            AND ciphertext IS NOT NULL AND octet_length(ciphertext) BETWEEN 16 AND 32768)
        OR (status IN ('SENT','FAILED','CANCELLED') AND key_id IS NULL AND nonce IS NULL AND ciphertext IS NULL)),
    CONSTRAINT offer_outbox_lease CHECK (
        (status='PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT offer_outbox_sent CHECK ((status='SENT')=(sent_at IS NOT NULL))
);
CREATE INDEX offer_outbox_due_idx ON offer_delivery_outbox(next_attempt_at,id)
    WHERE status IN ('PENDING','RETRY_WAIT','PROCESSING');

-- Candidate transitions other than withdrawal must name the exact offer on the same application.
ALTER TABLE application_stage_events ADD COLUMN offer_id UUID;
ALTER TABLE application_stage_events ADD CONSTRAINT application_stage_event_offer
    FOREIGN KEY (offer_id,application_id) REFERENCES offers(id,application_id) ON DELETE RESTRICT;
ALTER TABLE application_stage_events DROP CONSTRAINT application_stage_events_candidate_withdrawal;
ALTER TABLE application_stage_events ADD CONSTRAINT application_stage_events_candidate_withdrawal CHECK (
    (actor_type='STAFF' AND offer_id IS NULL)
    OR (actor_type='CANDIDATE' AND offer_id IS NULL AND stage_after='WITHDRAWN'
        AND stage_before IN ('NEW','SCREENING','INTERVIEW','OFFER'))
    OR (actor_type='CANDIDATE' AND offer_id IS NOT NULL AND stage_before='OFFER'
        AND stage_after IN ('HIRED','REJECTED')));
CREATE UNIQUE INDEX application_stage_events_one_offer_response_idx ON application_stage_events(offer_id)
    WHERE offer_id IS NOT NULL;

CREATE TABLE offer_request_buckets (
    bucket_key VARCHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 120)
);
CREATE INDEX offer_request_buckets_expiry_idx ON offer_request_buckets(window_started_at);
