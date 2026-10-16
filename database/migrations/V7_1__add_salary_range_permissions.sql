-- Salary bands get their own permission module, because ORGANIZATION_READ_ALL is granted to every internal
-- role and must not reveal salaries. The four codes follow the V3 pattern <MODULE>_<READ|WRITE>_<ALL|SCOPED>.
WITH variants(action_code, scope_code) AS (VALUES
    ('READ', 'ALL'), ('WRITE', 'ALL'), ('READ', 'SCOPED'), ('WRITE', 'SCOPED')
)
INSERT INTO permissions (code, module_code, action_code, scope_code)
SELECT 'SALARY_RANGES_' || action_code || '_' || scope_code, 'SALARY_RANGES', action_code, scope_code
FROM variants;

-- Jira TKNHTTDNB1-205: only the HR manager sees and maintains salary bands. ADMIN is deliberately not granted
-- until BA/PO answer question 4 in docs/architecture/role-permission-matrix.md.
INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('HR_MANAGER', 'SALARY_RANGES_READ_ALL'),
    ('HR_MANAGER', 'SALARY_RANGES_WRITE_ALL');
