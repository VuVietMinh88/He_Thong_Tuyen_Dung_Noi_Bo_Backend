-- One strict deadline rule, also exercised at the exact boundary by database integration tests.
CREATE FUNCTION candidate_merge_undo_open(deadline TIMESTAMPTZ, checked_at TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE STRICT AS $$ SELECT checked_at < deadline $$;

CREATE OR REPLACE FUNCTION stamp_candidate_merge_undo() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE deadline TIMESTAMPTZ;
BEGIN
    SELECT undo_deadline INTO deadline FROM candidate_merge_operations WHERE id=NEW.merge_operation_id;
    NEW.recorded_at := clock_timestamp();
    IF deadline IS NOT NULL AND NOT candidate_merge_undo_open(deadline,NEW.recorded_at) THEN
        RAISE EXCEPTION 'Candidate undo deadline expired' USING ERRCODE='P7501';
    END IF;
    RETURN NEW;
END $$;
