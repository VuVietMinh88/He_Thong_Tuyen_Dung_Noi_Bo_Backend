-- Task 351 (story S4-07): every referral attempt that passed validation, whatever it answered (201, 404, 409 or a
-- refusal after the account lock), written in its own committed transaction before the referral itself. It is the
-- 24-hour limit per referrer (a 409 rolls the referral back but its attempt stays, so the endpoint cannot be used to
-- learn for free who applied where) and the audit trail of who tried which posting and what came back. No candidate
-- data. One bucket row per referrer serialises concurrent attempts, like V27 per IP.
CREATE TABLE referral_quotas (
    referrer_id UUID PRIMARY KEY REFERENCES user_accounts(id) ON DELETE RESTRICT,
    last_attempt_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE referral_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- RESTRICT like every staff reference: no API deletes accounts.
    referrer_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    -- No FK: an unknown posting is an attempt too (404).
    job_posting_id UUID NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    -- NULL until the referral has answered, or when recording the answer failed.
    outcome VARCHAR(20),
    CONSTRAINT referral_attempts_outcome CHECK (outcome IS NULL OR outcome IN
        ('STORED','POSTING_UNAVAILABLE','DUPLICATE','REFUSED','FAILED'))
);
-- Serves the 24-hour window of one referrer and the RESTRICT check.
CREATE INDEX referral_attempts_referrer_idx ON referral_attempts(referrer_id, attempted_at);
