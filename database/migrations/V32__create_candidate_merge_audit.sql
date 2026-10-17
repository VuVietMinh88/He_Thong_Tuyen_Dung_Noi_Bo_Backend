-- Internal storage foundation. There are no public merge/undo endpoints yet.
ALTER TABLE candidate_profiles ADD COLUMN representative_application_id UUID;
ALTER TABLE candidate_profiles ADD COLUMN merge_head_id UUID;
UPDATE candidate_profiles p SET representative_application_id=(
    SELECT m.application_id FROM candidate_memberships m WHERE m.candidate_id=p.id
    ORDER BY m.application_id LIMIT 1
);
-- Empty historical groups remain valid. Deleting a representative does not delete the group or its audit.
ALTER TABLE candidate_profiles ADD CONSTRAINT candidate_representative_member
    FOREIGN KEY (id,representative_application_id)
    REFERENCES candidate_memberships(candidate_id,application_id)
    ON DELETE SET NULL (representative_application_id) DEFERRABLE INITIALLY DEFERRED;

-- One immutable parent row contains the complete member partition: no child rows can be appended later.
CREATE FUNCTION valid_candidate_merge_members(value JSONB,left_id UUID,right_id UUID) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE member JSONB; seen UUID[] := '{}'; app UUID; source UUID; left_seen BOOLEAN := false; right_seen BOOLEAN := false;
BEGIN
    IF jsonb_typeof(value) IS DISTINCT FROM 'array' OR octet_length(value::text)>1048576 THEN RETURN false; END IF;
    IF jsonb_array_length(value)<2 THEN RETURN false; END IF;
    FOR member IN SELECT jsonb_array_elements(value) LOOP
        IF jsonb_typeof(member) IS DISTINCT FROM 'object'
           OR NOT (member ?& ARRAY['applicationId','candidateId','contactRevision'])
           OR member - 'applicationId' - 'candidateId' - 'contactRevision' <> '{}'::jsonb
           OR jsonb_typeof(member->'applicationId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(member->'candidateId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(member->'contactRevision') IS DISTINCT FROM 'number'
           OR (member->>'contactRevision') !~ '^(0|[1-9][0-9]*)$' THEN RETURN false; END IF;
        app := (member->>'applicationId')::uuid; source := (member->>'candidateId')::uuid;
        IF app=ANY(seen) OR source NOT IN (left_id,right_id)
           OR (member->>'contactRevision')::numeric > 9223372036854775807 THEN RETURN false; END IF;
        seen := array_append(seen,app);
        left_seen := left_seen OR source=left_id; right_seen := right_seen OR source=right_id;
    END LOOP;
    RETURN left_seen AND right_seen;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN false;
END $$;

CREATE TABLE candidate_merge_operations (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    actor_name VARCHAR(255) NOT NULL CHECK (length(btrim(actor_name))>0),
    request_key UUID NOT NULL,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash)=32),
    left_group_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    right_group_id UUID NOT NULL REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    result_group_id UUID NOT NULL,
    left_revision_before BIGINT NOT NULL CHECK (left_revision_before>0),
    right_revision_before BIGINT NOT NULL CHECK (right_revision_before>0),
    left_revision_after BIGINT NOT NULL CHECK (left_revision_after=left_revision_before+1),
    right_revision_after BIGINT NOT NULL CHECK (right_revision_after=right_revision_before+1),
    left_representative_before UUID,
    right_representative_before UUID,
    representative_after UUID NOT NULL,
    left_head_before UUID REFERENCES candidate_merge_operations(id) ON DELETE RESTRICT,
    right_head_before UUID REFERENCES candidate_merge_operations(id) ON DELETE RESTRICT,
    members_before JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    undo_deadline TIMESTAMPTZ NOT NULL,
    UNIQUE(actor_id,request_key),
    CHECK (left_group_id<right_group_id),
    CHECK (result_group_id IN (left_group_id,right_group_id)),
    CHECK (left_head_before IS DISTINCT FROM id AND right_head_before IS DISTINCT FROM id),
    CHECK (undo_deadline=recorded_at+INTERVAL '24 hours'),
    CHECK (valid_candidate_merge_members(members_before,left_group_id,right_group_id))
);
CREATE INDEX candidate_merge_operations_left_idx ON candidate_merge_operations(left_group_id,recorded_at DESC);
CREATE INDEX candidate_merge_operations_right_idx ON candidate_merge_operations(right_group_id,recorded_at DESC);
ALTER TABLE candidate_profiles ADD CONSTRAINT candidate_merge_head
    FOREIGN KEY(merge_head_id) REFERENCES candidate_merge_operations(id) ON DELETE RESTRICT
    DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION stamp_candidate_merge_operation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT valid_candidate_merge_members(NEW.members_before,NEW.left_group_id,NEW.right_group_id) THEN
        RAISE EXCEPTION 'Invalid candidate member snapshot' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.members_before) m
                   WHERE (m->>'applicationId')::uuid=NEW.representative_after)
       OR (NEW.left_representative_before IS NOT NULL AND NOT EXISTS
           (SELECT 1 FROM jsonb_array_elements(NEW.members_before) m WHERE
             (m->>'applicationId')::uuid=NEW.left_representative_before AND (m->>'candidateId')::uuid=NEW.left_group_id))
       OR (NEW.right_representative_before IS NOT NULL AND NOT EXISTS
           (SELECT 1 FROM jsonb_array_elements(NEW.members_before) m WHERE
             (m->>'applicationId')::uuid=NEW.right_representative_before AND (m->>'candidateId')::uuid=NEW.right_group_id)) THEN
        RAISE EXCEPTION 'Invalid candidate representative snapshot' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM candidate_profiles p WHERE p.id=NEW.left_group_id
                   AND p.revision=NEW.left_revision_after AND p.merge_head_id=NEW.id
                   AND p.representative_application_id IS NOT DISTINCT FROM
                     CASE WHEN NEW.result_group_id=p.id THEN NEW.representative_after ELSE NULL END)
       OR NOT EXISTS (SELECT 1 FROM candidate_profiles p WHERE p.id=NEW.right_group_id
                   AND p.revision=NEW.right_revision_after AND p.merge_head_id=NEW.id
                   AND p.representative_application_id IS NOT DISTINCT FROM
                     CASE WHEN NEW.result_group_id=p.id THEN NEW.representative_after ELSE NULL END)
       OR (SELECT count(*) FROM candidate_memberships WHERE candidate_id IN (NEW.left_group_id,NEW.right_group_id))
            <> jsonb_array_length(NEW.members_before)
       OR EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.members_before) m WHERE NOT EXISTS
             (SELECT 1 FROM candidate_memberships current_member JOIN candidate_effective_contacts c
                ON c.application_id=current_member.application_id
              WHERE current_member.application_id=(m->>'applicationId')::uuid
                AND current_member.candidate_id=NEW.result_group_id
                AND c.contact_revision=(m->>'contactRevision')::bigint)) THEN
        RAISE EXCEPTION 'Candidate merge result does not match journal' USING ERRCODE='23514';
    END IF;
    NEW.recorded_at := clock_timestamp(); NEW.undo_deadline := NEW.recorded_at+INTERVAL '24 hours';
    RETURN NEW;
