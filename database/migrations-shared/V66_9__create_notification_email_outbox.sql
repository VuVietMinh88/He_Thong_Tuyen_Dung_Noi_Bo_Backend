-- Task 551 (story S8-04): the email channel of in-app notifications. NotificationWorker writes a row here, in the same
-- transaction as the in-app notification, when email delivery is enabled (NOTIFICATION_EMAIL_DELIVERY_ENABLED: while it
-- is off no copy is kept, so enabling it later never sends old notifications) and the recipient's EMAIL preference for
-- the type is on (always for a mandatory type). The row keeps no text and no personal data, only ids:
-- NotificationEmailWorker rebuilds the notification from its event and current data just before SMTP, with the same
-- planning as the in-app notification (a recipient who lost access or a state that moved on cancels it as OBSOLETE),
-- reads the EMAIL preference again (PREFERENCE_OFF) and cancels a copy older than app.notifications.email-max-age
-- (EXPIRED). The lease, fencing and retry rules are those of the other outboxes.
CREATE TABLE notification_email_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES notification_events(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    notification_type VARCHAR(32) NOT NULL,
    dedupe_key VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_failure VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT notification_email_outbox_once UNIQUE (recipient_id, dedupe_key),
    CONSTRAINT notification_email_outbox_type CHECK (notification_type IN
        ('APPROVAL_PENDING', 'RECRUITER_ASSIGNED', 'INTERVIEW_INVITED', 'OFFER_RESPONDED', 'SLA_OVERDUE')),
    CONSTRAINT notification_email_outbox_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT notification_email_outbox_attempts CHECK (attempts BETWEEN 0 AND 6),
    CONSTRAINT notification_email_outbox_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT notification_email_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT notification_email_outbox_failure CHECK (last_failure IS NULL OR last_failure IN
        ('RECIPIENT_UNAVAILABLE', 'RECIPIENT_REFUSED', 'PAYLOAD_INVALID', 'SMTP_TEMPORARY', 'RETRIES_EXHAUSTED',
         'DELIVERY_UNCERTAIN', 'OBSOLETE', 'PREFERENCE_OFF', 'EXPIRED'))
);
CREATE INDEX notification_email_outbox_due_idx ON notification_email_outbox(next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');
CREATE INDEX notification_email_outbox_event_idx ON notification_email_outbox(event_id);
