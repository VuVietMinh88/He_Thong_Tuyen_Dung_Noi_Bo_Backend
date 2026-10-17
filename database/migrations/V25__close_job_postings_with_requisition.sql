-- Closing job postings with their requisition (story S3-07, task 292): "Tự gỡ tin tuyển dụng khi đóng yêu cầu". When HR
-- closes a requisition as filled or cancels it (task 290), every posting of it that is not closed yet moves to CLOSED
-- in the same transaction, and the step is added to the posting's history with the person who closed the requisition,
-- the time and the reason. CLOSED is final and never public. A posting closed after its publication keeps who published
-- it and when (V23 lists CLOSED in neither trail CHECK). A paused requisition closes nothing: the portal already hides
-- postings whose requisition is not approved (task 309).
ALTER TABLE job_postings DROP CONSTRAINT valid_job_posting_status;
ALTER TABLE job_postings ADD CONSTRAINT valid_job_posting_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED', 'PUBLISHED', 'CLOSED'));
-- A posting closed while it was still being written may lack the portal details; one that was ever published has them.
ALTER TABLE job_postings DROP CONSTRAINT job_posting_details_required;
ALTER TABLE job_postings ADD CONSTRAINT job_posting_details_required CHECK (
    status IN ('DRAFT', 'REVISION_REQUESTED')
    OR (status = 'CLOSED' AND published_at IS NULL)
    OR (work_location_id IS NOT NULL AND employment_type_id IS NOT NULL AND application_deadline IS NOT NULL));

ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_type;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_type
    CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REVISION_REQUESTED', 'PUBLISHED', 'CLOSED'));
ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_transition;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_transition CHECK (
    (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'REVISION_REQUESTED')
        AND status_after = 'PENDING_APPROVAL')
    OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'APPROVED')
    OR (event_type = 'REVISION_REQUESTED' AND status_before IN ('PENDING_APPROVAL', 'APPROVED')
        AND status_after = 'REVISION_REQUESTED')
    OR (event_type = 'PUBLISHED' AND status_before = 'APPROVED' AND status_after = 'PUBLISHED')
    OR (event_type = 'CLOSED' AND status_before IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED',
        'PUBLISHED') AND status_after = 'CLOSED'));
-- The reason is the requisition's, which V24 requires for filled and cancelled requisitions.
ALTER TABLE job_posting_events DROP CONSTRAINT job_posting_event_comment_required;
ALTER TABLE job_posting_events ADD CONSTRAINT job_posting_event_comment_required
    CHECK (event_type NOT IN ('REVISION_REQUESTED', 'CLOSED') OR comment IS NOT NULL);
-- Closed once. PUBLISHED can now be left, but only for the final CLOSED, so one publication per posting still holds.
CREATE UNIQUE INDEX job_posting_events_one_closure_idx ON job_posting_events(job_posting_id)
    WHERE event_type = 'CLOSED';

-- Requisitions closed (V24 events) before this migration ran close their postings now, with the same person, time and
-- reason (requisition_workflow_events_one_end_idx: at most one such event per requisition). Every part of the
-- statement reads the same snapshot, so "old" still sees each posting's status before the update. V23's trigger refuses
-- only UPDATE and DELETE of events, not INSERT.
WITH old AS (
    SELECT p.id, p.status AS status_before, p.revision + 1 AS revision, e.actor_id, e.occurred_at, e.comment
    FROM job_postings p
    JOIN requisition_workflow_events e ON e.requisition_id = p.requisition_id
        AND e.event_type IN ('FILLED', 'CANCELLED')
    WHERE p.status <> 'CLOSED'
), moved AS (
    UPDATE job_postings p SET status = 'CLOSED', revision = old.revision FROM old WHERE p.id = old.id RETURNING p.id
)
INSERT INTO job_posting_events (id, job_posting_id, revision, event_type, status_before, status_after, actor_id,
                                occurred_at, comment)
SELECT gen_random_uuid(), old.id, old.revision, 'CLOSED', old.status_before, 'CLOSED', old.actor_id, old.occurred_at,
       old.comment
FROM old;
