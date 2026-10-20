-- Task 339 (story S4-05): durable sliding-window quota of the public tracking API (lookup; from task 345 also the
-- withdrawal), one bucket per IPv4 address or per IPv6 /64 (stored as the first address of the /64, so it is still a
-- host value). Same shape and algorithm as V27 but separate rows, so a lookup never spends one of the five submissions
-- per IP and hour. Nothing else is stored here: no code, email or application.
CREATE TABLE application_tracking_ip_quotas (
    client_ip INET PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_tracking_ip_quotas_host_address CHECK (
        masklen(client_ip) = CASE family(client_ip) WHEN 4 THEN 32 ELSE 128 END)
);
CREATE INDEX application_tracking_ip_quotas_last_attempt_idx ON application_tracking_ip_quotas(last_attempt_at);

CREATE TABLE application_tracking_ip_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_ip INET NOT NULL REFERENCES application_tracking_ip_quotas(client_ip) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX application_tracking_ip_attempts_window_idx ON application_tracking_ip_attempts(client_ip, attempted_at);
-- Every admitted well-formed attempt is recorded (found or not); malformed and refused requests are not.
