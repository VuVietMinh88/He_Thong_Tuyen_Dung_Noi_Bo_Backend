-- Task 544 (story S8-03, "Thông báo sinh bất đồng bộ, không làm chậm thao tác gốc"): one committed business event per
-- row, inserted in the producer's own transaction (an approval waiting, a recruiter assigned, a panel invitation, a
-- candidate's offer answer), so the event and the business change commit or roll back together. NotificationWorker
-- turns it into notifications after commit, resolving the recipients and the texts from current data; no free text,
-- token or salary is kept here. recipient_ids names the recipients the producer already knows (empty: resolved by
-- the worker). The lease, retry and failure columns follow the outboxes.
CREATE TABLE notification_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    entity_type VARCHAR(24) NOT NULL,
    entity_id UUID NOT NULL,
    recipient_ids UUID[] NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_failure VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT notification_events_type CHECK (event_type IN
        ('APPROVAL_PENDING', 'RECRUITER_ASSIGNED', 'INTERVIEW_INVITED', 'OFFER_RESPONDED', 'SLA_OVERDUE')),
    CONSTRAINT notification_events_entity CHECK (entity_type IN
        ('REQUISITION', 'JOB_POSTING', 'INTERVIEW', 'OFFER', 'APPLICATION')),
    CONSTRAINT notification_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'DONE', 'FAILED')),
    CONSTRAINT notification_events_attempts CHECK (attempts BETWEEN 0 AND 6),
    CONSTRAINT notification_events_lease CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT notification_events_done CHECK ((status = 'DONE') = (processed_at IS NOT NULL)),
    CONSTRAINT notification_events_recipients CHECK (cardinality(recipient_ids) <= 100)
);
CREATE INDEX notification_events_due_idx ON notification_events(next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT', 'PROCESSING');
