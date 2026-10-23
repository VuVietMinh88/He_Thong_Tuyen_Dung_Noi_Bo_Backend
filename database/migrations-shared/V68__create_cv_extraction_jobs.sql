-- Internal, explicitly requested suggestions; independent of application intake.
CREATE TABLE cv_extraction_jobs (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    cv_sha256 BYTEA NOT NULL CHECK (octet_length(cv_sha256) = 32),
    extractor_version VARCHAR(40) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    result JSONB,
    error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (application_id, cv_sha256, extractor_version),
    CHECK (result IS NULL OR (jsonb_typeof(result) = 'object' AND octet_length(result::text) <= 131072)),
    CHECK ((status = 'SUCCEEDED') = (result IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
CREATE TABLE cv_extraction_attempts (
    job_id UUID NOT NULL REFERENCES cv_extraction_jobs(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL CHECK (attempt > 0),
    is_retry BOOLEAN NOT NULL,
    requested_by UUID REFERENCES user_accounts(id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(60),
    PRIMARY KEY (job_id, attempt),
    CHECK (is_retry = (attempt > 1)),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK ((status IN ('SUCCEEDED','FAILED')) = (finished_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
CREATE INDEX cv_extraction_jobs_queue_idx ON cv_extraction_jobs(created_at) WHERE status = 'QUEUED';
CREATE INDEX cv_extraction_jobs_active_idx ON cv_extraction_jobs(status,updated_at) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX cv_extraction_attempts_retry_idx ON cv_extraction_attempts(requested_at) WHERE is_retry;
CREATE INDEX cv_extraction_attempts_lease_idx ON cv_extraction_attempts(lease_until) WHERE status = 'RUNNING';
