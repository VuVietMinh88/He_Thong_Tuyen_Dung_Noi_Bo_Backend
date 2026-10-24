-- Task 419 (story S5-10, "Quá hạn thì hồ sơ được gắn cờ"): the applications the daily SLA job found over their stage's
-- threshold. One row per application, for the stage it was in when detected; entered_at is when it entered that stage
-- (its latest stage-history row into the stage, or its receipt for an application still NEW). The job deletes a row as
-- soon as the application left the stage, reached a final stage, or the threshold no longer makes it late.
CREATE TABLE application_sla_flags (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    entered_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    max_days INTEGER NOT NULL,
    flagged_at TIMESTAMPTZ NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_sla_flags_stage CHECK (stage IN ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER')),
    CONSTRAINT application_sla_flags_days CHECK (max_days BETWEEN 1 AND 365),
    CONSTRAINT application_sla_flags_due CHECK (due_at > entered_at AND checked_at >= flagged_at)
);
CREATE INDEX application_sla_flags_due_idx ON application_sla_flags(due_at, application_id);
