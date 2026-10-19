-- Login throttling belongs to a browser, never to the target account.
CREATE TABLE login_devices (
    id UUID PRIMARY KEY,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Preserve legacy columns for compatibility; administrative locks are separate.
UPDATE user_accounts SET failed_login_attempts = 0, locked_until = NULL;
