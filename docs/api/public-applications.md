# 326 — Xây dựng API nhận hồ sơ ứng tuyển công khai

Backend của S4-03, [ATSNB-95](https://luoplayer.atlassian.net/browse/ATSNB-95).
API đã có hợp đồng, validation, [chống trùng email/requisition](application-duplicates.md)
và [quota IP production](application-ip-quota.md), [receipt/outbox production](application-confirmation.md).
**Nộp hồ sơ tắt mặc định**; trước sử dụng thật cần khóa, URL tra cứu hoạt động và cấu hình proxy.

## Cấu hình và điều kiện bật

`APPLICATION_INTAKE_ENABLED=false`, `APPLICATION_INTAKE_MAX_CONCURRENT=2` (1..8).
Flag true chỉ nhận hồ sơ khi Spring có cả `ApplicationAttemptQuota` và
`ApplicationReceiptWriter` sẵn sàng. Quota và receipt/outbox production đã có; writer chưa
ready khi khóa rỗng. Bật riêng flag vẫn503 trước quota/body nếu thiếu khóa mã hóa.
Adapter trong `src/test` chỉ chứng minh HTTP/storage, không đóng vào JAR, không gửi mail.

`ApplicationAttemptQuota.checkAndRecord(request)` là điểm nối 328: ghi nhận mỗi POST
trước kiểm UUID, media type, kích thước, dữ liệu, tin hay tình trạng bận. Không gọi cho CORS
preflight hoặc khi API chưa được bật đủ. Adapter phải tính 5 lần/IP trong cửa sổ 60 phút,
kể cả lỗi/trùng, lấy IP qua proxy tin cậy và commit lượt riêng với transaction hồ sơ.
Provider PostgreSQL của 328 đã nối: 429 kèm Retry-After; hỏng quota trả 503
APPLICATION_QUOTA_UNAVAILABLE. Xem [quy tắc IP, proxy và giao dịch](application-ip-quota.md).

`ApplicationReceiptWriter.accept(...)` là điểm nối 329, nhận dữ liệu/CV đã kiểm tra.
Implementation phải tạo mã ngẫu nhiên, hash 32 byte, gọi `ApplicationStorageService.store`
qua Spring proxy và ghi outbox cùng transaction READ_COMMITTED; tạo receipt hợp lệ trước
commit và chỉ trả receipt sau khi commit. Không gửi SMTP đồng bộ trong transaction.
Không lấy host/link từ header ứng viên. Mail xác nhận bắt buộc, giữ hồ sơ khi gửi mail lỗi,
thử lại tối đa5lần ngoài lần gửi đầu. Outbox/mail đã được triển khai ở
[329](application-confirmation.md), dùng chungSMTP và cấu hình người gửi hiện có.

## Nộp hồ sơ

`POST /api/v1/public/job-postings/{id}/applications`, không cần tài khoản.
Bearer token cũ bị bỏ qua chỉ tại đúng POST này và GET nguồn biết tin.
Các phương thức khác và API nội bộ giữ phân quyền hiện tại.

`Content-Type: multipart/form-data; boundary=...`, UTF-8:

| Part | Bắt buộc | Quy tắc |
|---|---|---|
| `fullName` | Có | Plain text, sau trim 1..120 ký tự |
| `email` | Có | Email hợp lệ, tối đa 254; trim Unicode, lowercase khi lưu |
| `phone` | Có | Sau bỏ khoảng trắng/dấu phân cách, 8..15 chữ số, tùy chọn `+` đầu |
| `sourceId` | Không | UUID nguồn CANDIDATE_SOURCE đang áp dụng; trống → null |
| `coverLetter` | Không | Plain text nhiều dòng, tối đa 5.000 ký tự |
| `cv` | Có | Đúng một file PDF/DOCX, tối đa 10 MiB = 10.485.760 byte |

Không nhận query, trường nội bộ, part dư/lặp, file phụ. Tối đa 6 parts, header mỗi part
2.048 byte; byte budget trước decode UTF-8: name 1.024, email 2.048, phone 256,
source 128, cover 20.000. Bean validation áp dụng giới hạn ký tự sau chuẩn hóa.
MIME client không quyết định loại CV; dùng `CvValidator` từ 325 kiểm byte và cấu trúc thực.

Body tối đa 11 MiB, được kiểm cả Content-Length và stream. CV không tạo file tạm.
Đọc/kiểm CV chạy trước transaction ghi DB. Commons FileUpload Servlet6 **2.0.0-M5**
được pin theo [bản phát hành Apache](https://commons.apache.org/fileupload/download_fileupload.cgi),
sử dụng [streaming API](https://commons.apache.org/proper/commons-fileupload/streaming.html).
M5 là milestone; version này được kiểm thử trên Servlet/Tomcat của dự án.
Servlet multipart cũ vẫn giới hạn 5/6 MiB; avatar/media/import giữ nguyên behavior.
Task 351: `POST /api/v1/referrals` (giới thiệu nội bộ) cũng bỏ qua parser Servlet và đọc CV bằng cùng vòng
đọc streaming (`ApplicationMultipartDecoder.decodeReferral`), cùng giới hạn 10/11 MiB; xem
[API giới thiệu](application-referrals.md#api-giới-thiệu-task-351).
Budget concurrent chặn quá tải parser; không thay thế hard CPU/heap isolation hay quét malware.

Tin phải PUBLISHED, requisition APPROVED và còn hạn hết ngày Việt Nam.
Kiểm lần đầu trước đọc body; storage đọc lại dưới khóa requisition → posting → source,
kiểm hạn tại thời điểm thực nhận. Tạm dừng/đóng trước commit sẽ không để lại hồ sơ/CV.
Không nhận stage, requisitionId, receivedAt, hash hay mã do client tự đặt.

Khi đủ adapter thật, thành công trả **201**, `Cache-Control: no-store`:

```json
{
  "applicationId": "UUID",
  "status": "RECEIVED",
  "statusName": "Đã nhận",
  "receivedAt": "2026-10-14T03:00:00Z",
  "trackingCode": "base64url-43-characters",
  "trackingUrl": "https://portal.example/track#ma=base64url-43-characters"
}
```

Mã/link nhạy cảm không xuất hiện trong `toString` của DTO. Link HTTPS (HTTP chỉ loopback
cho phát triển), không userinfo/query, mã nằm trong fragment. Link do cấu hình 329 tạo.
Không trả email, phone, nội dung CV hay hash. Không thêm API tra cứu S4-05 trong task này;
status/statusName lấy từ [ánh xạ trạng thái công khai](application-tracking.md#trạng-thái-công-khai-task-340)
của task 340: hồ sơ vừa nộp ở stage NEW nên luôn là RECEIVED/Đã nhận.

## Nguồn biết tin

`GET /api/v1/public/application-sources` hoạt động độc lập với flag nộp, trả danh sách
nguồn CANDIDATE_SOURCE đang áp dụng, theo thứ tự danh mục. Mỗi mục chỉ `{id,name}`,
`Cache-Control: no-store`. Không công khai code/trạng thái/thứ tự/loại danh mục nội bộ.

## Lỗi

JSON chung `{code,message,fieldErrors}`, không echo PII, tên file hay lỗi SQL/parser.

| HTTP | Code/điều kiện |
|---|---|
| 400 | VALIDATION_ERROR, INVALID_MULTIPART, CV hỏng/rỗng/mã hóa |
| 400 | APPLICATION_CV_ACTIVE_CONTENT (task 362): DOCX liên kết ra ngoài (mẫu, OLE, khung, ảnh trên máy chủ khác, hyperlink không phải http/https/mailto) hoặc PDF có Launch, JavaScript, tệp đính kèm; xem [nội dung tự động trong CV](candidate-profiles.md#nội-dung-tự-động-trong-cv-task-362) |
| 404 | JOB_POSTING_NOT_FOUND: tin/requisition không nhận hoặc hết hạn |
| 409 | APPLICATION_ALREADY_SUBMITTED: email chuẩn hóa đã nộp cho requisition, fieldErrors.email |
| 413 | APPLICATION_CV_TOO_LARGE: file >10 MiB/request >11 MiB |
| 415 | APPLICATION_FORM_TYPE_UNSUPPORTED, APPLICATION_CV_TYPE_UNSUPPORTED |
| 400 | APPLICATION_CLIENT_IP_INVALID: peer proxy tin cậy gửi header IP thiếu/sai |
| 429 | APPLICATION_RATE_LIMIT_EXCEEDED, kèm Retry-After |
| 503 | APPLICATION_QUOTA_UNAVAILABLE: lỗi kiểm tra/ghi quota |
| 503 | APPLICATION_INTAKE_UNAVAILABLE: tắt hoặc thiếu adapter cần thiết |
| 503 | APPLICATION_INTAKE_BUSY: hết budget concurrent, Retry-After: 1 |
| 503 | APPLICATION_SUBMISSION_UNAVAILABLE: lỗi writer/commit, giữ kín chi tiết |

Unique email/requisition và tracking hash của 325 vẫn giữ. Repository xử lý chính xác
`job_applications_requisition_email_key` bằng INSERT ON CONFLICT DO NOTHING, ném
`DuplicateApplicationException` 409 trước ghi CV nếu trùng. Bản gốc không bị sửa.
Xung đột tracking hash và lỗi database khác không được chuyển thành lỗi email; API che
bằng 503. Writer phải để exception thoát transaction để hoàn tác các thao tác liên quan.

## Kiểm thử

`ApplicationIntakeContractTest` kiểm parser/strict UTF-8/part/input/activation/link.
Ba `ApplicationIntake*IntegrationTest` kiểm HTTP thật với PostgreSQL: production OFF,
flag ON thiếu adapter vẫn chặn; adapter test kiểm 201+snapshot+byte/hash, DOCX, biên 10 MiB,
chunked, deadline Việt Nam, source/status, quota, rollback, closure race, concurrency và CORS.
Docker opt-in dùng factory/schema riêng như [storage](application-storage.md).
Không dùng database production hoặc SMTP thật.

Khi chạy JAR trên Windows với PostgreSQL Docker của môi trường này, dùng tham số JVM
`java "-Duser.timezone=UTC" -jar target/ttcs-backend-0.0.1-SNAPSHOT.jar` để tránh alias
`Asia/Saigon` bị PostgreSQL từ chối. Ngày nghiệp vụ vẫn dùng `app.business-zone=Asia/Ho_Chi_Minh`.
