# Hồ sơ ứng viên hợp nhất

Phạm vi TKNHTTDNB1-360 (một hồ sơ ứng viên liên kết mọi lần ứng tuyển của cùng một người), story TKNHTTDNB1-48 (S4-09): một trang gồm thông tin cá nhân, CV xem trước ngay trong trang, lịch sử ứng tuyển các vị trí, nhãn và ghi chú; hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này; chỉ recruiter phụ trách và hiring manager của vị trí đó mở được. Task 360 chỉ thêm phần dữ liệu (migration V32 và việc liên kết khi lưu hồ sơ), **chưa có API đọc**: quy tắc ai được xem là task 363, API hồ sơ và lịch sử ứng tuyển là task 361, xem CV là task 362.

Tiêu chí của story S4-09:

- "Hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này": phần dữ liệu đã có (360). Mỗi lần ứng tuyển được gắn với đúng một hồ sơ ứng viên theo email đã chuẩn hóa; API hiển thị là task 361.
- "Một trang gồm thông tin cá nhân, CV xem trước…": chưa có (361, 362).
- "Chỉ recruiter phụ trách và hiring manager của vị trí đó mở được": chưa có (363).

## Hồ sơ ứng viên hợp nhất (task 360)

### Thế nào là "cùng một người"

Cùng một người là cùng **email đã chuẩn hóa** (`normalized_email`): email người nộp gõ, bỏ các ký tự vô hình ở hai đầu (khoảng trắng, NBSP, ZWSP, BOM) rồi viết thường theo `Locale.ROOT` (quy tắc của task 325/327 trong `ApplicationSubmission.normalizeEmail`, dùng chung với tra cứu của task 339). Vì vậy `" A@Example.TEST "` và `a@example.test` là một người. Không bỏ dấu chấm, không bỏ phần `+tag`, không so theo số điện thoại hay họ tên.

Mỗi lần ứng tuyển vẫn là một dòng riêng của `job_applications` với bản chụp của chính nó (họ tên, email đã gõ, số điện thoại, nguồn, thư giới thiệu, CV, tên vị trí lúc nộp). Hồ sơ ứng viên chỉ **gom** các lần ứng tuyển lại; nó không chép, không ghi đè và không chọn bản nào là đúng. Quy tắc "mỗi email chỉ nộp một lần vào mỗi yêu cầu tuyển dụng" (task 327) giữ nguyên; vẫn không có ràng buộc email duy nhất trên toàn hệ thống cho `job_applications`.

### Email không được xác minh

Hệ thống **không xác minh** email người nộp sở hữu. API nộp hồ sơ công khai trả mã tra cứu ngay trong response 201 (task 326/329), nên ai biết email của một ứng viên cũng nộp được một hồ sơ, với họ tên, số điện thoại và CV của mình, mà không cần vào hộp thư đó; hồ sơ ấy được gắn vào hồ sơ ứng viên của người kia. Các thành viên một gia đình dùng chung một hộp thư cũng bị gộp như vậy.

Hệ quả cho các task sau:

- API hồ sơ (361) giữ thông tin liên hệ **theo từng lần ứng tuyển** và không bao giờ lấy họ tên hay số điện thoại của lần nộp mới nhất làm thông tin của người đó. Nếu các lần ứng tuyển người xem đọc được không cùng họ tên hoặc số điện thoại, phần đầu hồ sơ để trống và báo `contactMismatch`.
- Chỉ việc xác minh email (ví dụ bấm liên kết trong email trước khi gộp) hoặc không trả mã tra cứu trong response nộp hồ sơ mới chặn được việc gắn hồ sơ giả; cả hai chờ BA/PO (câu hỏi 25, ý 1).

### Khi nào hồ sơ được liên kết

`ApplicationStorageService.store` (của TRAN DUC LUONG) gọi `CandidateLinks.link` ngay sau khi chèn dòng `job_applications` (sau bộ chặn trùng email theo yêu cầu tuyển dụng) và trước khi chèn CV, trong **cùng transaction**:

