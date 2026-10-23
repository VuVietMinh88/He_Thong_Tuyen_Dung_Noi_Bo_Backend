-- Task 413 (story S5-09, "Hồ sơ mới được gắn điểm phù hợp và cờ Đạt hoặc Không đạt tiêu chí bắt buộc; điểm chỉ để gợi ý
-- sắp xếp, không tự động loại ứng viên").
-- What a staff member recorded about an application after reading its CV. Nothing is extracted from CV files
-- automatically: an application without this row is evaluated as UNKNOWN, never given invented values. version grows
-- with every save, so a fit result names the qualifications it was computed from (0 = none recorded). Skills count as
-- recorded only when skills_recorded: an empty recorded list means "no skill", unrecorded skills stay UNKNOWN.
CREATE TABLE application_qualifications (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    experience_years NUMERIC(3,1),
    education_level VARCHAR(16),
    skills_recorded BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    recorded_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT application_qualifications_experience CHECK (experience_years IS NULL
        OR experience_years BETWEEN 0 AND 60),
    CONSTRAINT application_qualifications_education CHECK (education_level IS NULL OR education_level IN
        ('HIGH_SCHOOL', 'COLLEGE', 'BACHELOR', 'MASTER', 'DOCTORATE')),
    CONSTRAINT application_qualifications_version CHECK (version >= 1)
);
CREATE INDEX application_qualifications_recorded_by_idx ON application_qualifications(recorded_by);

CREATE TABLE application_skills (
    application_id UUID NOT NULL REFERENCES application_qualifications(application_id) ON DELETE CASCADE,
    skill_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(60) NOT NULL,
    sort_order SMALLINT NOT NULL,
    PRIMARY KEY (application_id, skill_key),
    CONSTRAINT application_skills_display CHECK (display_name = btrim(display_name) AND display_name <> ''),
    CONSTRAINT application_skills_key CHECK (skill_key = btrim(skill_key) AND skill_key <> ''),
    CONSTRAINT application_skills_order CHECK (sort_order BETWEEN 0 AND 49),
    CONSTRAINT application_skills_application_order_key UNIQUE (application_id, sort_order)
);

-- The fit of an application against its position's criteria, written for every new application and every
-- qualifications save. The versions name the criteria and qualifications it was computed from: after a criteria save
-- the row is stale (readers evaluate it live) until the refresh worker rewrites it. The score is advice for sorting
-- only: nothing reads it to change a stage.
CREATE TABLE application_fit_results (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    position_id UUID NOT NULL REFERENCES positions(id) ON DELETE CASCADE,
    criteria_version BIGINT NOT NULL,
    qualifications_version BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    required_met BOOLEAN NOT NULL,
    score SMALLINT,
    details JSONB NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_fit_results_status CHECK (status IN ('MET', 'NOT_MET', 'UNKNOWN', 'NO_CRITERIA')),
    CONSTRAINT application_fit_results_met CHECK (required_met = (status = 'MET')),
    CONSTRAINT application_fit_results_score CHECK (
        (status = 'NO_CRITERIA' AND score IS NULL) OR (status <> 'NO_CRITERIA' AND score BETWEEN 0 AND 100)),
    CONSTRAINT application_fit_results_versions CHECK (criteria_version >= 0 AND qualifications_version >= 0),
    CONSTRAINT application_fit_results_details CHECK (jsonb_typeof(details) = 'array')
);
CREATE INDEX application_fit_results_position_idx ON application_fit_results(position_id);

-- Applications stored before this migration: no position has criteria yet on an upgrade (V65_3), so their result is
-- NO_CRITERIA. A position that already has criteria (a database that ran V65_3 earlier) is left without rows; its
-- applications are evaluated live until the next change recomputes them.
INSERT INTO application_fit_results(application_id, position_id, criteria_version, qualifications_version, status,
    required_met, score, details, evaluated_at)
SELECT a.id, r.position_id, 0, 0, 'NO_CRITERIA', FALSE, NULL, '[]'::jsonb, now()
FROM job_applications a JOIN recruitment_requisitions r ON r.id = a.requisition_id
WHERE NOT EXISTS (SELECT 1 FROM position_required_criteria c WHERE c.position_id = r.position_id);