END $$;
CREATE TRIGGER candidate_merge_stamp BEFORE INSERT ON candidate_merge_operations
    FOR EACH ROW EXECUTE FUNCTION stamp_candidate_merge_operation();

-- Storage for a future undo event; execution, permission checks and deadline enforcement belong to the undo module.
CREATE TABLE candidate_merge_undo_events (
    id UUID PRIMARY KEY,
    merge_operation_id UUID NOT NULL UNIQUE REFERENCES candidate_merge_operations(id) ON DELETE RESTRICT,
    actor_id UUID NOT NULL,
    actor_name VARCHAR(255) NOT NULL CHECK (length(btrim(actor_name))>0),
    request_key UUID NOT NULL,
    payload_hash BYTEA NOT NULL CHECK (octet_length(payload_hash)=32),
    left_revision_before BIGINT NOT NULL CHECK (left_revision_before>0),
    right_revision_before BIGINT NOT NULL CHECK (right_revision_before>0),
    left_revision_after BIGINT NOT NULL CHECK (left_revision_after=left_revision_before+1),
    right_revision_after BIGINT NOT NULL CHECK (right_revision_after=right_revision_before+1),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE(actor_id,request_key)
);
CREATE FUNCTION stamp_candidate_merge_undo() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.recorded_at := clock_timestamp(); RETURN NEW; END $$;
CREATE TRIGGER candidate_merge_undo_stamp BEFORE INSERT ON candidate_merge_undo_events
    FOR EACH ROW EXECUTE FUNCTION stamp_candidate_merge_undo();

CREATE FUNCTION reject_candidate_merge_audit_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE has_history BOOLEAN;
BEGIN
    -- Empty-table maintenance cannot remove history. Once a row exists, even TRUNCATE CASCADE is rejected.
    IF TG_OP='TRUNCATE' THEN
        EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.%I)',TG_TABLE_SCHEMA,TG_TABLE_NAME) INTO has_history;
        IF NOT has_history THEN RETURN NULL; END IF;
    END IF;
    RAISE EXCEPTION 'Candidate merge audit is append-only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER candidate_merge_operations_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON candidate_merge_operations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_candidate_merge_audit_change();
CREATE TRIGGER candidate_merge_undo_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON candidate_merge_undo_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_candidate_merge_audit_change();
