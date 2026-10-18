# 325 — Lưu hồ sơ ứng tuyển và tệp CV

Task này cung cấp lớp lưu trữ nội bộ, **chưa có endpoint nhận hồ sơ hoặc tải CV**.
API công khai, quota IP, mã/email xác nhận còn thuộc 326–329.

## Schema

V26 thêm `job_applications` và `application_cvs`. Composite FK giữ đúng tin ↔ requisition.
Hồ sơ giữ thông tin người nộp, nguồn tùy chọn, thư giới thiệu, snapshot tên tin, thời điểm
nhận và stage NEW. Không tự gộp ứng viên hoặc unique email toàn hệ thống. Hồ sơ ứng tuyển vẫn không
bao giờ bị gộp: từ task 360 (V32), một [hồ sơ ứng viên](candidate-profiles.md) gom các hồ sơ ứng tuyển
có đúng cùng email đã chuẩn hóa, mỗi hồ sơ ứng tuyển giữ nguyên bản chụp của nó.

Unique `(requisition_id,normalized_email)` không phụ thuộc stage. Chuẩn hóa bằng Java
Locale.ROOT, không bỏ dấu chấm/`+tag` hoặc dùng lower() theo locale PostgreSQL.
Khóa email dài 512 để chứa trường hợp Unicode lowercase làm dài hơn email đầu vào (254).

CV nằm trong bảng BYTEA riêng; MIME do backend xác định, có tên sạch, size/hash/thời điểm.
CHECK kiểm lại số byte và SHA-256 với `pg_catalog.sha256(content)`. Không public URL,
không query byte CV khi count pipeline. Quyền đọc CV về sau thuộc S4-09.
`tracking_code_hash` là 32 byte bắt buộc/unique, caller nội bộ cung cấp cho nền 329.
325 không sinh mã/email, không có API đổi stage/xóa/thu hồi mã.

## Service và khóa

`ApplicationStorageService.store(postingId,submission,validatedCv,trackingHash)` là Spring
transaction READ_COMMITTED; gọi qua bean/proxy. Reception service tương lai cần transaction
READ_COMMITTED chung với outbox. Unique/error mapping public thuộc 327, không trả SQL/PII.

- Kiểm tra CV ngoài transaction, không parser khi giữ khóa.
- Validate input dù không qua controller; khóa requisition FOR SHARE → tin FOR SHARE →
  nguồn CANDIDATE_SOURCE còn active nếu có.
- Chỉ nhận PUBLISHED/APPROVED, còn hạn theo BusinessCalendar; kiểm lại ngày sau chờ khóa
  danh mục bằng cùng mốc ghi vào hồ sơ, và (task 360) thêm một lần sau liên kết hồ sơ ứng viên,
  chỗ chờ khóa cuối cùng.
- Insert hồ sơ và CV atomic; lỗi CV/rollback caller bỏ cả hai; unique chặn nộp đồng thời.
- Task 360: ngay sau insert hồ sơ (sau bộ chặn trùng), trước CV, `CandidateLinks.link` gắn hồ sơ vào
  hồ sơ ứng viên của email chuẩn hóa trong cùng transaction; cần READ_COMMITTED, caller không được
  chạy `store()` trong REPEATABLE READ. Xem [hồ sơ ứng viên](candidate-profiles.md#khóa-và-transaction).

Input: tên 120, email 254, thư giới thiệu 5000 ký tự; văn bản thuần, trim Unicode ngoài cùng.
Phone giữ số 0 đầu, bỏ dấu phân cách, nhận `+` đầu và 8–15 số; không thêm mã quốc gia.
Object PII/CV có toString redacted; content/hash dùng defensive copy.

## CV validator

`CvValidator.validate(filename,inputStream)`; caller đóng stream. Không tin MIME/size header.

- Đọc tối đa 10.485.761 byte để chặn vượt **10 MiB**, không nhận file rỗng.
- Đuôi/signature khớp. PDFBox 3.0.8 strict parsing, PDF 1–1000 trang, không mã hóa/password.
- DOCX là Word OOXML có body; dùng POI sau bounded ZIP/XML preflight.
- ZIP 2000 entry, tổng giải nén 100 MiB; XML từng entry 5 MiB, depth 128, 200.000 element;
  entry trên 1 MiB có tỷ lệ nén >100:1 bị chặn.
- Chặn entry trùng/traversal/vbaProject, DOCTYPE/XXE/external schema; không sửa static
  settings POI dùng chung với Excel import.
- Filename chỉ basename, <=255 ký tự, không control/format/surrogate.

Không render PDF, chạy macro, bóc tách thông tin hoặc chứng nhận file không có malware.
Không có sandbox hard timeout/heap riêng; trước khi mở endpoint 326 cần xét thêm ngân sách
concurrency/body/proxy. Bytes được giữ nguyên. Nguồn:
[Apache PDFBox](https://pdfbox.apache.org/download.html),
[PDFBox 3.0](https://pdfbox.apache.org/3.0/migration.html),
[PostgreSQL SHA-256](https://www.postgresql.org/docs/16/functions-binarystring.html).

## Pipeline và phần còn lại

`ApplicationPipelineSource` thay stub: NEW/SCREENING/INTERVIEW/OFFER active;
HIRED/REJECTED/WITHDRAWN final. Không thêm khóa/read CV, xếp stage theo enum.
Đóng/hủy sau nộp nhìn thấy pipeline và dùng xác nhận hiện có. 325 chỉ tạo NEW; ánh xạ stage
sang [trạng thái công khai](application-tracking.md#trạng-thái-công-khai-task-340) có từ task 340.
Multipart HTTP vẫn 5 MiB/6 MiB vì chưa có endpoint
CV; 326 cần nâng và giữ limit riêng avatar/media/import. Outbox/IP/mã/link/tra cứu/rút/UI
chưa có trong 325.

## Kiểm thử

Tests mới: CvValidatorTest, ApplicationSubmissionTest, ApplicationStageTest,
ApplicationStorageIntegrationTest. Kiểm tra biên 10 MiB, file giả/hỏng/mã hóa, XML/ZIP,
bytes/hash/FK, upgrade V25→V26 giữ dữ liệu, unique song song, rollback, pipeline, mọi
trạng thái không nhận, hai chiều race nộp–đóng và chờ khóa qua nửa đêm Việt Nam.
Storage có 24 ca, gồm source/cover null, chặn xóa nguồn đã dùng và khôi phục schema sau upgrade.

Docker opt-in: `-Dtest.database.url=jdbc:postgresql://127.0.0.1:<port>/task325_test`.
Factory test chỉ nhận localhost/tên database này, tạo schema và Hikari pool riêng mỗi context;
pool tối đa 10 connection, đóng cùng context. Không dùng recruitment/credential production.
Không có property thì vẫn PostgreSQL nhúng.
Docker trên máy này dùng `-Duser.timezone=UTC`, BusinessCalendar vẫn giờ Việt Nam.
Container test dùng fsync/synchronous_commit off để tăng tốc, không kiểm chứng durability
sau crash của production. Nhật ký: [task325-journal](../reviews/2026-10-14/task325-journal.md).
