-- Task 573 (story S8-08): recruitment costs entered by hand (job advertising and referral bonuses), per requisition
-- and date, for cost per hire. An entry is never edited: a wrong entry is voided (who, when, why) and entered again,
-- so the ledger keeps every amount anyone ever reported. Nothing deletes an entry: there is no HTTP delete, a trigger
-- refuses DELETE (like the append-only histories of V20 and V33), and the requisition cannot be deleted while it has
-- entries (RESTRICT). Test fixtures empty the table with TRUNCATE, which fires no row trigger.
CREATE TABLE recruitment_costs (
    id UUID PRIMARY KEY,
    requisition_id UUID NOT NULL REFERENCES recruitment_requisitions(id) ON DELETE RESTRICT,
    cost_type VARCHAR(20) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    incurred_on DATE NOT NULL,
    description VARCHAR(500),
    recorded_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    recorded_at TIMESTAMPTZ NOT NULL,
    voided_at TIMESTAMPTZ,
    voided_by UUID REFERENCES user_accounts(id) ON DELETE RESTRICT,
    void_reason VARCHAR(500),
    CONSTRAINT recruitment_costs_type CHECK (cost_type IN ('ADVERTISING', 'REFERRAL_BONUS')),
    CONSTRAINT recruitment_costs_amount CHECK (amount_vnd BETWEEN 1 AND 100000000000),
    CONSTRAINT recruitment_costs_date CHECK (incurred_on BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
    CONSTRAINT recruitment_costs_description CHECK (
        description IS NULL OR (description = btrim(description) AND description <> '')),
    CONSTRAINT recruitment_costs_void CHECK (
        (voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
        OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL
            AND void_reason = btrim(void_reason) AND void_reason <> '' AND voided_at >= recorded_at))
);
CREATE INDEX recruitment_costs_requisition_idx ON recruitment_costs (requisition_id, incurred_on);
CREATE INDEX recruitment_costs_incurred_idx ON recruitment_costs (incurred_on, requisition_id) WHERE voided_at IS NULL;
CREATE INDEX recruitment_costs_recorded_by_idx ON recruitment_costs (recorded_by);
CREATE INDEX recruitment_costs_voided_by_idx ON recruitment_costs (voided_by) WHERE voided_by IS NOT NULL;

-- The only change an entry accepts is being voided, once; every recorded value stays as it was entered.
CREATE FUNCTION protect_recruitment_cost() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.requisition_id, NEW.cost_type, NEW.amount_vnd, NEW.incurred_on, NEW.description,
           NEW.recorded_by, NEW.recorded_at)
        IS DISTINCT FROM ROW(OLD.id, OLD.requisition_id, OLD.cost_type, OLD.amount_vnd, OLD.incurred_on,
           OLD.description, OLD.recorded_by, OLD.recorded_at) THEN
        RAISE EXCEPTION 'recruitment cost entries are never changed; void and enter again' USING ERRCODE = '23514';
    END IF;
    IF OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION 'a voided recruitment cost entry is final' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER recruitment_costs_entry_immutable BEFORE UPDATE ON recruitment_costs
    FOR EACH ROW EXECUTE FUNCTION protect_recruitment_cost();

CREATE FUNCTION reject_recruitment_cost_delete() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'recruitment_costs is append-only: % is not allowed', TG_OP USING ERRCODE = '23514';
END $$;
CREATE TRIGGER recruitment_costs_append_only BEFORE DELETE ON recruitment_costs
    FOR EACH ROW EXECUTE FUNCTION reject_recruitment_cost_delete();
