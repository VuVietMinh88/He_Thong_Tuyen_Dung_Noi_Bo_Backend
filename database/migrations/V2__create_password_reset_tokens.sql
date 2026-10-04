CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    CONSTRAINT valid_password_reset_expiry CHECK (expires_at > created_at)
);

CREATE INDEX password_reset_tokens_user_created_idx ON password_reset_tokens(user_id, created_at);
