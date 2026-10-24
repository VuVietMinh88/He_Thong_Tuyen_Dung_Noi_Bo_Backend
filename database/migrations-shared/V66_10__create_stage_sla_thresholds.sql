-- Task 418 (story S5-10, "Cấu hình ngưỡng SLA cho từng giai đoạn, ví dụ hồ sơ mới phải sàng lọc trong 3 ngày"): the
-- longest an application may stay in each active stage, in whole calendar days. One row per active stage; a NULL
-- max_days means no SLA for that stage (nothing is seeded: HR decides the values). Final stages (HIRED, ONBOARDED,
-- REJECTED, WITHDRAWN) have no SLA by construction.
CREATE TABLE stage_sla_thresholds (
    stage VARCHAR(20) PRIMARY KEY,
    max_days INTEGER,
    version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID REFERENCES user_accounts(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT stage_sla_thresholds_stage CHECK (stage IN ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER')),
    CONSTRAINT stage_sla_thresholds_days CHECK (max_days IS NULL OR max_days BETWEEN 1 AND 365),
    CONSTRAINT stage_sla_thresholds_version CHECK (version >= 0)
);
INSERT INTO stage_sla_thresholds(stage, updated_at) VALUES
    ('NEW', clock_timestamp()), ('SCREENING', clock_timestamp()), ('INTERVIEW', clock_timestamp()),
    ('OFFER', clock_timestamp());
