-- Recruitment requisitions (story S2-10): a department manager asks for headcount for one position.
-- Task 243 only creates the table and the DRAFT status. The API, the business rules (salary justification,
-- needed-by date not in the past, department scope) and the approval workflow come in later tasks.
CREATE TABLE recruitment_requisitions (
    id UUID PRIMARY KEY,
    position_id UUID NOT NULL REFERENCES positions(id) ON DELETE RESTRICT,
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE RESTRICT,
    headcount INTEGER NOT NULL,
    reason VARCHAR(20) NOT NULL,
    -- Proposed salary band in VND (whole dong, BIGINT like positions). A draft may leave either end empty.
    proposed_salary_min BIGINT,
    proposed_salary_max BIGINT,
    -- The API decides when it is required: only when the proposal is outside the position's standard band.
    salary_justification TEXT,
    -- A calendar date without time. The API checks that it is not in the past (business time zone).
    needed_by DATE,
    job_description TEXT,
    candidate_requirements TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT positive_requisition_headcount CHECK (headcount > 0),
    CONSTRAINT valid_requisition_reason CHECK (reason IN ('REPLACEMENT', 'NEW_HEADCOUNT')),
    CONSTRAINT non_negative_requisition_salary CHECK (
        (proposed_salary_min IS NULL OR proposed_salary_min >= 0)
        AND (proposed_salary_max IS NULL OR proposed_salary_max >= 0)
    ),
    CONSTRAINT valid_requisition_salary_range CHECK (
        proposed_salary_min IS NULL OR proposed_salary_max IS NULL OR proposed_salary_min <= proposed_salary_max
    ),
    -- A section the manager has not written yet is NULL, never an empty or whitespace-only text.
    CONSTRAINT valid_requisition_salary_justification CHECK (
        salary_justification IS NULL OR salary_justification ~ '[^[:space:]]'
    ),
    CONSTRAINT valid_requisition_job_description CHECK (
        job_description IS NULL OR job_description ~ '[^[:space:]]'
    ),
    CONSTRAINT valid_requisition_candidate_requirements CHECK (
        candidate_requirements IS NULL OR candidate_requirements ~ '[^[:space:]]'
    ),
    -- Only DRAFT exists for now. The approval workflow migration drops and re-creates this named constraint
    -- to allow its new statuses; existing rows stay DRAFT.
    CONSTRAINT valid_requisition_status CHECK (status IN ('DRAFT'))
);

-- PostgreSQL does not index foreign keys by itself. These indexes serve the department scope filter,
-- the "position or department still used by a requisition" checks, "my requisitions" and status filters.
CREATE INDEX recruitment_requisitions_department_id_idx ON recruitment_requisitions(department_id);
CREATE INDEX recruitment_requisitions_position_id_idx ON recruitment_requisitions(position_id);
CREATE INDEX recruitment_requisitions_created_by_idx ON recruitment_requisitions(created_by);
CREATE INDEX recruitment_requisitions_status_idx ON recruitment_requisitions(status);
