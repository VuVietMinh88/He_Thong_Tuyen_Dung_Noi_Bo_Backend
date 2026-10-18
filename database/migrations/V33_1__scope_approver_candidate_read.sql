-- Task 363 (story S4-09), temporary until BA/PO answer questions 3 and 25(4) of role-permission-matrix.md. The task's
-- criterion (only the assigned recruiter or the related hiring manager may read a candidate's profile and files) is
-- applied to APPROVER, the role without a recruiting function in Sprint 4. As for RECRUITER (section 7.1 of that
-- document), the story's criterion wins over the workbook cell R: APPROVER keeps candidate reading in its own scope
-- only (CandidateAccess: assigned recruiter or department manager). HR_MANAGER and ADMIN keep CANDIDATES_READ_ALL
-- (questions 6 and 25(4)). The total stays 106 grants.
DELETE FROM role_permissions WHERE role_code = 'APPROVER' AND permission_code = 'CANDIDATES_READ_ALL';
INSERT INTO role_permissions (role_code, permission_code) VALUES ('APPROVER', 'CANDIDATES_READ_SCOPED');
