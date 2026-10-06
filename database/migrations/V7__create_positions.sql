-- Position catalog with an approved salary band in VND (whole dong, BIGINT).
-- The band is the company-approved limit that offer approval will compare against later.
CREATE TABLE positions (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    level VARCHAR(50) NOT NULL,
    salary_min BIGINT NOT NULL,
    salary_max BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_position_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT valid_position_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT valid_position_level CHECK (level = btrim(level) AND level <> ''),
    CONSTRAINT non_negative_position_salary_min CHECK (salary_min >= 0),
    CONSTRAINT valid_position_salary_range CHECK (salary_min <= salary_max)
);
