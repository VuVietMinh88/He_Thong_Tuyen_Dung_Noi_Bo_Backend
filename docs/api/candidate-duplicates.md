# 367 — Phát hiện ứng viên trùng email hoặc số điện thoại

Backend chỉ cảnh báo, không tự gộp. Thiết kế chung: [candidate identity](../architecture/candidate-identity-design.md).

## API

`GET /api/v1/applications/{id}/duplicate-candidates?page=0&size=20`

Cần Bearer token và CANDIDATES_READ_ALL/SCOPED. Kiểm account/session/token, grant và scope từ dữ liệu hiện tại.
SCOPED: requisition được giao chính/hỗ trợ; hiring manager có READ_SCOPED được xem cây phòng ban quản lý.
Quyền REQUISITIONS/JOB_POSTINGS không thay thế CANDIDATES. Chưa đăng nhập401, thiếu quyền/ngoài scope403.

Response200 no-store:

```json
{
  "applicationId": "<UUID hồ sơ đang xem>",
  "contactRevision": 0,
  "detectionStatus": "AVAILABLE",
  "indexedAt": "2026-10-16T08:00:00Z",
  "items": [
    {
      "applicationId": "<UUID hồ sơ được phép xem>",
      "requisitionId": "<UUID yêu cầu tuyển dụng>",
      "postingTitle": "Lập trình viên",
      "contact": {"fullName": "Nguyễn Minh Anh", "email": "anh@example.test", "phone": "0912345678"},
      "contactRevision": 1,
      "matchedBy": ["EMAIL", "PHONE"],
      "detectedAt": "2026-10-16T08:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "hasMore": false,
  "restrictedWarning": null
}
```

- PENDING: chưa lập chỉ mục liên hệ/nhóm hiện tại; indexedAt=null. Poll lại sau.
- AVAILABLE: nguồn hiện tại đã lập chỉ mục. Vẫn có thể xuất hiện kết quả mới khi worker xử lý hồ sơ khác.
  Không hiểu AVAILABLE hoặc items rỗng là bảo đảm không có hồ sơ trùng trong toàn hệ thống.
- matchedBy có EMAIL, PHONE hoặc cả hai. Không có source OCR chưa xác nhận.
- Chỉ trả application trong phạm vi. Nếu có trùng ngoài phạm vi, restrictedWarning chỉ chứa:
  “Có hồ sơ có thể trùng ngoài phạm vi phụ trách”. Không có ID/PII/vị trí/count của hồ sơ ẩn.
- Phân trang và hasMore chỉ tính kết quả nhìn thấy. Không có totalElements hoặc candidateId.
- page>=0, size1..100, page*size<=1000000. Sắp detectedAt giảm dần, applicationId tăng dần.
- Application không tồn tại404 APPLICATION_NOT_FOUND; phân trang sai400 VALIDATION_ERROR.
- Tắt tính năng hoặc DB bận/lỗi503 DUPLICATE_DETECTION_UNAVAILABLE; lỗi không chứa SQL/PII.

## Cách so khớp

Email HOẶC phone trùng. Email trim/lowercase Locale.ROOT; giữ dấu chấm/+tag. Phone chỉ bỏ separator,
giữ số0/dấu+/mã nước:0912345678 KHÁC +84912345678. Thông tin xác nhận ưu tiên bản nộp theo từng application.
Bao gồm hồ sơ đã loại/rút; không tự gộp theo chuỗi A-B-C. Cặp cùng nhóm không xuất hiện.
Sau khi sửa contact, API bỏ cảnh báo cũ ngay; worker sẽ tìm cặp mới. Không thay email chống nộp trùng,
CV, stage, mã tra cứu, email đã queue/gửi hoặc receipt.

## Cấu hình và vận hành

- Migration V31 thêm nhóm/membership, contact index, cặp trùng và view nguồn nội bộ; không sửa migration cũ.
- `CANDIDATE_DUPLICATES_ENABLED=false` mặc định: API503 với hồ sơ có quyền, worker không làm việc.
- `CANDIDATE_DUPLICATES_BATCH_SIZE=25`, hợp lệ1..100; `CANDIDATE_DUPLICATES_POLL_DELAY_MS=5000`.
- Khi bật, worker xử lý cả dữ liệu cũ và dữ liệu phát sinh. Không có backfill ghi đè dữ liệu gốc trong migration.
- Không có endpoint công khai để chạy detector hoặc xem người khác. Không có API merge/undo trong367.
- Scheduler xử lý một batch mỗi poll; nhiều instance chia advisory lock. Lỗi retry ở poll tiếp theo.
  Theo dõi log cảnh báo chung và backlog; điều chỉnh batch/poll theo tải triển khai thực tế.
- API/nộp hồ sơ và receipt không gọi detector. DB bảng detector lỗi không làm hỏng nộp hồ sơ.
  Scheduler và chỉ mục vẫn dùng tài nguyên database chung; cần đo tải trước khi bật production.

## Kiểm chứng

ContactMatchKeysTest kiểm normalization chính xác và redaction.
CandidateDuplicateIntegrationTest kiểm HTTP/DB, phạm vi/ẩn dữ liệu, contact đổi, OCR chưa xác nhận,
membership, backfill/late commit, rollback batch, worker contention và intake khi detector lỗi.
