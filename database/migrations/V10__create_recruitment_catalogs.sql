-- Shared recruitment catalogs: candidate sources, rejection reasons, work locations and employment types.
-- One table holds all four catalog types. HR enters the values, so this migration seeds no rows.
CREATE TABLE recruitment_catalog_items (
    id UUID PRIMARY KEY,
    catalog_type VARCHAR(32) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- The same code may exist in two catalog types, but only once inside one type.
    CONSTRAINT recruitment_catalog_items_type_code_key UNIQUE (catalog_type, code),
    CONSTRAINT valid_recruitment_catalog_type CHECK (catalog_type IN (
        'CANDIDATE_SOURCE', 'REJECTION_REASON', 'WORK_LOCATION', 'EMPLOYMENT_TYPE'
    )),
    CONSTRAINT valid_recruitment_catalog_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT valid_recruitment_catalog_name CHECK (name = btrim(name) AND name <> ''),
    -- Smaller numbers are shown first. Equal numbers are allowed so reordering never hits a unique conflict.
    CONSTRAINT non_negative_recruitment_catalog_sort_order CHECK (sort_order >= 0)
);
