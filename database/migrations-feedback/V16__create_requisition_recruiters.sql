-- Recruiters of a requisition (story S3-06, task 283): "Phân công một recruiter chính và nhiều recruiter hỗ trợ".
-- The requisition is assigned, not the catalog position: a position is one job title shared by every department.
-- One row per recruiter currently assigned; a requisition without rows is not assigned yet (every existing draft,
-- every new draft and every copy of task 279). A handover deletes the old primary's row; who held it before is kept
-- in the change history of task 286, not here.
CREATE TABLE requisition_recruiters (
    -- RESTRICT like V15: no API deletes a requisition, and a team must never disappear silently.
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    -- RESTRICT: no API deletes accounts. A recruiter who is later locked or loses the RECRUITER role stays assigned
    -- until HR hands over; the API checks the role and the account only when someone is given a role.
    recruiter_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    assignment_role VARCHAR(20) NOT NULL,
    -- Who gave this recruiter this role and when (for the primary: the last handover).
    assigned_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    assigned_at TIMESTAMPTZ NOT NULL,
    -- A recruiter appears once per requisition: never twice, never primary and supporting at the same time.
    CONSTRAINT requisition_recruiters_pkey PRIMARY KEY (requisition_id, recruiter_id),
    CONSTRAINT valid_requisition_recruiter_role CHECK (assignment_role IN ('PRIMARY', 'SUPPORTING'))
);

-- At most one primary per requisition, even for two saves at the same moment. "Supporting only with a primary" and
-- "at most 10 supporting" need the other rows, so the API checks them while it holds the requisition row lock.
CREATE UNIQUE INDEX requisition_recruiters_one_primary_idx ON requisition_recruiters(requisition_id)
    WHERE assignment_role = 'PRIMARY';
-- PostgreSQL does not index foreign keys by itself; the primary key already starts with requisition_id.
-- recruiter_id serves "requisitions assigned to me" (scope follow-up, task 285) and the RESTRICT check;
-- assigned_by serves the RESTRICT check.
CREATE INDEX requisition_recruiters_recruiter_id_idx ON requisition_recruiters(recruiter_id);
CREATE INDEX requisition_recruiters_assigned_by_idx ON requisition_recruiters(assigned_by);
