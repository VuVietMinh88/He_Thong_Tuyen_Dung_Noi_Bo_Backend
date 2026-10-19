-- Durable, sliding-window admission quota for the public application endpoint.
CREATE TABLE application_ip_quotas (
    client_ip INET PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_ip_quotas_host_address CHECK (
        masklen(client_ip) = CASE family(client_ip) WHEN 4 THEN 32 ELSE 128 END)
);
CREATE INDEX application_ip_quotas_last_attempt_idx ON application_ip_quotas(last_attempt_at);

CREATE TABLE application_ip_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_ip INET NOT NULL REFERENCES application_ip_quotas(client_ip) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX application_ip_attempts_window_idx ON application_ip_attempts(client_ip, attempted_at);
-- Only admitted attempts are recorded, up to five live attempts per IP. No application/email/CV is stored here.
