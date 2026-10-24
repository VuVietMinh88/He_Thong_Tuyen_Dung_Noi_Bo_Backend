-- Task 543 (story S8-03): in-app notifications, one row per recipient. notification_type is what happened, link_type
-- and link_id the screen the bell opens (a server-built entity reference, never a free URL; parent_id carries the
-- application of an interview or an offer so the frontend can build the route). read_at belongs to that recipient
-- only. dedupe_key makes one event notify one recipient once, even when it is processed again.
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    notification_type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    link_type VARCHAR(24) NOT NULL,
    link_id UUID NOT NULL,
    parent_id UUID,
    dedupe_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT notifications_type CHECK (notification_type IN
        ('APPROVAL_PENDING', 'RECRUITER_ASSIGNED', 'INTERVIEW_INVITED', 'OFFER_RESPONDED', 'SLA_OVERDUE')),
    CONSTRAINT notifications_link CHECK (link_type IN ('REQUISITION', 'JOB_POSTING', 'INTERVIEW', 'OFFER', 'APPLICATION')),
    CONSTRAINT notifications_parent CHECK ((link_type IN ('INTERVIEW', 'OFFER')) = (parent_id IS NOT NULL)),
    CONSTRAINT notifications_text CHECK (btrim(title) <> '' AND btrim(body) <> ''),
    CONSTRAINT notifications_read CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT notifications_once UNIQUE (recipient_id, dedupe_key)
);
CREATE INDEX notifications_inbox_idx ON notifications(recipient_id, created_at DESC, id DESC);
CREATE INDEX notifications_unread_idx ON notifications(recipient_id) WHERE read_at IS NULL;
