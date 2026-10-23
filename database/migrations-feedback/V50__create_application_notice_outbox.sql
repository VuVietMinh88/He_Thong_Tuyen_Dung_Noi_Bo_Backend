-- Task 346 (story S4-06): emails after a candidate withdraws, written in the withdrawal's transaction under the
-- requisition lock and delivered after commit with the lease/retry rules of V28. RECRUITER_WITHDRAWAL goes to the
-- requisition's PRIMARY recruiter (an account that may read candidates); CANDIDATE_WITHDRAWAL goes to the address of
-- the application itself. No payload: the worker renders every mail at send time from job_applications, the stage event
-- and the recipient's current account, so no personal data or secret is kept here, and a recruiter mail never quotes
-- text the applicant typed.
CREATE TABLE application_notice_outbox (
    id UUID PRIMARY KEY,
    kind VARCHAR(30) NOT NULL,
    -- CASCADE: the notice goes with its application (manual cleanup, deletion on request).
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    -- RESTRICT like every staff reference; NULL for CANDIDATE_WITHDRAWAL.
    recipient_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_failure VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    -- One notice of each kind per application: an application is withdrawn once (V34 unique index).
    CONSTRAINT application_notice_outbox_once UNIQUE (application_id, kind),
    CONSTRAINT application_notice_outbox_kind CHECK (kind IN ('RECRUITER_WITHDRAWAL','CANDIDATE_WITHDRAWAL')),
    CONSTRAINT application_notice_outbox_recipient CHECK ((kind = 'RECRUITER_WITHDRAWAL') = (recipient_id IS NOT NULL)),
    CONSTRAINT application_notice_outbox_status CHECK (status IN ('PENDING','PROCESSING','RETRY_WAIT','SENT','FAILED')),
    CONSTRAINT application_notice_outbox_attempts CHECK (attempts BETWEEN 0 AND 6),
    CONSTRAINT application_notice_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT application_notice_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT application_notice_outbox_failure CHECK (last_failure IS NULL OR last_failure IN
        ('SMTP_TEMPORARY','RECIPIENT_REFUSED','RETRIES_EXHAUSTED','DELIVERY_UNCERTAIN','RECIPIENT_UNAVAILABLE','OBSOLETE'))
);
CREATE INDEX application_notice_outbox_due_idx ON application_notice_outbox(next_attempt_at, id)
    WHERE status IN ('PENDING','RETRY_WAIT');
CREATE INDEX application_notice_outbox_lease_idx ON application_notice_outbox(lease_until)
    WHERE status = 'PROCESSING';
-- Serves the RESTRICT check and the batch of one recipient.
CREATE INDEX application_notice_outbox_recipient_idx ON application_notice_outbox(recipient_id, status)
    WHERE recipient_id IS NOT NULL;
