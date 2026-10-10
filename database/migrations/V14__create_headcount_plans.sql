-- Headcount and salary budget plans (story S3-04, task 272): for each department and year, HR declares how many
-- people the department may request and, optionally, the yearly salary budget for them.
-- What is already used is not stored here. It is summed from recruitment_requisitions whenever it is needed
-- (HeadcountPlanRepository.usage), so it can never drift from the requisitions themselves.
CREATE TABLE headcount_plans (
    id UUID PRIMARY KEY,
    -- A plan is configuration of its department. Deleting a department nobody uses (task 197) deletes its plans too.
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    plan_year INTEGER NOT NULL,
    -- How many people the department may request in plan_year. 0 is allowed: no new headcount that year.
    headcount_limit INTEGER NOT NULL,
    -- Yearly salary budget in VND (whole dong, BIGINT like positions). NULL means the year has no budget limit.
    salary_budget BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- The account that created the plan or changed it last. Accounts are never deleted through the API.
    updated_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    -- One plan per department and year. The index of this constraint also serves the lookup by department.
    CONSTRAINT headcount_plans_department_year_key UNIQUE (department_id, plan_year),
    CONSTRAINT valid_headcount_plan_year CHECK (plan_year BETWEEN 2000 AND 2100),
    CONSTRAINT non_negative_headcount_limit CHECK (headcount_limit >= 0),
    CONSTRAINT non_negative_salary_budget CHECK (salary_budget IS NULL OR salary_budget >= 0)
);

-- PostgreSQL does not index foreign keys by itself.
CREATE INDEX headcount_plans_updated_by_idx ON headcount_plans(updated_by);

-- Plans hold salary money, so they get their own permission module instead of ORGANIZATION, which every internal
-- role reads. The four codes follow the V3 pattern <MODULE>_<READ|WRITE>_<ALL|SCOPED>, like SALARY_RANGES in V7_1.
WITH variants(action_code, scope_code) AS (VALUES
    ('READ', 'ALL'), ('WRITE', 'ALL'), ('READ', 'SCOPED'), ('WRITE', 'SCOPED')
)
INSERT INTO permissions (code, module_code, action_code, scope_code)
SELECT 'HEADCOUNT_PLANS_' || action_code || '_' || scope_code, 'HEADCOUNT_PLANS', action_code, scope_code
FROM variants;

-- Story S3-04: the HR manager declares the plans and is the only one who may confirm going over them.
-- ADMIN is not granted, the same choice as for salary bands (question 4 in docs/architecture/role-permission-matrix.md).
INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('HR_MANAGER', 'HEADCOUNT_PLANS_READ_ALL'),
    ('HR_MANAGER', 'HEADCOUNT_PLANS_WRITE_ALL');
