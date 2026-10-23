-- Task 537 (story S8-02): an immutable log of every delivery attempt of a candidate email of email_messages (V66_1).
-- A row is written by the same trigger that keeps the message status in step with its outbox row, in the worker's
-- fenced transaction, so no code path (sent, temporary or permanent failure, a lease that expired, a cancellation
-- while the attempt was running) can finish an attempt without logging it. error_code is the outbox's safe failure
-- code only: never SMTP server text, a token or a body. Rows are never updated or deleted (trigger below, like the
-- other append-only logs), and a message that has attempts cannot be deleted either (RESTRICT).
CREATE TABLE email_attempts (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES email_messages(id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL,
    delivery VARCHAR(32) NOT NULL,
    delivery_id UUID NOT NULL,
    template_type VARCHAR(32) NOT NULL,
    -- The template version current when the attempt ended (workers render the current template just before SMTP);
    -- NULL for the interview UPDATED and CANCELLED emails, whose text is fixed and uses no template.
    template_version BIGINT,
    recipient_email VARCHAR(254) NOT NULL,
    result VARCHAR(16) NOT NULL,
    error_code VARCHAR(40),
    attempted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_attempts_number CHECK (attempt_number > 0),
    CONSTRAINT email_attempts_once UNIQUE (message_id, attempt_number),
    CONSTRAINT email_attempts_result CHECK (result IN ('SENT', 'RETRY_SCHEDULED', 'FAILED', 'CANCELLED', 'LEASE_EXPIRED')),
    -- Only a delivered attempt has no error code.
    CONSTRAINT email_attempts_error CHECK ((result = 'SENT') = (error_code IS NULL))
);
CREATE INDEX email_attempts_message_idx ON email_attempts(message_id, attempt_number);

CREATE FUNCTION reject_email_attempt_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'email_attempts is append-only: % is not allowed', TG_OP USING ERRCODE = '23514';
END $$;
CREATE TRIGGER email_attempts_immutable BEFORE UPDATE OR DELETE ON email_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_email_attempt_change();

-- V66_1's function, now also logging the attempt that a status change ends. An attempt is running while the outbox
-- row is PROCESSING: leaving PROCESSING ends it (SENT, RETRY_WAIT, FAILED, CANCELLED), and a new claim of a row that
-- is still PROCESSING with more attempts means the previous lease expired with an unknown outcome.
CREATE OR REPLACE FUNCTION sync_email_message_status() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    message email_messages%ROWTYPE;
    outcome VARCHAR(16);
    code VARCHAR(40);
BEGIN
    IF OLD.status = 'PROCESSING' AND (NEW.status <> 'PROCESSING' OR NEW.attempts > OLD.attempts) THEN
        SELECT * INTO message FROM email_messages WHERE delivery = TG_ARGV[0] AND delivery_id = NEW.id;
        IF FOUND THEN
            IF NEW.status = 'PROCESSING' THEN
                outcome := 'LEASE_EXPIRED';
                code := 'DELIVERY_UNCERTAIN';
            ELSE
                outcome := CASE NEW.status WHEN 'RETRY_WAIT' THEN 'RETRY_SCHEDULED' ELSE NEW.status END;
                code := CASE WHEN NEW.status = 'SENT' THEN NULL ELSE COALESCE(NEW.last_failure, 'UNKNOWN') END;
            END IF;
            INSERT INTO email_attempts(id, message_id, attempt_number, delivery, delivery_id, template_type,
                template_version, recipient_email, result, error_code, attempted_at)
            VALUES (gen_random_uuid(), message.id,
                (SELECT COALESCE(max(a.attempt_number), 0) + 1 FROM email_attempts a WHERE a.message_id = message.id),
                TG_ARGV[0], NEW.id, message.template_type,
                CASE WHEN message.variant IN ('UPDATED', 'CANCELLED') THEN NULL
                    ELSE (SELECT t.version FROM email_templates t WHERE t.template_type = message.template_type) END,
                message.recipient_email, outcome, code, clock_timestamp());
        END IF;
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        UPDATE email_messages m SET
            status = CASE NEW.status WHEN 'PENDING' THEN 'QUEUED' WHEN 'PROCESSING' THEN 'SENDING'
                ELSE NEW.status END,
            last_failure = NEW.last_failure,
            sent_at = CASE WHEN NEW.status = 'SENT' THEN NEW.sent_at ELSE NULL END,
            updated_at = clock_timestamp()
        WHERE m.delivery = TG_ARGV[0] AND m.delivery_id = NEW.id;
    END IF;
    RETURN NEW;
END $$;
