-- HR exceptions to the headcount plan (story S3-04, task 275): "Vượt chỉ tiêu ... cần Trưởng phòng Nhân sự xác nhận
-- ghi đè kèm lý do". One row each time an HR manager saves a requisition over the plan of its department and year:
-- who confirmed it, when, why, and the numbers it was confirmed against. Rows are only ever added.
CREATE TABLE requisition_headcount_overrides (
    id UUID PRIMARY KEY,
    -- RESTRICT: the history of a requisition is kept as long as the requisition (no API deletes either).
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    -- The plan the save went over. The requisition may move to another department or year later; this row keeps the
    -- plan it was confirmed for. RESTRICT: a department with exception history is not deleted (task 197 answers 409).
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE RESTRICT,
    plan_year INTEGER NOT NULL,
    -- The plan at that moment (it may change later).
    headcount_limit INTEGER NOT NULL,
    salary_budget BIGINT,
    -- What the other requisitions used at that moment, and what this save asked for (people, yearly salary in VND).
    headcount_used BIGINT NOT NULL,
    salary_cost_used BIGINT NOT NULL,
    requested_headcount INTEGER NOT NULL,
    requested_salary_cost BIGINT NOT NULL,
    headcount_exceeded BOOLEAN NOT NULL,
    salary_budget_exceeded BOOLEAN NOT NULL,
    reason TEXT NOT NULL,
    overridden_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    overridden_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_headcount_override_year CHECK (plan_year BETWEEN 2000 AND 2100),
    CONSTRAINT valid_headcount_override_numbers CHECK (
        headcount_limit >= 0 AND (salary_budget IS NULL OR salary_budget >= 0)
        AND headcount_used >= 0 AND salary_cost_used >= 0
        AND requested_headcount > 0 AND requested_salary_cost >= 0
    ),
    -- An exception is only recorded when the save really went over at least one limit.
    CONSTRAINT valid_headcount_override_excess CHECK (headcount_exceeded OR salary_budget_exceeded),
    -- The reason is required: never empty or whitespace only.
    CONSTRAINT valid_headcount_override_reason CHECK (reason ~ '[^[:space:]]')
);

-- PostgreSQL does not index foreign keys by itself. requisition_id serves the history of one requisition;
-- the other two serve the RESTRICT checks when a department or an account is deleted.
CREATE INDEX requisition_headcount_overrides_requisition_id_idx ON requisition_headcount_overrides(requisition_id);
CREATE INDEX requisition_headcount_overrides_department_id_idx ON requisition_headcount_overrides(department_id);
CREATE INDEX requisition_headcount_overrides_overridden_by_idx ON requisition_headcount_overrides(overridden_by);
