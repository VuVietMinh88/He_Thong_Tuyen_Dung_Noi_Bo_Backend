-- Recruiter assignment history (story S3-06, task 286): "Ghi phân công trước/sau, người thao tác và thời điểm để truy
-- vết bàn giao". One row per change of requisition_recruiters (V16), written in the same transaction while the
-- requisition row is locked, so replaying the rows by revision always ends in the current assignment.
-- Rows are only ever added; no API updates or deletes them.
CREATE TABLE requisition_recruiter_changes (
    id UUID PRIMARY KEY,
    -- RESTRICT: the history is kept as long as the requisition (no API deletes either), like V15.
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    -- 1, 2, 3... per requisition in the order the changes happened (they queue on the requisition row). Orders the
    -- history even when two changes share a timestamp; a writer that skipped the requisition lock fails on the key.
    revision INTEGER NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    -- After the change: the new primary (PRIMARY_*), or the supporting recruiter added or removed (SUPPORTING_*).
    recruiter_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    -- Before the change: the primary that was replaced. Only a handover has one.
    previous_recruiter_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    changed_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    changed_at TIMESTAMPTZ NOT NULL,
    -- Optional reason typed by HR; the API limits it to 1,000 characters and stores blank as NULL.
    note TEXT,
    CONSTRAINT requisition_recruiter_changes_revision_key UNIQUE (requisition_id, revision),
    CONSTRAINT positive_requisition_recruiter_change_revision CHECK (revision > 0),
    CONSTRAINT valid_requisition_recruiter_change_type CHECK (change_type IN
        ('PRIMARY_ASSIGNED', 'PRIMARY_HANDED_OVER', 'SUPPORTING_ADDED', 'SUPPORTING_REMOVED')),
    -- A handover always names the primary it replaced, never itself; no other change names one.
    -- change_type is NOT NULL, so the "=" between the two booleans is never NULL.
    CONSTRAINT valid_requisition_recruiter_change_previous CHECK (
        (change_type = 'PRIMARY_HANDED_OVER') = (previous_recruiter_id IS NOT NULL)
        AND (previous_recruiter_id IS NULL OR previous_recruiter_id <> recruiter_id)
    ),
    -- No note is NULL, never an empty or whitespace-only text.
    CONSTRAINT valid_requisition_recruiter_change_note CHECK (note IS NULL OR note ~ '[^[:space:]]')
);

-- PostgreSQL does not index foreign keys by itself. The revision key serves "the history of one requisition, newest
-- first"; these serve the RESTRICT checks on user_accounts (and "what was I handed", later).
CREATE INDEX requisition_recruiter_changes_recruiter_id_idx ON requisition_recruiter_changes(recruiter_id);
CREATE INDEX requisition_recruiter_changes_previous_recruiter_id_idx
    ON requisition_recruiter_changes(previous_recruiter_id);
CREATE INDEX requisition_recruiter_changes_changed_by_idx ON requisition_recruiter_changes(changed_by);

-- Task 284 may be released before this migration. Assignments saved in between get one row each, primary first, so
-- replaying the history still ends in the current assignment. What happened before them is unknown: no previous
-- primary, no note. gen_random_uuid() is built into PostgreSQL 13 and later.
INSERT INTO requisition_recruiter_changes (id, requisition_id, revision, change_type, recruiter_id,
    previous_recruiter_id, changed_by, changed_at, note)
SELECT gen_random_uuid(), requisition_id,
       ROW_NUMBER() OVER (PARTITION BY requisition_id
                          ORDER BY (assignment_role = 'PRIMARY') DESC, assigned_at, recruiter_id),
       CASE assignment_role WHEN 'PRIMARY' THEN 'PRIMARY_ASSIGNED' ELSE 'SUPPORTING_ADDED' END,
       recruiter_id, NULL, assigned_by, assigned_at, NULL
FROM requisition_recruiters;
