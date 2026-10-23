-- Task 408 (story S5-08, "Ứng viên thêm tay vẫn đi qua đúng luồng phát hiện trùng"): the duplicate check of a manual
-- entry looks for other applications with the same phone in its national form ("+84..." read as "0..."); this
-- expression index serves exactly that comparison (ManualApplicationDuplicates.NATIONAL_PHONE). No data changes.
CREATE INDEX job_applications_national_phone_idx ON job_applications
    ((CASE WHEN phone LIKE '+84%' THEN '0' || substr(phone, 4) ELSE phone END));
