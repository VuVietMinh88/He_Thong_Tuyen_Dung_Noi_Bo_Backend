-- Task 533 (story S8-01): one row per candidate email decided by a business event or by a person, for the five
-- template types of V66. It is written in the event's own transaction, together with the delivery row of the outbox
-- that sends it (delivery, delivery_id), or alone as SUPPRESSED when the type's automatic sending is off: a suppressed
-- email is never queued and never shown as sent. The outbox keeps its own retry state; the trigger below copies every
-- status change of a delivery row onto its message, so both always agree.
CREATE TABLE email_messages (
    id UUID PRIMARY KEY,
    template_type VARCHAR(32) NOT NULL,
    -- INTERVIEW_INVITATION only: which interview email (the template is used for INVITATION).
    variant VARCHAR(16),
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    interview_id UUID,
    offer_id UUID,
    trigger_kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    delivery VARCHAR(32),
    delivery_id UUID,
    -- The address the email was decided for; the candidate's own address of the application at that time.
    recipient_email VARCHAR(254) NOT NULL,
    dedupe_key VARCHAR(200) NOT NULL UNIQUE,
    last_failure VARCHAR(40),
    created_by UUID REFERENCES user_accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT email_messages_type CHECK (template_type IN
        ('APPLICATION_RECEIPT', 'INTERVIEW_INVITATION', 'INTERVIEW_THANKS', 'REJECTION', 'OFFER')),
    CONSTRAINT email_messages_variant CHECK (
        (template_type = 'INTERVIEW_INVITATION' AND variant IN ('INVITATION', 'UPDATED', 'CANCELLED'))
        OR (template_type <> 'INTERVIEW_INVITATION' AND variant IS NULL)),
    CONSTRAINT email_messages_interview CHECK (
        (template_type IN ('INTERVIEW_INVITATION', 'INTERVIEW_THANKS')) = (interview_id IS NOT NULL)),
    CONSTRAINT email_messages_offer CHECK ((template_type = 'OFFER') = (offer_id IS NOT NULL)),
    CONSTRAINT email_messages_trigger CHECK (trigger_kind IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT email_messages_status CHECK (status IN
        ('QUEUED', 'SENDING', 'RETRY_WAIT', 'SENT', 'FAILED', 'SUPPRESSED', 'CANCELLED')),
    CONSTRAINT email_messages_delivery CHECK (delivery IS NULL OR delivery IN
        ('APPLICATION_CONFIRMATION', 'INTERVIEW_INVITATION', 'OFFER_DELIVERY', 'CANDIDATE_MAIL')),
    -- A suppressed email has no delivery; every other one has. CANDIDATE_MAIL rows get their queue row in V66_3.
    CONSTRAINT email_messages_suppressed CHECK ((status = 'SUPPRESSED') = (delivery IS NULL)),
    CONSTRAINT email_messages_delivery_row CHECK (delivery IS NULL OR delivery = 'CANDIDATE_MAIL' OR delivery_id IS NOT NULL),
    CONSTRAINT email_messages_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT email_messages_recipient CHECK (btrim(recipient_email) <> ''),
    CONSTRAINT email_messages_interview_application FOREIGN KEY (interview_id, application_id)
        REFERENCES interview_sessions(id, application_id) ON DELETE CASCADE,
    CONSTRAINT email_messages_offer_application FOREIGN KEY (offer_id, application_id)
        REFERENCES offers(id, application_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX email_messages_delivery_idx ON email_messages(delivery, delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX email_messages_application_idx ON email_messages(application_id, created_at DESC, id DESC);
CREATE INDEX email_messages_created_idx ON email_messages(created_at DESC, id DESC);
-- At most one rejection letter on its way or delivered per application (story S7-09 "chặn gửi trùng"): a second
-- automatic or bulk rejection cannot be queued, even by concurrent requests.
CREATE UNIQUE INDEX email_messages_one_rejection_idx ON email_messages(application_id)
    WHERE template_type = 'REJECTION' AND status IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'SENT');

CREATE FUNCTION sync_email_message_status() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
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

CREATE TRIGGER application_email_outbox_message_status AFTER UPDATE OF status ON application_email_outbox
    FOR EACH ROW EXECUTE FUNCTION sync_email_message_status('APPLICATION_CONFIRMATION');
CREATE TRIGGER interview_invitation_outbox_message_status AFTER UPDATE OF status ON interview_invitation_outbox
    FOR EACH ROW EXECUTE FUNCTION sync_email_message_status('INTERVIEW_INVITATION');
CREATE TRIGGER offer_delivery_outbox_message_status AFTER UPDATE OF status ON offer_delivery_outbox
    FOR EACH ROW EXECUTE FUNCTION sync_email_message_status('OFFER_DELIVERY');