```sql
INSERT INTO candidates (id, normalized_email, created_at) VALUES (:id, :email, :at)
ON CONFLICT ON CONSTRAINT candidates_normalized_email_key DO NOTHING;

INSERT INTO candidate_applications (application_id, candidate_id, linked_at)
SELECT :application, c.id, :at FROM candidates c WHERE c.normalized_email = :email
RETURNING candidate_id;   -- đúng một dòng, nếu không: IllegalStateException
```

- Lần ứng tuyển đầu tiên của một email tạo hồ sơ ứng viên (`created_at` = thời điểm nhận hồ sơ đó); các lần sau chỉ thêm liên kết (`linked_at` = thời điểm nhận của chính lần đó).
- Hồ sơ bị từ chối vì trùng (409 `APPLICATION_ALREADY_SUBMITTED`) không tạo liên kết hay hồ sơ ứng viên nào: bộ chặn trùng đứng trước.
- CV lỗi, hết thời gian giao dịch hoặc caller rollback: hồ sơ ứng viên mới và liên kết bị bỏ cùng hồ sơ ứng tuyển. `CandidateLinks.link` là `MANDATORY`: không có transaction thì Spring từ chối.
- Mọi đường lưu hồ sơ đều đi qua `store()`: API nộp hồ sơ công khai (`PostgresApplicationReceiptWriter`) và, từ task 351, API giới thiệu nội bộ.

Chữ ký `store()`, các lỗi và response của API nộp hồ sơ không đổi.

### Khóa và transaction

`store()` chạy READ COMMITTED (đã là hợp đồng của nó) và việc liên kết **cần** READ COMMITTED: hai lần ứng tuyển đầu tiên của cùng một email vào hai yêu cầu khác nhau chạy cùng lúc thì lần sau chờ lần trước ở chỉ mục unique `candidates_normalized_email_key`. Khi lần trước commit, câu `INSERT … ON CONFLICT DO NOTHING` không chèn gì, và câu thứ hai (một câu riêng, nên có snapshot mới) thấy hồ sơ ứng viên vừa commit; khi lần trước rollback, lần sau tự tạo hồ sơ ứng viên. Hai bên không giữ thứ bên kia cần (mỗi bên đã giữ yêu cầu, tin và dòng hồ sơ của riêng mình), nên không có deadlock. Đây là chỗ chờ mới duy nhất, sau mọi khóa sẵn có của `store()` (yêu cầu, tin, danh mục nguồn `FOR SHARE`, bộ chặn trùng), nên cũng là chỗ chờ cuối cùng: ngay sau khi liên kết, `store()` kiểm lại hạn nộp theo ngày làm việc (`BusinessCalendar`) thêm một lần, như lần kiểm sau khi chờ khóa danh mục. Lần chờ kết thúc sau nửa đêm của ngày hết hạn thì hồ sơ bị từ chối như hồ sơ nộp muộn (404 `JOB_POSTING_NOT_FOUND`): hồ sơ ứng tuyển, liên kết và hồ sơ ứng viên mới của nó bị rollback, cùng dòng giới thiệu và email chờ gửi nếu caller dùng chung transaction. Hồ sơ được nhận vẫn giữ `received_at` (và `linked_at`) là mốc của lần kiểm đầu.

Một caller gọi `store()` bên trong transaction REPEATABLE READ sẽ gặp lỗi serialization ở đúng tình huống trên; hiện không caller nào làm vậy, và caller mới không được làm vậy.

### Schema (V32)

| Bảng | Nội dung |
|---|---|
| `candidates` | Một dòng cho mỗi người: `id`, `normalized_email` (unique `candidates_normalized_email_key`; CHECK `candidates_valid_email`: không rỗng, không có khoảng trắng ở hai đầu), `created_at` |
| `candidate_applications` | Mỗi lần ứng tuyển một dòng: `application_id` (khóa chính, nên mỗi lần ứng tuyển thuộc đúng một hồ sơ ứng viên; `ON DELETE CASCADE` từ `job_applications`), `candidate_id` (`ON DELETE RESTRICT`: hồ sơ ứng viên còn lần ứng tuyển thì không bị xóa âm thầm; chỉ mục `candidate_applications_candidate_idx`), `linked_at` |

