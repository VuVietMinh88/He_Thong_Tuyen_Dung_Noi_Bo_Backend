-- Daily expiry of job postings (story S4-08, task 356): "Tạo tác vụ hàng ngày tự đánh dấu tin hết hạn". Every day at
-- 00:05 in the business time zone a scheduled task moves each PUBLISHED posting whose last day to apply has passed to
-- EXPIRED, one posting per transaction under the requisition and posting row locks, with an EXPIRED step in its
-- history. No person does this step, so its actor is empty; every other step still names one. The portal already hides
-- such a posting from the list by date from 00:00 (task 309) and its detail shows a notice (task 320); the status only
-- follows. EXPIRED may still be taken down (task 355) and is closed with its requisition (task 292). No existing row
-- changes: a published posting already past its deadline is expired by the first run, not by this migration.
ALTER TABLE job_postings DROP CONSTRAINT valid_job_posting_status;
ALTER TABLE job_postings ADD CONSTRAINT valid_job_posting_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED', 'PUBLISHED', 'CLOSED',
                      'UNPUBLISHED', 'EXPIRED'));
-- An expired posting was published, so it always names who published it and when.
ALTER TABLE job_postings DROP CONSTRAINT job_posting_publication_required;
ALTER TABLE job_postings ADD CONSTRAINT job_posting_publication_required
    CHECK (status NOT IN ('PUBLISHED', 'UNPUBLISHED', 'EXPIRED') OR published_at IS NOT NULL);

ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_type;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_type
    CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REVISION_REQUESTED', 'PUBLISHED', 'CLOSED', 'UNPUBLISHED',
                          'EXPIRED'));
ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_transition;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_transition CHECK (
    (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'REVISION_REQUESTED')
        AND status_after = 'PENDING_APPROVAL')
    OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'APPROVED')
    OR (event_type = 'REVISION_REQUESTED' AND status_before IN ('PENDING_APPROVAL', 'APPROVED')
        AND status_after = 'REVISION_REQUESTED')
    OR (event_type = 'PUBLISHED' AND status_before = 'APPROVED' AND status_after = 'PUBLISHED')
    OR (event_type = 'CLOSED' AND status_before IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED',
        'PUBLISHED', 'UNPUBLISHED', 'EXPIRED') AND status_after = 'CLOSED')
    OR (event_type = 'UNPUBLISHED' AND status_before IN ('PUBLISHED', 'EXPIRED') AND status_after = 'UNPUBLISHED')
    OR (event_type = 'EXPIRED' AND status_before = 'PUBLISHED' AND status_after = 'EXPIRED'));
-- The scheduled task is no account: only its steps have no actor. "=" of two NOT NULL booleans is never NULL.
ALTER TABLE job_posting_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE job_posting_events ADD CONSTRAINT job_posting_event_actor_required
    CHECK ((event_type = 'EXPIRED') = (actor_id IS NULL));
-- No comment on an expiry, like a publication.
ALTER TABLE job_posting_events DROP CONSTRAINT job_posting_event_comment_allowed;
ALTER TABLE job_posting_events ADD CONSTRAINT job_posting_event_comment_allowed
    CHECK (event_type NOT IN ('PUBLISHED', 'EXPIRED') OR comment IS NULL);
-- Expired once: the last line behind the row locks and the re-check.
CREATE UNIQUE INDEX job_posting_events_one_expiry_idx ON job_posting_events(job_posting_id)
    WHERE event_type = 'EXPIRED';
