-- Pausing, closing and cancelling requisitions (story S3-07, tasks 293 and 290): "Ghi lịch sử trạng thái và lý do
-- đóng yêu cầu". Three new statuses: PAUSED (Tạm dừng; HR may resume it to APPROVED), FILLED (Đã tuyển đủ) and
-- CANCELLED (Đã hủy), both final like REJECTED. Every change is one more row of the requisition's workflow history
-- (V19), written in the same transaction as the status while the requisition row is locked: who, when, the status
-- before and after, and the reason in comment. These rows belong to no approval round, so the round, level and
-- snapshot CHECKs of V19 already require those columns to be empty, and the V20 trigger already keeps them as they
-- are. They share the revision counter of the approval events, so a change made on an outdated view is refused.
-- No existing row changes.
ALTER TABLE recruitment_requisitions DROP CONSTRAINT valid_requisition_status;
ALTER TABLE recruitment_requisitions ADD CONSTRAINT valid_requisition_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'RETURNED', 'APPROVED', 'REJECTED', 'PAUSED', 'FILLED',
                      'CANCELLED'));

ALTER TABLE requisition_workflow_events DROP CONSTRAINT valid_requisition_workflow_event_type;
ALTER TABLE requisition_workflow_events ADD CONSTRAINT valid_requisition_workflow_event_type
    CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'INFO_REQUESTED', 'PAUSED', 'RESUMED', 'FILLED',
                          'CANCELLED'));
ALTER TABLE requisition_workflow_events DROP CONSTRAINT valid_requisition_workflow_event_transition;
ALTER TABLE requisition_workflow_events ADD CONSTRAINT valid_requisition_workflow_event_transition CHECK (
    (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'RETURNED') AND status_after = 'PENDING_APPROVAL')
    OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL'
        AND status_after IN ('PENDING_APPROVAL', 'APPROVED'))
    OR (event_type = 'REJECTED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'REJECTED')
    OR (event_type = 'INFO_REQUESTED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'RETURNED')
    OR (event_type = 'PAUSED' AND status_before = 'APPROVED' AND status_after = 'PAUSED')
    OR (event_type = 'RESUMED' AND status_before = 'PAUSED' AND status_after = 'APPROVED')
    OR (event_type = 'FILLED' AND status_before IN ('APPROVED', 'PAUSED') AND status_after = 'FILLED')
    OR (event_type = 'CANCELLED' AND status_before IN ('DRAFT', 'PENDING_APPROVAL', 'RETURNED', 'APPROVED', 'PAUSED')
        AND status_after = 'CANCELLED')
);
-- A pause, a closure and a cancellation say why (story S3-07: "Ba trạng thái kết thúc có lý do riêng"); resuming may.
-- valid_requisition_workflow_event_comment (V19) still refuses a blank reason or one over 2,000 characters.
ALTER TABLE requisition_workflow_events DROP CONSTRAINT requisition_workflow_event_comment_required;
ALTER TABLE requisition_workflow_events ADD CONSTRAINT requisition_workflow_event_comment_required CHECK (
    event_type NOT IN ('REJECTED', 'INFO_REQUESTED', 'PAUSED', 'FILLED', 'CANCELLED') OR comment IS NOT NULL);
-- Filled and cancelled are final: at most one of them per requisition. Last line against a writer that skipped the
-- requisition lock, like requisition_workflow_events_one_submission_idx (V19).
CREATE UNIQUE INDEX requisition_workflow_events_one_end_idx ON requisition_workflow_events(requisition_id)
    WHERE event_type IN ('FILLED', 'CANCELLED');
