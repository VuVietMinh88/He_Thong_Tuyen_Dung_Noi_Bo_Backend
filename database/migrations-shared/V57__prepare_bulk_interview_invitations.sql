ALTER TABLE interview_sessions ADD COLUMN preparation_instructions VARCHAR(2000) NOT NULL
    DEFAULT 'Vui lòng chuẩn bị CV và các thông tin liên quan đến kinh nghiệm của bạn.';
ALTER TABLE interview_sessions ADD CONSTRAINT interview_preparation_nonblank
    CHECK (length(btrim(preparation_instructions)) > 0);

CREATE TABLE interview_bulk_requests (
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    response_json JSONB NOT NULL,
    PRIMARY KEY(actor_id, request_id)
);

CREATE TABLE interview_invitation_request_buckets (
    bucket_key CHAR(64) PRIMARY KEY CHECK (bucket_key ~ '^[a-f0-9]{64}$'),
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX interview_invitation_request_buckets_cleanup
    ON interview_invitation_request_buckets(window_started_at, bucket_key);
