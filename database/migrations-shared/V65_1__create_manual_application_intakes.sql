-- Task 407 (story S5-08, "Nhập tay thông tin và tải CV cho ứng viên tìm được từ nguồn khác"): who entered an
-- application by hand, and when. The application itself, its CV and its candidate link are written by the shared
-- ApplicationStorageService exactly like a public submission, so every rule of job_applications still applies. The
-- row goes with its application (CASCADE); the staff reference stays RESTRICT like every staff trail.
CREATE TABLE manual_application_intakes (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    entered_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    entered_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX manual_application_intakes_actor_idx ON manual_application_intakes(entered_by);
