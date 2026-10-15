-- Who published a job posting and when (story S3-10, task 310): "Ghi người xuất bản và thời điểm công khai". Kept on
-- the posting next to its status, so the database itself guarantees that a published posting names its publisher
-- and publication time (a CHECK cannot read job_posting_events), and the public list is ordered by it. The PUBLISHED
-- event (V22) keeps the same facts in the history, which from now on can only be added to.
ALTER TABLE job_postings
    -- RESTRICT like created_by: no API deletes accounts.
    ADD COLUMN published_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    ADD COLUMN published_at TIMESTAMPTZ;
-- Postings published under V22 take both from their only PUBLISHED event (job_posting_events_one_publication_idx).
UPDATE job_postings p SET published_by = e.actor_id, published_at = e.occurred_at
FROM job_posting_events e
WHERE e.job_posting_id = p.id AND e.event_type = 'PUBLISHED';
-- Both set or both empty; "=" of two NOT NULL booleans is never NULL.
ALTER TABLE job_postings ADD CONSTRAINT valid_job_posting_publication
    CHECK ((published_by IS NULL) = (published_at IS NULL));
-- A published posting always names who published it and when; a posting never published has no trail. A status
-- after PUBLISHED (CLOSED, task 292) is in neither list and keeps it.
ALTER TABLE job_postings ADD CONSTRAINT job_posting_publication_required
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL);
ALTER TABLE job_postings ADD CONSTRAINT job_posting_unpublished_without_trail CHECK (
    status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'REVISION_REQUESTED', 'APPROVED') OR published_at IS NULL);
-- PostgreSQL does not index foreign keys by itself. The second index serves the public list, newest publication first.
CREATE INDEX job_postings_published_by_idx ON job_postings(published_by);
CREATE INDEX job_postings_published_at_idx ON job_postings(published_at DESC, id DESC) WHERE status = 'PUBLISHED';

-- Like V20: the review and publication history is only ever added to; no saved row can be changed or removed, not
-- even with SQL written by hand. TRUNCATE (table owner only; tests use it to clean up) fires no row trigger and stays
-- possible.
CREATE FUNCTION reject_job_posting_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'job_posting_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER job_posting_events_append_only
    BEFORE UPDATE OR DELETE ON job_posting_events
    FOR EACH ROW EXECUTE FUNCTION reject_job_posting_event_change();