Liên kết là một bảng riêng chứ không phải một cột trên `job_applications` để việc gộp hồ sơ ứng viên của S5-01 (theo số điện thoại hoặc gộp tay, hoàn tác trong 24 giờ) chỉ cần chuyển dòng `candidate_applications`, và để không đổi bảng của module hồ sơ ứng tuyển. Việc liên kết là một lời gọi Java rõ ràng trong `store()`, không phải trigger: các trigger của dự án chỉ dùng để chặn sửa dữ liệu (V18, V20, V23), và S5-01 sẽ cần quyết định việc gộp trong Java.

**Dữ liệu cũ.** Khi nâng cấp, V32 tạo một hồ sơ ứng viên cho mỗi `normalized_email` đã có trong `job_applications` (`created_at` = lần nhận sớm nhất của email đó) và liên kết mọi lần ứng tuyển cũ (`linked_at` = thời điểm nhận của lần đó). Nhóm theo email đã chuẩn hóa, không theo email gõ: `A@Example.TEST` và `a@example.test` là một hồ sơ ứng viên.

### Chưa làm

- API đọc hồ sơ ứng viên, lịch sử ứng tuyển, CV, quyền xem và nhật ký truy cập: task 361, 362, 363.
- Gộp hồ sơ ứng viên theo số điện thoại hoặc gộp tay, so sánh cạnh nhau, hoàn tác trong 24 giờ: S5-01.
- Xác minh email trước khi gộp (câu hỏi 25, ý 1).
- Xóa hồ sơ ứng viên theo yêu cầu của chính ứng viên (câu hỏi 25, ý 3).
- Nhãn và ghi chú: S5-04.

## Kiểm thử

- `CandidateProfileMigrationTest`: nâng cấp từ migration ngay trước V32 với ba hồ sơ ứng tuyển viết bằng SQL (hai hồ sơ ở hai yêu cầu, email gõ khác nhau `A@Example.TEST` và `a@example.test` giữa hai NBSP, cùng email chuẩn hóa; một hồ sơ của người khác) → đúng hai hồ sơ ứng viên, email chuẩn hóa trùng email của các hồ sơ ứng tuyển, ba liên kết, `created_at` là lần nhận sớm nhất, `linked_at` là thời điểm nhận; `job_applications` không đổi; Flyway `validate()` qua. Trên database dừng ở V32: unique email, CHECK email, mỗi lần ứng tuyển một liên kết, hai khóa ngoại, xóa hồ sơ ứng viên còn liên kết bị từ chối (23503), xóa hồ sơ ứng tuyển xóa liên kết và giữ hồ sơ ứng viên.
- `CandidateLinkIntegrationTest`: `store()` liên kết hai lần ứng tuyển của `" A@EXAMPLE.test "` (giữa hai NBSP) và `a@example.test` vào một hồ sơ ứng viên, người khác vào hồ sơ khác; hồ sơ trùng (409) không tạo liên kết hay hồ sơ ứng viên; hai lần ứng tuyển đầu tiên cùng email vào hai yêu cầu chạy cùng lúc: lần sau chờ đúng ở câu chèn `candidates` (kiểm bằng `pg_blocking_pids`), lần trước commit → một hồ sơ ứng viên, hai liên kết; rollback → lần sau tự tạo; rollback của caller và lỗi chèn CV không để lại hồ sơ ứng viên; hồ sơ nộp qua `PostgresApplicationReceiptWriter` được liên kết; lần ứng tuyển đầu tiên qua lần kiểm hạn lúc 23:59:59 của ngày hết hạn rồi chờ lần kia tới sau nửa đêm → 404 `JOB_POSTING_NOT_FOUND`, không còn hồ sơ ứng tuyển, CV hay liên kết nào của nó, chỉ một hồ sơ ứng viên.
- Các test của module hồ sơ ứng tuyển (lưu trữ, nộp hồ sơ, xác nhận, hạn mức IP, pipeline) chạy lại không đổi.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 25, ý (1)–(3).
