-- Task 325: one submitted application and one private CV, saved in the same transaction.
-- The composite key prevents an application from naming a posting from another requisition.
ALTER TABLE job_postings ADD CONSTRAINT job_postings_id_requisition_key UNIQUE (id, requisition_id);

CREATE TABLE job_applications (
    id UUID PRIMARY KEY,
    job_posting_id UUID NOT NULL,
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    full_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    -- Unicode Locale.ROOT lowercasing can expand characters; the submitted email itself remains <=254.
    normalized_email VARCHAR(512) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    source_id UUID REFERENCES recruitment_catalog_items(id) ON DELETE RESTRICT,
    cover_letter TEXT,
    stage VARCHAR(20) NOT NULL DEFAULT 'NEW',
    received_at TIMESTAMPTZ NOT NULL,
    posting_title VARCHAR(255) NOT NULL,
    -- Task 329 supplies a SHA-256 hash, never the plaintext code. No expiry: revocation is explicit.
    tracking_code_hash BYTEA NOT NULL,
    tracking_revoked_at TIMESTAMPTZ,
    CONSTRAINT job_applications_posting_requisition_fkey FOREIGN KEY (job_posting_id, requisition_id)
        REFERENCES job_postings(id, requisition_id) ON DELETE RESTRICT,
    CONSTRAINT job_applications_requisition_email_key UNIQUE (requisition_id, normalized_email),
    CONSTRAINT job_applications_tracking_hash_key UNIQUE (tracking_code_hash),
    CONSTRAINT job_applications_valid_hash CHECK (octet_length(tracking_code_hash) = 32),
    CONSTRAINT job_applications_valid_stage CHECK (stage IN
        ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER', 'HIRED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT job_applications_valid_name CHECK (length(btrim(full_name)) > 0),
    -- Case normalization belongs to the Java Locale.ROOT contract, not the database's locale-dependent lower().
    CONSTRAINT job_applications_normalized_email CHECK (
        normalized_email = btrim(normalized_email) AND length(normalized_email) > 0),
    CONSTRAINT job_applications_valid_phone CHECK (phone ~ '^\+?[0-9]{8,15}$'),
    CONSTRAINT job_applications_cover_letter_length CHECK (cover_letter IS NULL OR length(cover_letter) <= 5000)
);
CREATE INDEX job_applications_requisition_stage_idx ON job_applications (requisition_id, stage);
CREATE INDEX job_applications_posting_idx ON job_applications (job_posting_id, requisition_id);
CREATE INDEX job_applications_source_idx ON job_applications (source_id);

CREATE TABLE application_cvs (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 BYTEA NOT NULL,
    content BYTEA NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_cvs_valid_type CHECK (content_type IN
        ('application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
    CONSTRAINT application_cvs_valid_size CHECK (size_bytes BETWEEN 1 AND 10485760
        AND size_bytes = octet_length(content)),
    CONSTRAINT application_cvs_valid_hash CHECK (octet_length(sha256) = 32 AND sha256 = pg_catalog.sha256(content)),
    CONSTRAINT application_cvs_valid_filename CHECK (length(filename) > 0
        AND position('/' in filename) = 0 AND position(chr(92) in filename) = 0)
);
-- There is intentionally no public file URL, account FK, global email uniqueness or automatic candidate merge.
