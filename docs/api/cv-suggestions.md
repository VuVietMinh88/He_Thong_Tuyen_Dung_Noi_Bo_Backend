# Gợi ý dữ liệu CV cho recruiter

Backend xử lý CV của hồ sơ đã lưu. Recruiter chủ động yêu cầu; không upload file tại API này,
không tự chạy sau khi ứng viên nộp. Kết quả chỉ là gợi ý, không ghi đè hồ sơ hay email chống trùng.
Lưu xác nhận/sửa chưa thuộc chức năng này; story hiển thị/xác nhận chưa hoàn tất toàn bộ.

API xác nhận/sửa riêng: [confirmed-contacts](confirmed-contacts.md). API gợi ý không tự ghi đè
bản đã xác nhận. Phần frontend/hồ sơ hợp nhất được tích hợp riêng.

## API

`POST /api/v1/applications/{applicationId}/cv-extractions` không cần body.

- Cần đăng nhập, cả quyền đọc và ghi `CANDIDATES`. Nếu một quyền có scope SCOPED thì chỉ hồ sơ
  thuộc requisition đang phân công chính/hỗ trợ cho người gọi. Quyền REQUISITIONS không thay thế.
- `202 Accepted`: đã tạo job hoặc tái dùng job QUEUED/RUNNING. `Location` trỏ đến API đọc job.
- `200 OK`: dùng lại kết quả SUCCEEDED theo application + SHA-256 CV + phiên bản bộ trích.
- DOCX trả ngay SUCCEEDED với outcome UNSUPPORTED/PDF_REQUIRED; không tiêu tốn slot OCR.
- FAILED có thể yêu cầu lại: tối đa 3 lần thử lại trong cửa sổ trượt 60 phút/hồ sơ,
  dùng đồng hồ DB, tính chung mọi recruiter và phiên bản. Lần chạy đầu không tính retry.
  Gọi lại khi đã QUEUED/RUNNING/SUCCEEDED không tính thêm lượt.

`GET /api/v1/applications/{applicationId}/cv-extractions/{extractionId}` trả `200 OK`.
Kiểm tra quyền đọc CANDIDATES và phân công hiện tại mỗi lần. Job phải thuộc đúng hồ sơ;
không dùng quyền của người tạo job để thay thế quyền của người đọc.
Không trả kết quả cũ nếu CV hoặc phiên bản bộ trích đã đổi. Có thể xem job đã tạo khi tắt tạo mới.

Các response thành công/lỗi ứng dụng có `Cache-Control: no-store`. Kết quả có PII:
không log response, không cache chung, không coi văn bản OCR là HTML an toàn.

Ví dụ khi đang chờ:

```json
{
  "id": "<uuid>",
  "applicationId": "<uuid>",
  "extractorVersion": "pdf-contact-v2",
  "status": "QUEUED",
  "attempt": 1,
  "createdAt": "2026-10-15T08:00:00Z",
  "updatedAt": "2026-10-15T08:00:00Z",
  "result": null,
  "errorCode": null
}
```

Job SUCCEEDED có `result` gồm extractorVersion, outcome, processedPages, totalPages,
suggestions và warnings. Mỗi trường fullName/email/phone là danh sách candidate:
value, normalizedValue, reason, page, source (TEXT/OCR), preferred. Nếu mơ hồ thì không tự chọn.
Outcome COMPLETE/PARTIAL/NO_DATA/UNSUPPORTED đều là kết quả hoàn tất, tái dùng được.
FAILED không có result; errorCode chỉ là mã cố định, không chứa CV/SQL/đường dẫn/nội dung lỗi hệ thống.

## Trạng thái và giới hạn

QUEUED → RUNNING → SUCCEEDED hoặc FAILED. Thử lại thủ công tạo attempt mới trên cùng job,
giữ lịch sử attempt cũ. Không tự retry OCR, kể cả worker bị crash.

- Queue mặc định tối đa 100 job QUEUED/RUNNING, cấu hình `CV_EXTRACTION_MAX_PENDING` (1..10000).
- Worker mặc định 1 job đang chạy toàn hệ thống (`CV_WORKER_MAX_RUNNING`, 1..8); các worker phải dùng cùng cấu hình.
- QUEUED quá 10 phút hoặc lease RUNNING hết hạn chuyển FAILED/JOB_EXPIRED khi GET/POST/poll worker.
- Lease 180 giây, gia hạn mỗi 30 giây; completion được fence bằng token và lease chưa hết hạn.
- Timeout/giới hạn bộ trích giữ nguyên: PDF tối đa 10 MiB, chỉ 5 trang đầu, OCR 12 giây/trang,
  Docker 90 giây, runner 100 giây cùng thời gian dọn; host adapter dừng chờ sau 150 giây.
- Kết quả stdout tối đa 128 KiB, được kiểm tra cấu trúc, phiên bản, số candidate và outcome trước lưu.
- Không giữ DB transaction/row lock trong lúc chạy sandbox. Các lệnh DB web có timeout ngắn;
  riêng worker dùng connect/socket/query timeout và transaction ngắn.

| HTTP | code | Ý nghĩa |
|---|---|---|
| 400 | VALIDATION_ERROR | UUID không hợp lệ |
| 401/403 | Chuẩn auth của hệ thống | Chưa đăng nhập, hết phiên, thiếu quyền hoặc ngoài phân công |
| 404 | CV_EXTRACTION_NOT_FOUND | Không có hồ sơ/CV/job hoặc job thuộc hồ sơ khác |
| 409 | CV_CHANGED / CV_EXTRACTION_STALE | Cần tạo yêu cầu cho CV/phiên bản hiện tại |
| 429 | CV_RETRY_LIMIT | Đã đủ 3 retry/60 phút; gọi GET để theo dõi, không POST liên tục |
| 503 | CV_QUEUE_FULL | Queue đủ slot |
| 503 | CV_EXTRACTION_UNAVAILABLE | Tắt tạo mới, DB bận hoặc chưa sẵn sàng |

## Triển khai và phạm vi

Migration V68 (`database/migrations-shared`) tạo `cv_extraction_jobs` và `cv_extraction_attempts`, cascade khi xóa hồ sơ.
Không sao chép CV vào queue; job tham chiếu hash, worker đọc đúng phiên bản CV khi claim.
Kết quả và attempt không bị xóa chỉ vì tin tuyển dụng đóng. Dữ liệu tồn tại cùng hồ sơ.

`CV_EXTRACTION_ENABLED=false` mặc định; bật tạo job sau khi chuẩn bị worker và cấu hình tài nguyên.
Flag này độc lập `APPLICATION_INTAKE_ENABLED`; không tự bật API nộp hồ sơ.
Hướng dẫn worker: [devops/cv-extraction/worker.md](../../devops/cv-extraction/worker.md).

335 kiểm chứng ranh giới lỗi với intake. Không cần nối worker vào giao dịch nhận hồ sơ;
nếu queue/worker/OCR lỗi, hồ sơ đã nộp và CV gốc vẫn được giữ.
