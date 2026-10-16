-- Competency frameworks (Jira TKNHTTDNB1-211, story S2-06). A framework is a reusable set of weighted
-- evaluation criteria; interview evaluation forms will be generated from it in Sprint 6.
-- DRAFT means the weights may still be incomplete. The API (later tasks) must check that an ACTIVE framework's
-- weights add up to exactly 100.00 and that only ACTIVE frameworks are assigned to positions.
CREATE TABLE competency_frameworks (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_competency_framework_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT valid_competency_framework_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT valid_competency_framework_description CHECK (
        description IS NULL OR (description <> '' AND description !~ '^[[:space:]]|[[:space:]]$')
    ),
    CONSTRAINT valid_competency_framework_status CHECK (status IN ('DRAFT', 'ACTIVE'))
);

-- Weight is a percentage with two decimals, e.g. 33.33. NUMERIC keeps it exact; never use a floating point type.
-- PostgreSQL rounds a third decimal (33.335 becomes 33.34) instead of rejecting it, so the API must reject it first.
-- Both UNIQUE constraints are checked at COMMIT (DEFERRABLE INITIALLY DEFERRED). One transaction can therefore swap
-- the sort order of two criteria, or rename them crosswise, without a temporary duplicate failing the update.
-- COMMIT runs after the service method has returned, so a write API must lock the framework row first and end with
-- CompetencyCriterionRepository.checkUniqueConstraintsNow(), which reports a duplicate while it can still be caught.
CREATE TABLE competency_criteria (
    id UUID PRIMARY KEY,
    framework_id UUID NOT NULL REFERENCES competency_frameworks(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    weight NUMERIC(5, 2) NOT NULL,
    sort_order INTEGER NOT NULL,
    CONSTRAINT valid_competency_criterion_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT valid_competency_criterion_description CHECK (
        description IS NULL OR (description <> '' AND description !~ '^[[:space:]]|[[:space:]]$')
    ),
    CONSTRAINT valid_competency_criterion_weight CHECK (weight > 0 AND weight <= 100),
    CONSTRAINT valid_competency_criterion_sort_order CHECK (sort_order >= 1),
    CONSTRAINT competency_criteria_framework_name_key UNIQUE (framework_id, name) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT competency_criteria_framework_sort_order_key UNIQUE (framework_id, sort_order)
        DEFERRABLE INITIALLY DEFERRED
);

-- Many positions may point to the same framework, so its criteria are stored once and never copied.
-- NULL keeps every existing position valid without a framework. RESTRICT blocks deleting a framework in use.
ALTER TABLE positions
    ADD COLUMN competency_framework_id UUID REFERENCES competency_frameworks(id) ON DELETE RESTRICT;

CREATE INDEX positions_competency_framework_id_idx ON positions(competency_framework_id);
