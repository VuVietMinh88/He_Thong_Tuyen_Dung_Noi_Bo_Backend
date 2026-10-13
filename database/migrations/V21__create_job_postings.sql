-- Job postings (story S3-09, task 302): "Lưu bản nháp tin tuyển dụng và thông tin công khai". A posting is made from an
-- APPROVED requisition (task 303) and keeps its own copy of the title, the job description and the candidate
-- requirements: the API copies them once and never writes the requisition, so the recruiter rewrites them in
-- recruiting language without changing the requisition (task 304). Position, department, headcount and the salary
-- shown are read from the requisition, which cannot change once approved, and are not copied. The public details
-- (work location, employment type, application deadline, whether the salary is shown) belong to the posting.
-- Only DRAFT exists for now; the review workflow (task 308) drops and re-creates the named status CHECK.
CREATE TABLE job_postings (
    id UUID PRIMARY KEY,
    -- RESTRICT like V15-V19: no API deletes a requisition, and a posting never loses its source. One posting per
    -- requisition (job_postings_requisition_id_key), a backend decision waiting for BA/PO.
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    -- 1 when created, one more for every save and every status change. Clients send it back as expectedRevision with
    -- every change, so a change made on an outdated view is refused.
    revision INTEGER NOT NULL,
    -- The position name unless the author gives another one; same limit as positions.name, so a copy always fits.
    title VARCHAR(255) NOT NULL,
    -- Copied from the requisition when the posting is created, then edited only here. An approved requisition always
    -- has both (RequisitionService.requireComplete). No length CHECK, like V13: a requisition text written by hand
    -- above the API limit can still be copied, and the next save of the posting reports it.
    job_description TEXT NOT NULL,
    candidate_requirements TEXT NOT NULL,
    -- Optional, never copied from the requisition.
    benefits TEXT,
    -- Shared catalog values (V10): WORK_LOCATION ("Địa điểm làm việc") and EMPLOYMENT_TYPE ("Hình thức làm việc").
    -- Plain, non-deferrable keys as docs/database/README.md requires, so deleting a value still used fails at once
    -- and the catalog API answers RECRUITMENT_CATALOG_ITEM_IN_USE (task 229). The API checks the catalog type; an
    -- item never changes type (task 228). Empty in a draft until chosen; sending for review needs them (task 308).
    work_location_id UUID REFERENCES recruitment_catalog_items(id) ON DELETE RESTRICT,
    employment_type_id UUID REFERENCES recruitment_catalog_items(id) ON DELETE RESTRICT,
    -- Last day candidates may apply, inclusive, a calendar date in app.business-zone. The API refuses a day before
    -- today; a CHECK cannot, because today changes.
    application_deadline DATE,
    -- Whether candidates see the requisition's approved salary proposal. No amount is stored here.
    show_salary BOOLEAN NOT NULL,
    -- RESTRICT: no API deletes accounts.
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    -- The last content edit; status changes do not touch it (like recruitment_requisitions.updated_at).
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT job_postings_requisition_id_key UNIQUE (requisition_id),
    CONSTRAINT positive_job_posting_revision CHECK (revision > 0),
    -- Never empty or whitespace-only; benefits not written yet are NULL.
    CONSTRAINT valid_job_posting_title CHECK (title ~ '[^[:space:]]'),
    CONSTRAINT valid_job_posting_job_description CHECK (job_description ~ '[^[:space:]]'),
    CONSTRAINT valid_job_posting_candidate_requirements CHECK (candidate_requirements ~ '[^[:space:]]'),
    CONSTRAINT valid_job_posting_benefits CHECK (
        benefits IS NULL OR (benefits ~ '[^[:space:]]' AND char_length(benefits) <= 10000)),
    -- Only DRAFT exists for now. The review migration (task 308) drops and re-creates this named constraint.
    CONSTRAINT valid_job_posting_status CHECK (status IN ('DRAFT'))
);

-- PostgreSQL does not index foreign keys by itself. The unique key above serves requisition_id; these serve the
-- RESTRICT checks (catalog delete, accounts), "postings I created" and the status filters of lists and the portal.
CREATE INDEX job_postings_work_location_id_idx ON job_postings(work_location_id);
CREATE INDEX job_postings_employment_type_id_idx ON job_postings(employment_type_id);
CREATE INDEX job_postings_created_by_idx ON job_postings(created_by);
CREATE INDEX job_postings_status_idx ON job_postings(status);

-- Everyone who wrote the content of a posting: its creator and every account that saved it (task 303). HR and ADMIN
-- may edit every posting and also review it, so the review (task 308) refuses APPROVE to all of them: nobody approves
-- text they wrote. Only ever added to by the API: an account that once wrote the content stays an author.
CREATE TABLE job_posting_authors (
    -- RESTRICT like every other reference to postings and accounts: no API deletes either.
    job_posting_id UUID NOT NULL REFERENCES job_postings(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    PRIMARY KEY (job_posting_id, account_id)
);
-- The primary key serves "the authors of one posting"; this index serves the RESTRICT check on accounts.
CREATE INDEX job_posting_authors_account_id_idx ON job_posting_authors(account_id);
