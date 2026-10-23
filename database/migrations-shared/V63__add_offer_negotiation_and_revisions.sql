-- Candidate decline categories are optional so responses written before this migration stay unchanged.
ALTER TABLE offers
    ADD COLUMN decline_reason VARCHAR(20),
    ADD COLUMN previous_offer_id UUID,
    ADD COLUMN revision_number INTEGER NOT NULL DEFAULT 1;
ALTER TABLE offers DROP CONSTRAINT offers_status_check;
ALTER TABLE offers ADD CONSTRAINT offers_status_check CHECK (status IN
    ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT','ACCEPTED','DECLINED',
     'REJECTED','EXPIRED','CANCELLED','SUPERSEDED'));
ALTER TABLE offers ADD CONSTRAINT offers_decline_reason CHECK (
    decline_reason IS NULL OR (decline_reason IN ('SALARY','LOCATION','OTHER_JOB','PERSONAL')
        AND status IN ('DECLINED','SUPERSEDED')));
ALTER TABLE offers ADD CONSTRAINT offers_revision_number CHECK (revision_number > 0);
ALTER TABLE offers ADD CONSTRAINT offers_previous_unique UNIQUE(previous_offer_id);
ALTER TABLE offers ADD CONSTRAINT offers_previous_application
    FOREIGN KEY(previous_offer_id,application_id) REFERENCES offers(id,application_id) ON DELETE RESTRICT;

-- Superseding a declined offer keeps the candidate's original response as historical evidence.
ALTER TABLE offers DROP CONSTRAINT offer_response_state;
ALTER TABLE offers ADD CONSTRAINT offer_response_state CHECK (
    (status IN ('ACCEPTED','DECLINED') AND responded_at IS NOT NULL AND sent_at IS NOT NULL)
    OR (status='SUPERSEDED' AND (
        (responded_at IS NOT NULL AND sent_at IS NOT NULL)
        OR (responded_at IS NULL AND response_note IS NULL AND decline_reason IS NULL)))
    OR (status NOT IN ('ACCEPTED','DECLINED','SUPERSEDED') AND responded_at IS NULL
        AND response_note IS NULL AND decline_reason IS NULL));

CREATE FUNCTION protect_offer_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE predecessor offers%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.previous_offer_id IS NULL THEN
            IF NEW.revision_number<>1 THEN
                RAISE EXCEPTION 'first offer revision must be one' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT * INTO predecessor FROM offers WHERE id=NEW.previous_offer_id FOR KEY SHARE;
            IF NOT FOUND OR predecessor.status<>'SUPERSEDED' OR NEW.status<>'DRAFT'
                OR NEW.application_id<>predecessor.application_id OR NEW.candidate_id<>predecessor.candidate_id
                OR NEW.position_id<>predecessor.position_id
                OR NEW.revision_number::BIGINT<>predecessor.revision_number::BIGINT+1 THEN
                RAISE EXCEPTION 'invalid offer predecessor or revision' USING ERRCODE='23514';
            END IF;
        END IF;
    ELSE
        IF ROW(NEW.previous_offer_id,NEW.revision_number) IS DISTINCT FROM ROW(OLD.previous_offer_id,OLD.revision_number) THEN
            RAISE EXCEPTION 'offer revision lineage is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.status='SUPERSEDED' AND OLD.status NOT IN ('APPROVED','SENT','DECLINED','SUPERSEDED') THEN
            RAISE EXCEPTION 'offer cannot be superseded from this status' USING ERRCODE='23514';
        END IF;
        IF OLD.status='SUPERSEDED' AND NEW.status<>'SUPERSEDED' THEN
            RAISE EXCEPTION 'superseded offer remains historical' USING ERRCODE='23514';
        END IF;
        IF NEW.status='SUPERSEDED' AND ROW(NEW.sent_at,NEW.responded_at,NEW.response_note,NEW.decline_reason)
            IS DISTINCT FROM ROW(OLD.sent_at,OLD.responded_at,OLD.response_note,OLD.decline_reason) THEN
            RAISE EXCEPTION 'superseded response evidence is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER offers_revision_identity BEFORE INSERT OR UPDATE ON offers
    FOR EACH ROW EXECUTE FUNCTION protect_offer_revision();

CREATE TABLE offer_negotiations (
    id UUID PRIMARY KEY,
    sequence_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    offer_id UUID NOT NULL REFERENCES offers(id) ON DELETE RESTRICT,
    reason VARCHAR(20) NOT NULL CHECK (reason IN ('SALARY','LOCATION','OTHER_JOB','PERSONAL')),
    comment VARCHAR(2000) NOT NULL CHECK (btrim(comment)<>''),
    actor_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    actor_name VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX offer_negotiations_offer_idx ON offer_negotiations(offer_id,sequence_number);
CREATE FUNCTION reject_offer_negotiation_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'offer negotiations cannot be updated or deleted' USING ERRCODE='23514';
END $$;
CREATE TRIGGER offer_negotiations_immutable BEFORE UPDATE OR DELETE ON offer_negotiations
    FOR EACH ROW EXECUTE FUNCTION reject_offer_negotiation_change();
