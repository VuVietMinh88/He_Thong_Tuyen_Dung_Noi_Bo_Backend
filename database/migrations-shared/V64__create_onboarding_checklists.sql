-- Department templates are copied into each hire's checklist. Later template edits never change that snapshot.
CREATE TABLE onboarding_templates (
    id UUID PRIMARY KEY,
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE RESTRICT,
    name VARCHAR(120) NOT NULL CHECK (name ~ '[^[:space:]]'),
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX onboarding_templates_department_idx ON onboarding_templates(department_id,id);

CREATE TABLE onboarding_template_items (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES onboarding_templates(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL CHECK (title ~ '[^[:space:]]'),
    assignee_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    due_offset_days INTEGER NOT NULL CHECK (due_offset_days BETWEEN -30 AND 365),
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
    UNIQUE(template_id,sort_order)
);
CREATE INDEX onboarding_template_items_assignee_idx ON onboarding_template_items(assignee_id);

CREATE TABLE onboarding_checklists (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES job_applications(id) ON DELETE RESTRICT,
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE RESTRICT,
    template_id UUID NOT NULL REFERENCES onboarding_templates(id) ON DELETE RESTRICT,
    template_version BIGINT NOT NULL CHECK (template_version >= 0),
    template_name VARCHAR(120) NOT NULL CHECK (template_name ~ '[^[:space:]]'),
    start_date DATE NOT NULL CHECK (start_date BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    completed_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    CHECK ((status='COMPLETED' AND completed_at IS NOT NULL AND completed_by IS NOT NULL)
        OR (status='PENDING' AND completed_at IS NULL AND completed_by IS NULL))
);
CREATE INDEX onboarding_checklists_department_idx ON onboarding_checklists(department_id,id);
CREATE INDEX onboarding_checklists_template_idx ON onboarding_checklists(template_id);

CREATE TABLE onboarding_checklist_items (
    id UUID PRIMARY KEY,
    checklist_id UUID NOT NULL REFERENCES onboarding_checklists(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL CHECK (title ~ '[^[:space:]]'),
    assignee_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    due_date DATE NOT NULL CHECK (due_date BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','DONE')),
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
    completed_at TIMESTAMPTZ,
    completed_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    UNIQUE(checklist_id,sort_order),
    CHECK ((status='DONE' AND completed_at IS NOT NULL AND completed_by IS NOT NULL)
        OR (status<>'DONE' AND completed_at IS NULL AND completed_by IS NULL))
);
CREATE INDEX onboarding_checklist_items_assignee_idx ON onboarding_checklist_items(assignee_id);

-- A template belongs permanently to one department. Serialize direct item writes on its header as well.
CREATE FUNCTION protect_onboarding_template() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent UUID;
BEGIN
    IF TG_TABLE_NAME='onboarding_templates' THEN
        IF ROW(NEW.id,NEW.department_id,NEW.created_by,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.department_id,OLD.created_by,OLD.created_at) THEN
            RAISE EXCEPTION 'onboarding template identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.template_id) IS DISTINCT FROM ROW(OLD.id,OLD.template_id) THEN
            RAISE EXCEPTION 'onboarding template item identity is immutable' USING ERRCODE='23514';
        END IF;
        parent := CASE WHEN TG_OP='DELETE' THEN OLD.template_id ELSE NEW.template_id END;
        PERFORM id FROM onboarding_templates WHERE id=parent FOR UPDATE;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER onboarding_template_identity BEFORE UPDATE ON onboarding_templates
    FOR EACH ROW EXECUTE FUNCTION protect_onboarding_template();
CREATE TRIGGER onboarding_template_item_identity BEFORE INSERT OR UPDATE OR DELETE ON onboarding_template_items
    FOR EACH ROW EXECUTE FUNCTION protect_onboarding_template();

ALTER TABLE job_applications DROP CONSTRAINT job_applications_valid_stage;
ALTER TABLE job_applications ADD CONSTRAINT job_applications_valid_stage CHECK (stage IN
    ('NEW','SCREENING','INTERVIEW','OFFER','HIRED','ONBOARDED','REJECTED','WITHDRAWN'));
ALTER TABLE application_stage_events DROP CONSTRAINT application_stage_events_valid_stages;
ALTER TABLE application_stage_events ADD CONSTRAINT application_stage_events_valid_stages CHECK (
    stage_before IN ('NEW','SCREENING','INTERVIEW','OFFER','HIRED','ONBOARDED','REJECTED','WITHDRAWN')
    AND stage_after IN ('NEW','SCREENING','INTERVIEW','OFFER','HIRED','ONBOARDED','REJECTED','WITHDRAWN')
    AND stage_before <> stage_after);

-- Validate at commit: replacing all template items may briefly leave zero rows inside the transaction.
CREATE FUNCTION validate_onboarding_template_items() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE template UUID; item_count BIGINT;
BEGIN
    IF TG_TABLE_NAME='onboarding_templates' THEN template := NEW.id;
    ELSE template := CASE WHEN TG_OP='DELETE' THEN OLD.template_id ELSE NEW.template_id END;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM onboarding_templates WHERE id=template) THEN RETURN NULL; END IF;
    SELECT count(*) INTO item_count FROM onboarding_template_items WHERE template_id=template;
    IF item_count NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'onboarding template needs 1 to 100 items' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER onboarding_template_nonempty AFTER INSERT OR UPDATE ON onboarding_templates
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_onboarding_template_items();
CREATE CONSTRAINT TRIGGER onboarding_template_item_count AFTER INSERT OR UPDATE OR DELETE ON onboarding_template_items
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_onboarding_template_items();

CREATE FUNCTION protect_onboarding_checklist() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.status='COMPLETED' THEN
            RAISE EXCEPTION 'completed onboarding is immutable' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.status='COMPLETED' AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'completed onboarding is immutable' USING ERRCODE='23514';
        END IF;
        IF ROW(NEW.id,NEW.application_id,NEW.department_id,NEW.template_id,NEW.template_version,
            NEW.template_name,NEW.start_date,NEW.created_by,NEW.created_at)
            IS DISTINCT FROM ROW(OLD.id,OLD.application_id,OLD.department_id,OLD.template_id,OLD.template_version,
            OLD.template_name,OLD.start_date,OLD.created_by,OLD.created_at) THEN
            RAISE EXCEPTION 'onboarding snapshot identity is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER onboarding_checklist_identity BEFORE UPDATE OR DELETE ON onboarding_checklists
    FOR EACH ROW EXECUTE FUNCTION protect_onboarding_checklist();

-- Lock the aggregate before changing any item, including direct SQL writers. Final snapshots cannot be edited.
CREATE FUNCTION protect_onboarding_checklist_item() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent UUID; parent_status VARCHAR(20);
BEGIN
    parent := CASE WHEN TG_OP='DELETE' THEN OLD.checklist_id ELSE NEW.checklist_id END;
    IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.checklist_id,NEW.sort_order)
        IS DISTINCT FROM ROW(OLD.id,OLD.checklist_id,OLD.sort_order) THEN
        RAISE EXCEPTION 'onboarding item identity is immutable' USING ERRCODE='23514';
    END IF;
    SELECT status INTO parent_status FROM onboarding_checklists WHERE id=parent FOR UPDATE;
    IF parent_status='COMPLETED' THEN
        RAISE EXCEPTION 'completed onboarding items are immutable' USING ERRCODE='23514';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER onboarding_item_final BEFORE INSERT OR UPDATE OR DELETE ON onboarding_checklist_items
    FOR EACH ROW EXECUTE FUNCTION protect_onboarding_checklist_item();

-- The final item, checklist and application are changed together. Deferred validation accepts either SQL order
-- but rejects empty completion, a missing checklist, a completed checklist with open items, or a partial commit.
CREATE FUNCTION validate_onboarding_completion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE application UUID; checklist onboarding_checklists%ROWTYPE; app_stage VARCHAR(20);
    app_department UUID; total BIGINT; done BIGINT;
BEGIN
    IF TG_TABLE_NAME='job_applications' THEN application := NEW.id;
    ELSIF TG_TABLE_NAME='onboarding_checklists' THEN application := NEW.application_id;
    ELSE
        SELECT application_id INTO application FROM onboarding_checklists
            WHERE id=CASE WHEN TG_OP='DELETE' THEN OLD.checklist_id ELSE NEW.checklist_id END;
    END IF;
    IF application IS NULL THEN RETURN NULL; END IF;
    SELECT a.stage,r.department_id INTO app_stage,app_department FROM job_applications a
        JOIN recruitment_requisitions r ON r.id=a.requisition_id WHERE a.id=application;
    SELECT * INTO checklist FROM onboarding_checklists WHERE application_id=application;
    IF NOT FOUND THEN
        IF app_stage='ONBOARDED' THEN
            RAISE EXCEPTION 'onboarded application needs a completed checklist' USING ERRCODE='23514';
        END IF;
        RETURN NULL;
    END IF;
    SELECT count(*),count(*) FILTER (WHERE status='DONE') INTO total,done
        FROM onboarding_checklist_items WHERE checklist_id=checklist.id;
    IF total NOT BETWEEN 1 AND 100 OR (checklist.status='COMPLETED') IS DISTINCT FROM (done=total)
        OR app_stage IS DISTINCT FROM (CASE WHEN checklist.status='COMPLETED' THEN 'ONBOARDED' ELSE 'HIRED' END)
        OR checklist.department_id IS DISTINCT FROM app_department
        OR NOT EXISTS (SELECT 1 FROM onboarding_templates t WHERE t.id=checklist.template_id
            AND t.department_id=checklist.department_id)
        OR EXISTS (SELECT 1 FROM onboarding_checklist_items i WHERE i.checklist_id=checklist.id
            AND i.due_date NOT BETWEEN checklist.start_date-30 AND checklist.start_date+365) THEN
        RAISE EXCEPTION 'invalid onboarding checklist completion or snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER onboarding_checklist_complete AFTER INSERT OR UPDATE ON onboarding_checklists
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_onboarding_completion();
CREATE CONSTRAINT TRIGGER onboarding_item_complete AFTER INSERT OR UPDATE OR DELETE ON onboarding_checklist_items
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_onboarding_completion();
CREATE CONSTRAINT TRIGGER onboarding_application_complete AFTER INSERT OR UPDATE ON job_applications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_onboarding_completion();

CREATE FUNCTION protect_onboarded_application_stage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.stage='ONBOARDED' THEN
        RAISE EXCEPTION 'onboarding must start from a hired application' USING ERRCODE='23514';
    ELSIF TG_OP='UPDATE' THEN
        IF (NEW.stage='ONBOARDED' AND OLD.stage NOT IN ('HIRED','ONBOARDED'))
            OR (OLD.stage='ONBOARDED' AND NEW.stage<>'ONBOARDED') THEN
            RAISE EXCEPTION 'invalid onboarding stage transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER job_applications_onboarding_stage BEFORE INSERT OR UPDATE OF stage ON job_applications
    FOR EACH ROW EXECUTE FUNCTION protect_onboarded_application_stage();
