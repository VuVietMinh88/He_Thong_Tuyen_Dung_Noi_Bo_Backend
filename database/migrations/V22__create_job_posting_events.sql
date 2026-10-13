-- Job posting review and publication (story S3-10, task 308): "Xây dựng API duyệt và xuất bản tin tuyển dụng". The
-- review statuses and the history of the workflow, like V19 for requisitions: one row per step (sent for review,
-- approved, sent back for changes, published), written in the same transaction as the status change while the
-- requisition and posting rows are locked. Every existing posting is DRAFT, the only status V21 allowed.
ALTER TABLE job_postings DROP CONSTRAINT valid_job_posting_status;
ALTER TABLE job_postings ADD CONSTRAINT valid_job_posting_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED', 'PUBLISHED'));
-- A posting that left the editable statuses has everything the portal shows. The API refuses an incomplete posting
-- when it is sent for review (JOB_POSTING_INCOMPLETE); this is the last line.
ALTER TABLE job_postings ADD CONSTRAINT job_posting_details_required CHECK (
    status IN ('DRAFT', 'REVISION_REQUESTED')
    OR (work_location_id IS NOT NULL AND employment_type_id IS NOT NULL AND application_deadline IS NOT NULL));

CREATE TABLE job_posting_events (
    id UUID PRIMARY KEY,
    -- RESTRICT: the history lives as long as the posting (no API deletes postings).
    job_posting_id UUID NOT NULL REFERENCES job_postings(id) ON DELETE RESTRICT,
    -- The posting's revision right after this step (job_postings.revision), so the history and expectedRevision share
    -- one counter. Gaps are content saves, which are not workflow steps.
    revision INTEGER NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    -- NOT NULL on purpose: a CHECK whose result is NULL passes, so the transition CHECK below needs both sides set.
    status_before VARCHAR(20) NOT NULL,
    status_after VARCHAR(20) NOT NULL,
    -- RESTRICT: no API deletes accounts.
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- Optional when sending for review or approving, required when asking for changes, never on publication. The API
    -- limits it to 2,000 characters and stores a comment without any visible character as NULL.
    comment TEXT,
    CONSTRAINT job_posting_events_revision_key UNIQUE (job_posting_id, revision),
    CONSTRAINT positive_job_posting_event_revision CHECK (revision > 0),
    CONSTRAINT valid_job_posting_event_type
        CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REVISION_REQUESTED', 'PUBLISHED')),
    CONSTRAINT valid_job_posting_event_transition CHECK (
        (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'REVISION_REQUESTED')
            AND status_after = 'PENDING_APPROVAL')
        OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'APPROVED')
        OR (event_type = 'REVISION_REQUESTED' AND status_before IN ('PENDING_APPROVAL', 'APPROVED')
            AND status_after = 'REVISION_REQUESTED')
        OR (event_type = 'PUBLISHED' AND status_before = 'APPROVED' AND status_after = 'PUBLISHED')
    ),
    CONSTRAINT valid_job_posting_event_comment CHECK (
        comment IS NULL OR (comment ~ '[^[:space:]]' AND char_length(comment) <= 2000)),
    CONSTRAINT job_posting_event_comment_required CHECK (event_type <> 'REVISION_REQUESTED' OR comment IS NOT NULL),
    CONSTRAINT job_posting_event_comment_allowed CHECK (event_type <> 'PUBLISHED' OR comment IS NULL)
);

-- A posting is published once (nothing leaves PUBLISHED in Sprint 3). Last line against a second publication by a
-- writer that skipped the locks.
CREATE UNIQUE INDEX job_posting_events_one_publication_idx ON job_posting_events(job_posting_id)
    WHERE event_type = 'PUBLISHED';
-- PostgreSQL does not index foreign keys by itself; the revision key serves "the history of one posting".
CREATE INDEX job_posting_events_actor_id_idx ON job_posting_events(actor_id);
