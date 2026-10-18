-- Requisition approval workflow (stories S3-01, S3-02, S3-03; tasks 257, 261-264, 267): the new requisition statuses
-- and the history of the workflow. One row per event (submitted, a level approved, rejected, more information
-- requested), written in the same transaction as the status change while the requisition row is locked, so the
-- revision order is the order things happened. Rows are only ever added.
-- Every existing requisition is DRAFT, the only status V13 allowed.
ALTER TABLE recruitment_requisitions DROP CONSTRAINT valid_requisition_status;
ALTER TABLE recruitment_requisitions ADD CONSTRAINT valid_requisition_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'RETURNED', 'APPROVED', 'REJECTED'));

CREATE TABLE requisition_workflow_events (
    id UUID PRIMARY KEY,
    -- RESTRICT: the history is kept as long as the requisition (no API deletes requisitions), like V15 and V17.
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    -- 1, 2, 3... per requisition across every round. Orders the history even when two events share a timestamp;
    -- clients send the last one back with a decision, so a decision taken on an outdated view is refused.
    revision INTEGER NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    status_before VARCHAR(20) NOT NULL,
    status_after VARCHAR(20) NOT NULL,
    -- Approval round: 1 for the first submission, one more for every submission after more information was asked.
    round_number INTEGER,
    -- The level decided (the position of the step in the flow version). Decisions only.
    level_number SMALLINT,
    -- The snapshot of the round, on SUBMITTED only: the sealed flow version and how many of its levels apply
    -- (positions 1 to level_count). Sealed versions and their steps never change (V18), so this is the chain.
    flow_id UUID,
    flow_version INTEGER,
    level_count SMALLINT,
    -- proposed_salary_max when submitted (whole VND per person per month): the amount compared with the thresholds.
    salary_basis BIGINT,
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- Optional when submitting or approving, required when rejecting or asking for more information. The API limits
    -- it to 2,000 characters and stores a blank comment as NULL.
    comment TEXT,
    CONSTRAINT requisition_workflow_events_revision_key UNIQUE (requisition_id, revision),
    CONSTRAINT requisition_workflow_events_flow_version_fkey FOREIGN KEY (flow_id, flow_version)
        REFERENCES approval_flow_versions(flow_id, version) ON DELETE RESTRICT,
    -- The last level of the round exists in that version. V18 keeps positions contiguous from 1, so every level from
    -- 1 to level_count exists.
    CONSTRAINT requisition_workflow_events_last_level_fkey FOREIGN KEY (flow_id, flow_version, level_count)
        REFERENCES approval_flow_steps(flow_id, version, position) ON DELETE RESTRICT,
    CONSTRAINT positive_requisition_workflow_event_revision CHECK (revision > 0),
    CONSTRAINT valid_requisition_workflow_event_type
        CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'INFO_REQUESTED')),
    CONSTRAINT valid_requisition_workflow_event_transition CHECK (
        (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'RETURNED') AND status_after = 'PENDING_APPROVAL')
        OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL'
            AND status_after IN ('PENDING_APPROVAL', 'APPROVED'))
        OR (event_type = 'REJECTED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'REJECTED')
        OR (event_type = 'INFO_REQUESTED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'RETURNED')
    ),
    -- event_type is NOT NULL, so each "=" between two booleans is never NULL (as in V17).
    CONSTRAINT valid_requisition_workflow_event_round CHECK (
        (event_type IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'INFO_REQUESTED')) = (round_number IS NOT NULL)
        AND (round_number IS NULL OR round_number > 0)
    ),
    CONSTRAINT valid_requisition_workflow_event_level CHECK (
        (event_type IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED')) = (level_number IS NOT NULL)
        AND (level_number IS NULL OR level_number BETWEEN 1 AND 20)
    ),
    CONSTRAINT valid_requisition_workflow_event_snapshot CHECK (
        (event_type = 'SUBMITTED') = (flow_id IS NOT NULL)
        AND (flow_id IS NULL) = (flow_version IS NULL)
        AND (flow_id IS NULL) = (level_count IS NULL)
        AND (flow_id IS NULL) = (salary_basis IS NULL)
        AND (level_count IS NULL OR level_count BETWEEN 1 AND 20)
        AND (salary_basis IS NULL OR salary_basis >= 0)
    ),
    -- No comment is NULL, never an empty or whitespace-only text.
    CONSTRAINT valid_requisition_workflow_event_comment CHECK (
        comment IS NULL OR (comment ~ '[^[:space:]]' AND char_length(comment) <= 2000)
    ),
    CONSTRAINT requisition_workflow_event_comment_required CHECK (
        event_type NOT IN ('REJECTED', 'INFO_REQUESTED') OR comment IS NOT NULL
    )
);

-- Last line against a decision taken twice: one submission per round, one decision per level of a round. A writer
-- that skipped the requisition lock fails here (or on the revision key) instead of storing a second one.
CREATE UNIQUE INDEX requisition_workflow_events_one_submission_idx
    ON requisition_workflow_events(requisition_id, round_number) WHERE event_type = 'SUBMITTED';
CREATE UNIQUE INDEX requisition_workflow_events_one_decision_idx
    ON requisition_workflow_events(requisition_id, round_number, level_number) WHERE level_number IS NOT NULL;
-- PostgreSQL does not index foreign keys by itself. The revision key serves "the history of one requisition"; these
-- serve the RESTRICT checks on user_accounts and on the approval-flow tables (both flow keys share the prefix).
CREATE INDEX requisition_workflow_events_actor_id_idx ON requisition_workflow_events(actor_id);
CREATE INDEX requisition_workflow_events_flow_level_idx
    ON requisition_workflow_events(flow_id, flow_version, level_count);
