-- Task 538 (story S8-02, "Email được gửi bất đồng bộ qua hàng đợi, không làm chậm thao tác gốc"): the durable queue of
-- the candidate emails that are only a template (REJECTION, INTERVIEW_THANKS of email_messages). A row is written in
-- the deciding transaction and delivered after commit by CandidateMailWorker with the lease, fencing and retry rules
-- of the other outboxes (V28, V58, V62). There is no payload: the worker renders the email at send time from the
-- message, the application, the interview and the current template, so nothing personal is copied here, and a
-- failed row can be retried later from current data (task 539) without keeping or re-issuing any secret.
CREATE TABLE candidate_mail_outbox (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE REFERENCES email_messages(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_failure VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT candidate_mail_outbox_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT candidate_mail_outbox_attempts CHECK (attempts BETWEEN 0 AND 6),
    CONSTRAINT candidate_mail_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT candidate_mail_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT candidate_mail_outbox_failure CHECK (last_failure IS NULL OR last_failure IN
        ('OBSOLETE', 'RECIPIENT_UNAVAILABLE', 'RECIPIENT_REFUSED', 'PAYLOAD_INVALID', 'SMTP_TEMPORARY',
         'RETRIES_EXHAUSTED', 'DELIVERY_UNCERTAIN'))
);
CREATE INDEX candidate_mail_outbox_due_idx ON candidate_mail_outbox(next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');

CREATE TRIGGER candidate_mail_outbox_message_status AFTER UPDATE OF status ON candidate_mail_outbox
    FOR EACH ROW EXECUTE FUNCTION sync_email_message_status('CANDIDATE_MAIL');

-- Emails decided before this queue existed (V66_1 recorded them QUEUED without a delivery row) are queued now.
INSERT INTO candidate_mail_outbox(id, message_id)
SELECT gen_random_uuid(), m.id FROM email_messages m
WHERE m.delivery = 'CANDIDATE_MAIL' AND m.status = 'QUEUED' AND m.delivery_id IS NULL;
UPDATE email_messages m SET delivery_id = o.id, updated_at = clock_timestamp()
FROM candidate_mail_outbox o WHERE o.message_id = m.id AND m.delivery_id IS NULL;

-- From now on every queued email names its delivery row.
ALTER TABLE email_messages DROP CONSTRAINT email_messages_delivery_row;
ALTER TABLE email_messages ADD CONSTRAINT email_messages_delivery_row CHECK (delivery IS NULL OR delivery_id IS NOT NULL);
