-- Recruiter-confirmed contact is local to one application; submission and duplicate identity remain immutable.
CREATE TABLE application_confirmed_contacts (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    full_name VARCHAR(120) NOT NULL CHECK (length(btrim(full_name)) > 0),
    email VARCHAR(254) NOT NULL CHECK (length(btrim(email)) > 0),
    normalized_email VARCHAR(512) NOT NULL,
    phone VARCHAR(16) NOT NULL CHECK (phone ~ '^\+?[0-9]{8,15}$'),
    cv_sha256 BYTEA NOT NULL CHECK (octet_length(cv_sha256) = 32),
    name_source VARCHAR(12) NOT NULL CHECK (name_source IN ('SUBMITTED','EXTRACTED','MANUAL')),
    email_source VARCHAR(12) NOT NULL CHECK (email_source IN ('SUBMITTED','EXTRACTED','MANUAL')),
    phone_source VARCHAR(12) NOT NULL CHECK (phone_source IN ('SUBMITTED','EXTRACTED','MANUAL')),
    -- Audit keeps source identity even if an extraction is removed later; verified by the service when saved.
    extraction_id UUID,
    confirmed_by UUID NOT NULL,
    confirmed_by_name VARCHAR(255) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CHECK ((name_source='EXTRACTED' OR email_source='EXTRACTED' OR phone_source='EXTRACTED') = (extraction_id IS NOT NULL))
);
CREATE TABLE application_contact_history (
    application_id UUID NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    before_contact JSONB NOT NULL,
    after_contact JSONB NOT NULL,
    actor_id UUID NOT NULL,
    actor_name VARCHAR(255) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (application_id,revision),
    CHECK (jsonb_typeof(before_contact)='object' AND octet_length(before_contact::text)<=16384),
    CHECK (jsonb_typeof(after_contact)='object' AND octet_length(after_contact::text)<=16384)
);
-- Edits to audit are forbidden; deleting the owning application can still cascade.
CREATE FUNCTION guard_application_contact_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND NOT EXISTS (SELECT 1 FROM job_applications WHERE id=OLD.application_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Contact audit is append-only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER application_contact_history_immutable BEFORE UPDATE OR DELETE ON application_contact_history
    FOR EACH ROW EXECUTE FUNCTION guard_application_contact_history();
