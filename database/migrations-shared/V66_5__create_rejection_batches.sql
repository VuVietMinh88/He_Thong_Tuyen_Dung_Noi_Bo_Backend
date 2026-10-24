-- Task 527 (story S7-09, "Hệ thống chặn gửi trùng cho ứng viên đã nhận thư từ chối"): one row per bulk rejection
-- request of an actor, keyed by the client's requestId, with the hash of the selection and the answer that was given.
-- A repeated request (a double click, a retry after a timeout) gets the same answer and queues nothing; the same
-- requestId with another selection is refused. The letters themselves are email_messages rows (V66_1), whose unique
-- email_messages_one_rejection_idx keeps one live rejection letter per application.
CREATE TABLE rejection_batches (
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (actor_id, request_id),
    CONSTRAINT rejection_batches_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rejection_batches_response CHECK (jsonb_typeof(response_json) = 'object')
);
