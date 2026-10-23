-- Forgot-password requests are limited per browser (the X-Device-Id UUID the frontend keeps): five requests in a
-- 15-minute window are accepted, the sixth and later are refused until 15 minutes after the fifth. Every well-formed
-- request counts, whether or not its email belongs to an account, so the limit never tells which emails exist.
-- Throttling belongs to a browser, never to the target account: no email, account id or IP address is stored.
-- No existing table or row changes.
CREATE TABLE password_reset_devices (
    id UUID PRIMARY KEY,
    request_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_password_reset_device_request_count CHECK (request_count BETWEEN 0 AND 5),
    -- A browser that has sent no request yet has no window; one that has, always has one.
    CONSTRAINT password_reset_device_window_required CHECK ((request_count = 0) = (window_started_at IS NULL)),
    -- Only the fifth request of a window locks the browser.
    CONSTRAINT password_reset_device_lock_after_limit CHECK (locked_until IS NULL OR request_count = 5)
);

-- Lets a later clean-up remove rows that have not been used for a long time.
CREATE INDEX password_reset_devices_updated_at_idx ON password_reset_devices (updated_at);
