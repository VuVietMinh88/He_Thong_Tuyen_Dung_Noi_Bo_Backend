CREATE TABLE approval_flows (
    id UUID PRIMARY KEY,
    department_id UUID NOT NULL UNIQUE REFERENCES departments(id) ON DELETE RESTRICT,
    current_version INTEGER NOT NULL CHECK (current_version > 0)
);

CREATE TABLE approval_flow_versions (
    flow_id UUID NOT NULL REFERENCES approval_flows(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL CHECK (version > 0),
    name VARCHAR(120) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (flow_id, version)
);

ALTER TABLE approval_flows ADD CONSTRAINT approval_flows_current_version_fk
    FOREIGN KEY (id, current_version) REFERENCES approval_flow_versions(flow_id, version)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE approval_flow_steps (
    flow_id UUID NOT NULL,
    version INTEGER NOT NULL,
    position SMALLINT NOT NULL CHECK (position BETWEEN 1 AND 20),
    -- NULL means always required; other steps apply strictly above the threshold in VND.
    salary_threshold NUMERIC(15,0) CHECK (salary_threshold > 0),
    approver_user_id UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    approver_role VARCHAR(32) REFERENCES roles(code) ON DELETE RESTRICT,
    PRIMARY KEY (flow_id, version, position),
    FOREIGN KEY (flow_id, version) REFERENCES approval_flow_versions(flow_id, version) ON DELETE RESTRICT,
    CHECK ((approver_user_id IS NOT NULL) <> (approver_role IS NOT NULL)),
    CHECK (approver_role IS NULL OR approver_role <> 'CANDIDATE')
);
CREATE UNIQUE INDEX approval_step_unique_user ON approval_flow_steps(flow_id, version, approver_user_id)
    WHERE approver_user_id IS NOT NULL;
CREATE UNIQUE INDEX approval_step_unique_role ON approval_flow_steps(flow_id, version, approver_role)
    WHERE approver_role IS NOT NULL;

CREATE FUNCTION guard_approval_steps() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Approval steps are immutable' USING ERRCODE = '23514';
    END IF;
    -- Serialize step insertion and publication of this version.
    PERFORM 1 FROM approval_flow_versions WHERE flow_id=NEW.flow_id AND version=NEW.version
        AND NOT sealed FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Approval version is missing or sealed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER approval_steps_immutable BEFORE INSERT OR UPDATE OR DELETE ON approval_flow_steps
    FOR EACH ROW EXECUTE FUNCTION guard_approval_steps();

CREATE FUNCTION guard_approval_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE n INTEGER; max_position INTEGER;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.sealed THEN
            RAISE EXCEPTION 'Insert an unsealed version before adding steps' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' OR OLD.sealed THEN
        RAISE EXCEPTION 'Approval versions are immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT NEW.sealed OR (NEW.flow_id,NEW.version,NEW.name,NEW.created_by,NEW.created_at)
        IS DISTINCT FROM (OLD.flow_id,OLD.version,OLD.name,OLD.created_by,OLD.created_at) THEN
        RAISE EXCEPTION 'Only publication of a complete version is allowed' USING ERRCODE = '23514';
    END IF;
    SELECT count(*), max(position) INTO n,max_position FROM approval_flow_steps
        WHERE flow_id=NEW.flow_id AND version=NEW.version;
    IF n = 0 OR n <> max_position OR NOT EXISTS (
        SELECT 1 FROM approval_flow_steps WHERE flow_id=NEW.flow_id AND version=NEW.version
            AND position=1 AND salary_threshold IS NULL
    ) OR EXISTS (
        SELECT 1 FROM approval_flow_steps a JOIN approval_flow_steps b
            ON a.flow_id=b.flow_id AND a.version=b.version AND b.position=a.position+1
        WHERE a.flow_id=NEW.flow_id AND a.version=NEW.version AND a.salary_threshold IS NOT NULL
            AND (b.salary_threshold IS NULL OR b.salary_threshold < a.salary_threshold)
    ) THEN
        RAISE EXCEPTION 'Approval chain requires a base step and ordered thresholds' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER approval_versions_immutable BEFORE INSERT OR UPDATE OR DELETE ON approval_flow_versions
    FOR EACH ROW EXECUTE FUNCTION guard_approval_version();

CREATE FUNCTION require_sealed_approval_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM approval_flow_versions WHERE flow_id=NEW.flow_id AND version=NEW.version AND NOT sealed) THEN
        RAISE EXCEPTION 'Incomplete approval version cannot be committed' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER approval_version_complete AFTER INSERT ON approval_flow_versions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_sealed_approval_version();

CREATE FUNCTION guard_approval_flow() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.current_version <> 1 THEN
            RAISE EXCEPTION 'First approval version must be 1' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.id <> OLD.id OR NEW.department_id <> OLD.department_id
            OR NEW.current_version <> OLD.current_version + 1 THEN
            RAISE EXCEPTION 'Approval scope is immutable and versions must advance by one' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER approval_flow_version_order BEFORE INSERT OR UPDATE ON approval_flows
    FOR EACH ROW EXECUTE FUNCTION guard_approval_flow();
