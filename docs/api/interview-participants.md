# Scheduling participant directory

`GET /api/v1/applications/{id}/interview-participants?q=&page=0&size=50`

For selecting interviewers without exposing the administrative account directory.
Requires a current session, candidate read permission, interview read and write
permissions, and access to the specified application under their intersected scopes.
Panel membership alone does not grant this directory access.

Returns `{items:[{id,fullName}],totalElements,page,size}` with `Cache-Control: no-store`.
Only enabled, administratively unlocked accounts with EVALUATIONS_WRITE_ALL or
EVALUATIONS_WRITE_SCOPED appear. No email, phone, role or account security fields.
Creation and rescheduling revalidate eligibility under locks; appearing in this
directory is not a reservation or authorization to override conflicts.

`q` is a trimmed NFC-normalized, case-insensitive literal substring of full name,
at most 120 characters. Empty matches all eligible names. Page starts at zero;
size is 1–100. Ordering is normalized name then ID. Count and rows share a snapshot.
Malformed query/paging returns 400; missing session 401; permission/scope denial 403;
unknown application 404. Candidate-access audit records the scoped lookup.
