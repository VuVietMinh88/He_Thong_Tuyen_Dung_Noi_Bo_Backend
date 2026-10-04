-- Candidate is an external actor. The existing user_roles check permits only internal roles.
CREATE TABLE roles (
    code VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    internal BOOLEAN NOT NULL
);

INSERT INTO roles (code, display_name, internal) VALUES
    ('ADMIN', 'Quản trị hệ thống', TRUE),
    ('HR_MANAGER', 'Trưởng phòng Nhân sự', TRUE),
    ('RECRUITER', 'Nhân viên tuyển dụng', TRUE),
    ('HIRING_MANAGER', 'Trưởng bộ phận', TRUE),
    ('INTERVIEWER', 'Người phỏng vấn', TRUE),
    ('APPROVER', 'Người duyệt', TRUE),
    ('CANDIDATE', 'Ứng viên', FALSE);

ALTER TABLE user_roles
    ADD CONSTRAINT user_roles_role_fk FOREIGN KEY (role) REFERENCES roles(code);

CREATE TABLE permissions (
    code VARCHAR(64) PRIMARY KEY,
    module_code VARCHAR(32) NOT NULL,
    action_code VARCHAR(16) NOT NULL CHECK (action_code IN ('READ', 'WRITE')),
    scope_code VARCHAR(16) NOT NULL CHECK (scope_code IN ('ALL', 'SCOPED')),
    UNIQUE (module_code, action_code, scope_code)
);

CREATE TABLE role_permissions (
    role_code VARCHAR(32) NOT NULL REFERENCES roles(code) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL REFERENCES permissions(code) ON DELETE CASCADE,
    PRIMARY KEY (role_code, permission_code)
);

CREATE INDEX role_permissions_permission_code_idx ON role_permissions(permission_code);

WITH modules(code) AS (VALUES
    ('ORGANIZATION'), ('REQUISITIONS'), ('JOB_POSTINGS'), ('CANDIDATES'),
    ('INTERVIEWS'), ('EVALUATIONS'), ('OFFERS'), ('NOTIFICATIONS'),
    ('REPORTS'), ('USER_ADMIN')
), variants(action_code, scope_code) AS (VALUES
    ('READ', 'ALL'), ('WRITE', 'ALL'), ('READ', 'SCOPED'), ('WRITE', 'SCOPED')
)
INSERT INTO permissions (code, module_code, action_code, scope_code)
SELECT modules.code || '_' || variants.action_code || '_' || variants.scope_code,
       modules.code, variants.action_code, variants.scope_code
FROM modules CROSS JOIN variants;

-- The workbook matrix is the initial policy. F=full, W=write in assigned scope,
-- R=read, *=own/assigned scope. Recruiter candidate access is scoped by story 14.
WITH matrix(role_code, module_code, access_level) AS (VALUES
    ('CANDIDATE','JOB_POSTINGS','R'), ('CANDIDATE','CANDIDATES','R*'),
    ('CANDIDATE','INTERVIEWS','R*'), ('CANDIDATE','OFFERS','R*'),
    ('CANDIDATE','NOTIFICATIONS','R*'),
    ('INTERVIEWER','ORGANIZATION','R'), ('INTERVIEWER','CANDIDATES','R*'),
    ('INTERVIEWER','INTERVIEWS','R*'), ('INTERVIEWER','EVALUATIONS','W*'),
    ('INTERVIEWER','NOTIFICATIONS','R*'),
    ('HIRING_MANAGER','ORGANIZATION','R'), ('HIRING_MANAGER','REQUISITIONS','W*'),
    ('HIRING_MANAGER','JOB_POSTINGS','R'), ('HIRING_MANAGER','CANDIDATES','R*'),
    ('HIRING_MANAGER','INTERVIEWS','R*'), ('HIRING_MANAGER','EVALUATIONS','R*'),
    ('HIRING_MANAGER','OFFERS','R*'), ('HIRING_MANAGER','NOTIFICATIONS','R*'),
    ('HIRING_MANAGER','REPORTS','R*'),
    ('RECRUITER','ORGANIZATION','R'), ('RECRUITER','REQUISITIONS','W'),
    ('RECRUITER','JOB_POSTINGS','W'), ('RECRUITER','CANDIDATES','W*'),
    ('RECRUITER','INTERVIEWS','F'), ('RECRUITER','EVALUATIONS','R'),
    ('RECRUITER','OFFERS','W'), ('RECRUITER','NOTIFICATIONS','F'),
    ('RECRUITER','REPORTS','R*'),
    ('APPROVER','ORGANIZATION','R'), ('APPROVER','REQUISITIONS','W*'),
    ('APPROVER','JOB_POSTINGS','R'), ('APPROVER','CANDIDATES','R'),
    ('APPROVER','EVALUATIONS','R'), ('APPROVER','OFFERS','W*'),
    ('APPROVER','REPORTS','R'),
    ('HR_MANAGER','ORGANIZATION','F'), ('HR_MANAGER','REQUISITIONS','F'),
    ('HR_MANAGER','JOB_POSTINGS','F'), ('HR_MANAGER','CANDIDATES','F'),
    ('HR_MANAGER','INTERVIEWS','F'), ('HR_MANAGER','EVALUATIONS','F'),
    ('HR_MANAGER','OFFERS','F'), ('HR_MANAGER','NOTIFICATIONS','F'),
    ('HR_MANAGER','REPORTS','F'), ('HR_MANAGER','USER_ADMIN','R'),
    ('ADMIN','ORGANIZATION','F'), ('ADMIN','REQUISITIONS','F'),
    ('ADMIN','JOB_POSTINGS','F'), ('ADMIN','CANDIDATES','F'),
    ('ADMIN','INTERVIEWS','F'), ('ADMIN','EVALUATIONS','F'),
    ('ADMIN','OFFERS','F'), ('ADMIN','NOTIFICATIONS','F'),
    ('ADMIN','REPORTS','F'), ('ADMIN','USER_ADMIN','F')
), expanded AS (
    SELECT role_code, module_code,
           CASE WHEN access_level IN ('F','R') THEN 'ALL' ELSE 'SCOPED' END AS scope_code,
           access_level
    FROM matrix
)
INSERT INTO role_permissions (role_code, permission_code)
SELECT role_code, module_code || '_READ_' || scope_code FROM expanded
UNION ALL
SELECT role_code, module_code || '_WRITE_' || scope_code
FROM expanded WHERE access_level IN ('F','W','W*');

-- Self-service authentication is available to every internal role only.
INSERT INTO permissions (code, module_code, action_code, scope_code) VALUES
    ('SELF_PROFILE_READ', 'SELF_PROFILE', 'READ', 'SCOPED'),
    ('SELF_SECURITY_WRITE', 'SELF_SECURITY', 'WRITE', 'SCOPED');

INSERT INTO role_permissions (role_code, permission_code)
SELECT code, 'SELF_PROFILE_READ' FROM roles WHERE internal
UNION ALL
SELECT code, 'SELF_SECURITY_WRITE' FROM roles WHERE internal;
