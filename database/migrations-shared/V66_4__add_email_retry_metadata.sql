-- Task 539 (story S8-02, "Gửi lại được thư thất bại"): who retried a failed email last, when, and how many times. The
-- earlier attempts stay in email_attempts (V66_2); a retry only queues the email again.
ALTER TABLE email_messages
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_retried_by UUID REFERENCES user_accounts(id) ON DELETE SET NULL,
    ADD COLUMN last_retried_at TIMESTAMPTZ,
    ADD CONSTRAINT email_messages_retry CHECK (retry_count >= 0 AND (retry_count = 0) = (last_retried_at IS NULL));
CREATE INDEX email_messages_status_idx ON email_messages(status, created_at DESC);
