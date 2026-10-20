-- Task 574 (story S8-08): time to hire and cost per hire select the hires of a period by the moment each application
-- entered HIRED, and start each one's clock at its requisition's final approval. Both lookups read only these rows.
CREATE INDEX application_stage_events_hired_at_idx ON application_stage_events (occurred_at, application_id)
    WHERE stage_after = 'HIRED';
CREATE INDEX requisition_workflow_events_final_approval_idx ON requisition_workflow_events (requisition_id, occurred_at)
    WHERE event_type = 'APPROVED' AND status_after = 'APPROVED';
