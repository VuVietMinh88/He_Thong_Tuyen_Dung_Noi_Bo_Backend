-- Taking a published job posting down (story S4-08, task 355): "Xây dựng API gỡ tin tuyển dụng ngay lập tức". The
-- recruiter of the requisition or HR moves a PUBLISHED posting to UNPUBLISHED at any time, with a reason; the portal
-- shows only PUBLISHED postings (task 309), so it leaves at once. UNPUBLISHED is final (a posting is published once:
-- job_posting_events_one_publication_idx), except that closing its requisition still closes it (task 292). It keeps who
-- published it and when: V23 job_posting_unpublished_without_trail means "not published yet" and lists only the
-- statuses before the publication. No existing row changes.
ALTER TABLE job_postings DROP CONSTRAINT valid_job_posting_status;
ALTER TABLE job_postings ADD CONSTRAINT valid_job_posting_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED', 'PUBLISHED', 'CLOSED',
                      'UNPUBLISHED'));
-- A posting taken down was published, so it always names who published it and when.
ALTER TABLE job_postings DROP CONSTRAINT job_posting_publication_required;
ALTER TABLE job_postings ADD CONSTRAINT job_posting_publication_required
    CHECK (status NOT IN ('PUBLISHED', 'UNPUBLISHED') OR published_at IS NOT NULL);

ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_type;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_type
    CHECK (event_type IN ('SUBMITTED', 'APPROVED', 'REVISION_REQUESTED', 'PUBLISHED', 'CLOSED', 'UNPUBLISHED'));
ALTER TABLE job_posting_events DROP CONSTRAINT valid_job_posting_event_transition;
ALTER TABLE job_posting_events ADD CONSTRAINT valid_job_posting_event_transition CHECK (
    (event_type = 'SUBMITTED' AND status_before IN ('DRAFT', 'REVISION_REQUESTED')
        AND status_after = 'PENDING_APPROVAL')
    OR (event_type = 'APPROVED' AND status_before = 'PENDING_APPROVAL' AND status_after = 'APPROVED')
    OR (event_type = 'REVISION_REQUESTED' AND status_before IN ('PENDING_APPROVAL', 'APPROVED')
        AND status_after = 'REVISION_REQUESTED')
    OR (event_type = 'PUBLISHED' AND status_before = 'APPROVED' AND status_after = 'PUBLISHED')
    OR (event_type = 'CLOSED' AND status_before IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED',
        'PUBLISHED', 'UNPUBLISHED') AND status_after = 'CLOSED')
    OR (event_type = 'UNPUBLISHED' AND status_before = 'PUBLISHED' AND status_after = 'UNPUBLISHED'));
-- The reason is required, like a closure's.
ALTER TABLE job_posting_events DROP CONSTRAINT job_posting_event_comment_required;
ALTER TABLE job_posting_events ADD CONSTRAINT job_posting_event_comment_required
    CHECK (event_type NOT IN ('REVISION_REQUESTED', 'CLOSED', 'UNPUBLISHED') OR comment IS NOT NULL);
-- Taken down once: the last line behind the posting row lock.
CREATE UNIQUE INDEX job_posting_events_one_unpublication_idx ON job_posting_events(job_posting_id)
    WHERE event_type = 'UNPUBLISHED';
