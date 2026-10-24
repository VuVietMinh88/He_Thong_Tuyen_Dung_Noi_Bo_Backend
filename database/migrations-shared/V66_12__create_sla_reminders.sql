-- Task 420 (story S5-10, "không nhắc trùng trong cùng ngày"): the reminders already given, one row per flagged
-- application, stage, business day and recipient. The daily job inserts the row before it publishes the reminder, and
-- the primary key turns a rerun or a concurrent run of the same day into nothing.
CREATE TABLE sla_reminders (
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    reminder_date DATE NOT NULL,
    recipient_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (application_id, stage, reminder_date, recipient_id),
    CONSTRAINT sla_reminders_stage CHECK (stage IN ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER'))
);
CREATE INDEX sla_reminders_recipient_idx ON sla_reminders(recipient_id, reminder_date);
