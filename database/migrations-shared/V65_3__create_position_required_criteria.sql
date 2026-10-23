-- Task 412 (story S5-09, "Khai báo tiêu chí bắt buộc cho vị trí: số năm kinh nghiệm, bằng cấp, kỹ năng"): the required
-- criteria of a catalog position, normalized: a minimum number of years, a minimum education level and a set of
-- skills. version starts at 1 and grows with every save, so a fit result names the criteria it was computed from.
-- The rows go with their position (CASCADE); the staff reference stays RESTRICT like every staff trail.
CREATE TABLE position_required_criteria (
    position_id UUID PRIMARY KEY REFERENCES positions(id) ON DELETE CASCADE,
    min_experience_years SMALLINT,
    required_education VARCHAR(16),
    updated_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT position_required_criteria_experience CHECK (min_experience_years IS NULL
        OR min_experience_years BETWEEN 0 AND 50),
    CONSTRAINT position_required_criteria_education CHECK (required_education IS NULL OR required_education IN
        ('HIGH_SCHOOL', 'COLLEGE', 'BACHELOR', 'MASTER', 'DOCTORATE')),
    CONSTRAINT position_required_criteria_version CHECK (version >= 1)
);
CREATE INDEX position_required_criteria_updated_by_idx ON position_required_criteria(updated_by);

-- One row per required skill; key is the case- and accent-insensitive form that decides whether two skills match.
CREATE TABLE position_required_skills (
    position_id UUID NOT NULL REFERENCES position_required_criteria(position_id) ON DELETE CASCADE,
    skill_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    sort_order SMALLINT NOT NULL,
    PRIMARY KEY (position_id, skill_key),
    CONSTRAINT position_required_skills_display CHECK (display_name = btrim(display_name) AND display_name <> ''),
    CONSTRAINT position_required_skills_key CHECK (skill_key = btrim(skill_key) AND skill_key <> ''),
    CONSTRAINT position_required_skills_order CHECK (sort_order BETWEEN 0 AND 19),
    CONSTRAINT position_required_skills_position_order_key UNIQUE (position_id, sort_order)
);
