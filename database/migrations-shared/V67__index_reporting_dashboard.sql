-- Task 558 (story S8-05, dashboard under 2 seconds): indexes for the bounded windows the dashboard reads.
-- The reports never scan a whole history: every series is limited to at most 366 days, the week card to one week,
-- the overdue list to a page of 50, so each of these lets PostgreSQL read only the rows inside the window.

-- Applications received in a window (week card, weekly chart; later the funnel and source cohorts).
-- requisition_id is included so the join to the requisition and its scope can be answered from the index.
CREATE INDEX job_applications_received_at_idx ON job_applications (received_at, requisition_id);

-- Open requisitions past their needed-by date, most overdue first (the overdue list and its count).
CREATE INDEX recruitment_requisitions_open_needed_by_idx ON recruitment_requisitions (needed_by, id)
    WHERE status IN ('APPROVED', 'PAUSED') AND needed_by IS NOT NULL;

-- Requisitions closed per month: only the FILLED/CANCELLED events, by time.
CREATE INDEX requisition_workflow_events_closed_at_idx ON requisition_workflow_events (occurred_at, requisition_id)
    WHERE event_type IN ('FILLED', 'CANCELLED');

-- Offers awaiting the candidate's answer, by deadline.
CREATE INDEX offers_sent_deadline_idx ON offers (response_deadline, application_id) WHERE status = 'SENT';
