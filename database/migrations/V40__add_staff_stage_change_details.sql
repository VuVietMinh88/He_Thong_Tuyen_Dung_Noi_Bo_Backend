-- Task 381 (story S5-03, "Chuyển sang Loại bắt buộc chọn lý do từ danh mục; không cho phép bỏ qua giai đoạn Phỏng
-- vấn khi đi thẳng tới Đề xuất offer, trừ khi Trưởng phòng Nhân sự xác nhận"): what a staff stage change decided, on
-- its V34 history row, written in the same transaction as the stage.
-- A rejection names one REJECTION_REASON catalog value (V10): its id, RESTRICT like every catalog reference, so a value
-- a rejection still uses can only be deactivated, never deleted; and its name as shown when the candidate was rejected,
-- so renaming the value later does not change what the history says. The catalog type is checked by the service under
-- a FOR SHARE lock of the value (a plain foreign key cannot name the type).
-- interview_skipped is the HR exception: SCREENING straight to OFFER, which only a confirming HR_MANAGER may do; every
-- such row says so, and no other row may.
-- Existing rows (the candidates' withdrawals) get no reason and false; V34's trigger keeps every row as written.
ALTER TABLE application_stage_events
    ADD COLUMN rejection_reason_id UUID REFERENCES recruitment_catalog_items(id) ON DELETE RESTRICT,
    ADD COLUMN rejection_reason_name VARCHAR(255),
    ADD COLUMN interview_skipped BOOLEAN NOT NULL DEFAULT FALSE;
-- Exactly the staff rejections carry a reason, with both its id and a visible name (V10's name rule).
ALTER TABLE application_stage_events ADD CONSTRAINT application_stage_events_rejection_reason CHECK (
    CASE WHEN actor_type = 'STAFF' AND stage_after = 'REJECTED'
         THEN rejection_reason_id IS NOT NULL AND rejection_reason_name IS NOT NULL
              AND rejection_reason_name = btrim(rejection_reason_name) AND rejection_reason_name <> ''
         ELSE rejection_reason_id IS NULL AND rejection_reason_name IS NULL END);
ALTER TABLE application_stage_events ADD CONSTRAINT application_stage_events_interview_skip CHECK (
    interview_skipped = (stage_before = 'SCREENING' AND stage_after = 'OFFER'));
-- Serves the RESTRICT check when HR deletes a catalog value.
CREATE INDEX application_stage_events_rejection_reason_idx ON application_stage_events(rejection_reason_id)
    WHERE rejection_reason_id IS NOT NULL;
