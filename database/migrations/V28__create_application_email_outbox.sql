CREATE TABLE application_email_outbox (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES job_applications(id) ON DELETE CASCADE,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    key_id VARCHAR(32),
    nonce BYTEA,
    ciphertext BYTEA,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_failure VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT application_email_outbox_status CHECK (status IN ('PENDING','PROCESSING','RETRY_WAIT','SENT','FAILED')),
    CONSTRAINT application_email_outbox_attempts CHECK (attempts BETWEEN 0 AND 6),
    CONSTRAINT application_email_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT application_email_outbox_payload CHECK (
        (status = 'SENT' AND key_id IS NULL AND nonce IS NULL AND ciphertext IS NULL AND sent_at IS NOT NULL)
        OR (status <> 'SENT' AND key_id IS NOT NULL AND nonce IS NOT NULL AND ciphertext IS NOT NULL
            AND octet_length(nonce) = 12 AND octet_length(ciphertext) BETWEEN 16 AND 16384 AND sent_at IS NULL)),
    CONSTRAINT application_email_outbox_failure CHECK (last_failure IS NULL OR last_failure IN
        ('SMTP_TEMPORARY','RECIPIENT_REFUSED','RETRIES_EXHAUSTED','DELIVERY_UNCERTAIN','PAYLOAD_INVALID'))
);
CREATE INDEX application_email_outbox_due_idx ON application_email_outbox(next_attempt_at,id)
    WHERE status IN ('PENDING','RETRY_WAIT');
CREATE INDEX application_email_outbox_lease_idx ON application_email_outbox(lease_until)
    WHERE status = 'PROCESSING';
