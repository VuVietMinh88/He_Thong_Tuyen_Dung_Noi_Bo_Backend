-- Task 549 (story S8-04): each user's choice per notification type and channel (in-app, email). Only explicit choices
-- are stored; a missing row means the channel is on. APPROVAL_PENDING is mandatory in both channels: the API refuses to
-- store false for it, delivery ignores any stored choice for it, and this CHECK is the last line of defence.
CREATE TABLE notification_preferences (
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    notification_type VARCHAR(32) NOT NULL,
    channel VARCHAR(8) NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, notification_type, channel),
    CONSTRAINT notification_preferences_type CHECK (notification_type IN
        ('APPROVAL_PENDING', 'RECRUITER_ASSIGNED', 'INTERVIEW_INVITED', 'OFFER_RESPONDED', 'SLA_OVERDUE')),
    CONSTRAINT notification_preferences_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT notification_preferences_mandatory CHECK (notification_type <> 'APPROVAL_PENDING' OR enabled)
);
