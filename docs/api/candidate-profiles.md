# Hồ sơ ứng viên hợp nhất

Phạm vi TKNHTTDNB1-360 (một hồ sơ ứng viên liên kết mọi lần ứng tuyển của cùng một người), story TKNHTTDNB1-48 (S4-09): một trang gồm thông tin cá nhân, CV xem trước ngay trong trang, lịch sử ứng tuyển các vị trí, nhãn và ghi chú; hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này; chỉ recruiter phụ trách và hiring manager của vị trí đó mở được. Task 360 chỉ thêm phần dữ liệu (migration V32 và việc liên kết khi lưu hồ sơ). Task 363 thêm quy tắc ai được xem và nhật ký truy cập (migration V33, V33_1). Task 361 thêm API đọc đầu tiên, hồ sơ và lịch sử ứng tuyển `GET /api/v1/candidates/{id}/unified-profile` (không migration). Task 362 thêm API xem CV `GET /api/v1/applications/{id}/cv` và việc từ chối CV có nội dung tự động ngay khi nộp (không migration).

Tiêu chí của story S4-09:

- "Hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này": đã có. Mỗi lần ứng tuyển được gắn với đúng một hồ sơ ứng viên theo email đã chuẩn hóa (360); API hồ sơ (361) trả mọi lần ứng tuyển **người xem được đọc**, mới nhất trước. Lần ứng tuyển ngoài phạm vi người xem không hiện gì (câu hỏi 25, ý 7), xem [API hồ sơ và lịch sử](#api-hồ-sơ-và-lịch-sử-task-361).
- "Một trang gồm thông tin cá nhân, CV xem trước…, lịch sử ứng tuyển các vị trí, nhãn và ghi chú": thông tin cá nhân (theo từng lần ứng tuyển) và lịch sử ứng tuyển có từ task 361, cùng tên tệp, loại và dung lượng CV; nội dung CV có từ task 362 (PDF xem ngay trong trang, DOCX chỉ tải về, xem [Xem CV](#xem-cv-task-362)); nhãn và ghi chú luôn rỗng tới S5-04.
- "Chỉ recruiter phụ trách và hiring manager của vị trí đó mở được": quy tắc và nhật ký đã có (363); API hồ sơ (361) và API CV (362) đều dùng, và mọi lần đọc CV cũng được ghi nhật ký. **Chưa đạt với HR_MANAGER và ADMIN** (giữ `CANDIDATES_READ_ALL`, câu hỏi 6 và 25 ý 4), xem [Ai được xem](#ai-được-xem-task-363).

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

- Xem trước DOCX ngay trong trang (câu hỏi 25, ý 10) và quét mã độc trong CV (câu hỏi 25, ý 11).
- Gộp hồ sơ ứng viên theo số điện thoại hoặc gộp tay, so sánh cạnh nhau, hoàn tác trong 24 giờ: S5-01.
- Xác minh email trước khi gộp (câu hỏi 25, ý 1).
- Xóa hồ sơ ứng viên theo yêu cầu của chính ứng viên (câu hỏi 25, ý 3).
- Nhãn và ghi chú: S5-04.

## Ai được xem (task 363)

Task 363 thêm quy tắc ai được đọc hồ sơ ứng viên và CV, cùng nhật ký mọi lần đọc. API hồ sơ (task 361) và API CV (task 362) dùng nó. Quy tắc nằm ở một chỗ, `vn.ttcs.recruitment.candidate.CandidateAccess`; không thêm mã quyền, không thêm route.

### Quy tắc

Quyết định theo **mã quyền**, không theo tên vai trò:

| Quyền | Vai trò trong seed | Được đọc |
|---|---|---|
| `CANDIDATES_READ_ALL` | HR_MANAGER, ADMIN | mọi lần ứng tuyển |
| `CANDIDATES_READ_SCOPED` | RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER (từ V33_1) | chỉ lần ứng tuyển vào yêu cầu tuyển dụng mà người đọc **đang** là recruiter chính hoặc hỗ trợ (`RecruiterAssignmentAccess`, task 285), hoặc thuộc phòng ban mà người đọc là trưởng (`departments.manager_user_id`), kể cả mọi phòng ban con cháu (`DepartmentRepository.MANAGED_DEPARTMENT_IDS`) |
| không có quyền nào | tài khoản không vai trò | không đọc được gì (403) |

- Người tạo yêu cầu tuyển dụng không được tính. Lịch sử phân công (V17) cũng không: sau khi bàn giao, chỉ recruiter mới đọc được; recruiter hỗ trợ bị bỏ thì mất quyền ngay.
- "Hiring manager của vị trí đó" được hiểu là trưởng phòng ban của yêu cầu tuyển dụng, hoặc trưởng một phòng ban cấp trên (cùng quy tắc cây phòng ban với `REQUISITIONS_*_SCOPED`).
- Interviewer chưa có vòng phỏng vấn (S6) nên chưa đọc được gì, trừ khi đồng thời là recruiter được phân công hoặc trưởng phòng ban, vì quy tắc theo mã quyền.
- Quyền tính theo **từng lần ứng tuyển**: một người nộp vào nhiều yêu cầu thì người đọc `SCOPED` chỉ được đọc các lần thuộc phạm vi của mình (API hồ sơ của task 361 chỉ trả các lần đó).
- Một đoạn SQL duy nhất `CandidateAccess.READABLE` dùng cho cả danh sách và một bản ghi (`findApplication`), nên hai nơi không thể trả lời khác nhau. Quyền được đọc lại từ database ở mỗi lần gọi: gỡ một dòng `role_permissions` có hiệu lực ngay ở lần gọi sau.

### Approver (V33_1)

Bảng nguồn ghi ô Approver – hồ sơ ứng viên là `R`, nên V3 cấp `CANDIDATES_READ_ALL`: Approver xem được CV, email, số điện thoại và thư giới thiệu của mọi ứng viên, dù không làm tuyển dụng ở Sprint 4. Theo tiêu chí của task 363, V33_1 tạm thay bằng `CANDIDATES_READ_SCOPED`, giống cách mục 7.1 của ma trận đã làm với Recruiter: Approver chỉ đọc khi là recruiter được phân công hoặc trưởng phòng ban. Tổng số dòng `role_permissions` vẫn là 106. BA/PO cần xác nhận (câu hỏi 3 và 25, ý 4), ví dụ Approver có cần xem ứng viên của offer chờ mình duyệt không.

### Tình trạng tiêu chí

Đạt với RECRUITER, HIRING_MANAGER, INTERVIEWER và APPROVER. **Chưa đạt với HR_MANAGER và ADMIN**: hai vai trò này giữ `CANDIDATES_READ_ALL` và đọc mọi ứng viên cho tới khi BA/PO trả lời câu hỏi 6 và 25 (ý 4). Task 363 nên giữ ở In Review, chưa Done, cho tới khi BA/PO xác nhận; QA (task 364) cần biết điểm này.

### Thứ tự kiểm

Mọi API ứng viên kiểm theo đúng thứ tự:

1. `requireReader`: token còn hạn, phiên và tài khoản còn hoạt động (401 nếu không); có `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED` (403 `FORBIDDEN` nếu không), **trước mọi tra cứu**. Không ghi nhật ký, vì chưa tra gì.
2. Bản ghi không tồn tại: 404, không ghi nhật ký.
3. Bản ghi có thật nhưng ngoài phạm vi: 403 `FORBIDDEN`, ghi một dòng `DENIED` sau khi transaction đọc đã kết thúc.
4. Được phép: trả dữ liệu và ghi một dòng `GRANTED` cho mỗi lần ứng tuyển được hiển thị, trong chính transaction đọc.

Cách dùng (task 361, 362 và API gợi ý dữ liệu CV task 334 của TRAN DUC LUONG phải dùng đúng các lời gọi này):

```java
CandidateReader reader = access.requireReader(jwt);                              // 401/403 trước mọi tra cứu
// Trong transaction đọc (REPEATABLE READ, read-write vì ghi nhật ký):
Optional<ApplicationAccess> found = access.findApplication(reader, applicationId); // rỗng: 404
// Danh sách: "... WHERE ca.candidate_id = :candidate AND " + CandidateAccess.READABLE, tham số access.parameters(reader)
access.recordGranted(reader, CandidateAccessKind.CV, candidateId, List.of(applicationId));
// Sau khi transaction đọc đã kết thúc, khi bản ghi có thật nhưng ngoài phạm vi:
access.recordDenied(reader, CandidateAccessKind.CV, candidateId, applicationId);   // rồi 403
```

`READABLE` dùng bí danh `a` cho `job_applications` và `r` cho `recruitment_requisitions`; các đoạn con tự giải quyết cột của mình. `findApplication` và `recordGranted` là `MANDATORY` (cần transaction của người gọi); `recordDenied` ném `IllegalStateException` nếu còn transaction.

### Nhật ký truy cập (V33)

`candidate_access_events` ghi mọi lần đọc hồ sơ (`PROFILE`) hoặc CV (`CV`):

| Cột | Ý nghĩa |
|---|---|
| `actor_id` | người đọc |
| `candidate_id` | hồ sơ ứng viên |
| `application_id` | lần ứng tuyển được hiển thị (được phép) hoặc bị từ chối; chỉ để trống khi một lần đọc hồ sơ bị từ chối vì không có lần ứng tuyển nào trong phạm vi |
| `access_kind` | `PROFILE` hoặc `CV` |
| `access_scope` | `ALL` hoặc `SCOPED` của người đọc lúc đó |
| `outcome` | `GRANTED` hoặc `DENIED` |
| `occurred_at` | thời điểm theo đồng hồ của ứng dụng, tới micro giây |

- Lần được phép ghi **một dòng cho mỗi lần ứng tuyển được hiển thị**: phạm vi không dựng lại được về sau (trưởng phòng ban không có lịch sử), nên nhật ký phải nói đúng đã hiện những gì. Các dòng này nằm trong transaction đọc, nên lần đọc bị lỗi giữa chừng không để lại dòng nào.
- Lần bị từ chối với bản ghi có thật (403) cũng được ghi, vì đó là dấu vết của việc dò id. Dòng này ghi trong transaction riêng, **sau** khi transaction đọc đã kết thúc, nên một request không bao giờ giữ hai kết nối database cùng lúc.
- Chỉ có id, không có dữ liệu cá nhân. Không có khóa ngoại: nhật ký phải còn khi dữ liệu được gộp (S5-01) hoặc xóa theo yêu cầu, và không được chặn các việc đó.
- Chỉ thêm được, như V20/V23: trigger chặn `UPDATE` và `DELETE` (SQLSTATE 23514); `TRUNCATE` (chỉ chủ bảng làm được; test dùng để dọn dữ liệu) không chạy trigger dòng.
- CHECK: lần được phép và mọi lần đọc CV phải có `application_id`; lần bị từ chối luôn có phạm vi `SCOPED` (người có `CANDIDATES_READ_ALL` không bao giờ bị từ chối). Chỉ mục theo `(candidate_id, occurred_at)` và `(actor_id, occurred_at)`.
- Chưa có API đọc nhật ký, chưa có thời hạn lưu và chưa định ai được xem (câu hỏi 25, ý 6).

## API hồ sơ và lịch sử (task 361)

`GET /api/v1/candidates/{id}/unified-profile` (Bearer), đúng đường dẫn frontend đang gọi (`candidateProfileService`). `{id}` là id hồ sơ ứng viên (`candidates.id`, task 360). Mọi response, kể cả lỗi, có `Cache-Control: no-store`. Không thêm mã quyền và không có migration. `GET /api/v1/candidates/{id}` không tồn tại (để dành cho một tài nguyên sau này); các route ghi chú, nhãn, chấm sao của frontend thuộc S5-04 và bị từ chối mặc định.

### Thứ tự kiểm và lỗi

| Trường hợp | Kết quả | Nhật ký |
|---|---|---|
| Không có token, token hết hạn, phiên bị thu hồi, tài khoản bị khóa | 401 `UNAUTHORIZED` | không |
| Không có `CANDIDATES_READ_ALL` hay `CANDIDATES_READ_SCOPED` (matcher, rồi `requireReader` đọc lại quyền từ database) | 403 `FORBIDDEN`, với mọi id, có thật hay không | không |
| `{id}` không phải UUID | 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ." | không |
| Hồ sơ không tồn tại, hoặc không còn lần ứng tuyển nào | 404 `CANDIDATE_NOT_FOUND` "Không tìm thấy hồ sơ ứng viên." | không |
| Có hồ sơ nhưng không có lần ứng tuyển nào trong phạm vi người gọi | 403 `FORBIDDEN` | một dòng `DENIED` `PROFILE`, không có `application_id`, ghi sau khi transaction đọc đã kết thúc |
| Được phép | 200 | một dòng `GRANTED` `PROFILE` cho **mỗi** lần ứng tuyển được trả, trong chính transaction đọc |

Bước 404 đứng trước bước phạm vi và chỉ phụ thuộc bản ghi, nên mọi người có quyền đọc ứng viên nhận cùng một câu trả lời cho một id không tồn tại. Phần đọc chạy trong một transaction REPEATABLE READ (read-write chỉ vì ghi nhật ký): kiểm tồn tại, phạm vi (nhóm recruiter, cây phòng ban) và các dòng trả về dùng cùng một snapshot; không khóa gì. `CandidateProfileService` không tự mở transaction bằng annotation: transaction đọc là một `TransactionTemplate`, nên dòng `DENIED` được ghi sau khi nó đã kết thúc và một request không bao giờ giữ hai kết nối database.

### Response 200

```json
{"id":"6f1c…","fullName":"Nguyễn Văn A","email":"NguyenVanA@Example.com","phone":"0912345678","contactMismatch":false,
 "currentApplicationId":"a1b2…","createdAt":"2026-03-02T02:00:00Z","updatedAt":"2026-10-15T03:00:00Z",
 "tags":[],"notes":[],
 "applications":[
  {"id":"a1b2…","isCurrent":true,"jobPostingId":"…","jobTitle":"Lập trình viên Java",
   "departmentName":"Phòng CNTT","appliedAt":"2026-10-15T03:00:00Z","stage":"NEW","stageName":"Hồ sơ mới",
   "fullName":"Nguyễn Văn A","email":"NguyenVanA@Example.com","phone":"0912345678",
   "source":{"type":"CAREER_PAGE","label":"Trang tuyển dụng","details":"LinkedIn"},
   "recruiterId":"…","recruiterName":"Trần Thị B","hiringManagerId":"…","hiringManagerName":"Phạm Văn C",
   "coverLetter":"…","cvFileName":"CV Nguyễn Văn A.pdf","cvFileType":"pdf","cvFileSize":12345}]}
```

Phần đầu hồ sơ:

| Trường | Ý nghĩa |
|---|---|
| `id` | id hồ sơ ứng viên |
| `fullName`, `phone` | họ tên và số điện thoại **chung** của mọi lần ứng tuyển được trả; `null` khi chúng không giống hệt nhau |
| `contactMismatch` | `true` khi các lần ứng tuyển được trả có họ tên hoặc số điện thoại khác nhau |
| `email` | email (đúng như đã gõ) của lần ứng tuyển mới nhất được trả; mọi lần có cùng email chuẩn hóa, email chuẩn hóa không bao giờ được trả |
| `currentApplicationId` | lần ứng tuyển mới nhất được trả (`received_at`, rồi `id`) |
| `createdAt`, `updatedAt` | `appliedAt` sớm nhất và mới nhất **trong các lần được trả**; không dùng `candidates.created_at`, vì nó có thể là ngày của một lần ngoài phạm vi. Từ task 345, `updatedAt` là thời điểm muộn nhất trong các `appliedAt` và `withdrawnAt` của các lần được trả (rút hồ sơ là thay đổi duy nhất của một lần ứng tuyển sau khi nộp trong Sprint 4) |
| `tags`, `notes` | luôn `[]` tới S5-04 |
| `applications` | các lần ứng tuyển người gọi được đọc, mới nhất trước; không bao giờ rỗng (rỗng thì đã trả 403) |

Mỗi lần ứng tuyển (khóa không có giá trị thì bị bỏ, không gửi `null`):

| Trường | Ý nghĩa |
|---|---|
| `id`, `isCurrent` | id lần ứng tuyển; `true` chỉ cho lần mới nhất |
| `jobPostingId`, `jobTitle` | tin tuyển dụng và **tên vị trí lúc nộp** (bản chụp `posting_title`; đổi tên tin sau đó không đổi giá trị này) |
| `departmentName` | tên **hiện tại** của phòng ban của yêu cầu tuyển dụng |
| `appliedAt` | thời điểm nhận hồ sơ |
| `stage`, `stageName` | giai đoạn nội bộ (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`, `WITHDRAWN`) và nhãn tiếng Việt của nó (`ApplicationStage`) |
| `withdrawnAt`, `withdrawalReason` | task 345: thời điểm ứng viên tự rút lần ứng tuyển này và lý do ứng viên ghi (không bắt buộc, tối đa 500 ký tự, giữ nguyên xuống dòng `\n`), lấy từ dòng `WITHDRAWN` của `application_stage_events` (V34). Chỉ có khi ứng viên đã rút qua [API rút hồ sơ](application-tracking.md#rút-hồ-sơ-task-345); `withdrawalReason` bỏ khi ứng viên không ghi lý do. Như mọi trường khác, chỉ người được đọc lần ứng tuyển đó mới thấy; lý do không bao giờ có trong email gửi nhân viên hay trong API công khai |
| `fullName`, `email`, `phone` | bản chụp của chính lần ứng tuyển đó, như người nộp đã nhập |
| `source` | `{type: "CAREER_PAGE", label: "Trang tuyển dụng", details}`; `details` là tên giá trị danh mục "Nguồn biết tin" người nộp đã chọn, bỏ khi không chọn. Giới thiệu nội bộ (task 350) sẽ thêm `INTERNAL_REFERRAL` |
| `recruiterId`, `recruiterName` | recruiter **chính** hiện tại của yêu cầu (chỉ id và họ tên); bỏ khi yêu cầu chưa có recruiter chính. Recruiter hỗ trợ không bao giờ hiện ở đây |
| `hiringManagerId`, `hiringManagerName` | trưởng trực tiếp của phòng ban của yêu cầu (chỉ id và họ tên) |
| `coverLetter` | thư giới thiệu; bỏ khi không có |
| `cvFileName`, `cvFileType`, `cvFileSize` | tên tệp lúc tải lên, `pdf` hoặc `docx` theo loại đã lưu (không theo đuôi tên tệp), dung lượng theo byte. Chỉ là thông tin của tệp: nội dung CV lấy ở [API CV](#xem-cv-task-362) (task 362) |

Không bao giờ trả: đường dẫn tệp CV (`cvFileUrl`), nội dung hay mã băm CV, mã tra cứu hay mã băm của nó, `requisitionId`, email chuẩn hóa, email của bất kỳ nhân viên nào, mã ứng viên (`candidateCode`), chấm sao (`rating`), chức danh hiện tại, số năm kinh nghiệm.

Hồ sơ của yêu cầu đã tuyển đủ, đã hủy hay tạm dừng, và của tin đã đóng, gỡ hay hết hạn vẫn được liệt kê: lịch sử ứng tuyển không phụ thuộc trạng thái yêu cầu hay tin.

### Chỉ các lần ứng tuyển người xem được đọc

Danh sách được lọc **trong SQL** bằng `CandidateAccess.READABLE` (`CandidateProfileRepository`), cùng đoạn điều kiện với `findApplication`. Một lần ứng tuyển ngoài phạm vi không thêm gì vào response: không id, không vị trí, không ngày, không giai đoạn, không phòng ban, không người phụ trách, và không làm thay đổi số phần tử hay `createdAt`/`updatedAt`. Ví dụ: chị Lan nộp vào "Kế toán" (tháng 3), "Lập trình viên Java" (tháng 6), "Kiểm thử" (tháng 10); recruiter của vị trí Java chỉ thấy lần nộp tháng 6, với `createdAt` = `updatedAt` = ngày đó; HR_MANAGER thấy cả ba.

Lý do: nếu hiện "tóm tắt" các lần khác, mọi người đọc theo phạm vi sẽ thấy quyết định của nhóm khác (bị loại, đang offer). Và vì email không được xác minh, một recruiter có thể tự nộp một hồ sơ công khai bằng email của người khác vào tin mình phụ trách để mở phạm vi đó; với quy tắc này, recruiter đó chỉ thấy đúng hồ sơ mình đã nộp. Có hiện thêm vị trí, ngày và giai đoạn của các lần khác hay không là câu hỏi 25 (ý 7) và 19 (ý 8) cho BA/PO.

### Thông tin liên hệ theo từng lần ứng tuyển

Mỗi lần ứng tuyển giữ họ tên, email và số điện thoại của chính nó. Phần đầu hồ sơ chỉ hiện `fullName` và `phone` khi mọi lần ứng tuyển được trả có **cùng** họ tên và **cùng** số điện thoại (so đúng từng ký tự); chỉ cần một khác biệt thì cả hai là `null` và `contactMismatch` là `true`. Không bao giờ lấy họ tên hay số điện thoại của lần nộp mới nhất làm thông tin của người đó, vì ai biết email cũng nộp được một hồ sơ với tên và số điện thoại khác (mục "Email không được xác minh"); HR gọi điện theo phần đầu hồ sơ thì có thể gọi nhầm người. Phần đầu được tính trên **các lần người gọi được đọc**: hai người đọc khác phạm vi có thể thấy phần đầu khác nhau.

### Ví dụ (PowerShell)

```powershell
$body = @{ email = 'hr@example.test'; password = '<mật khẩu>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
# <id>: SELECT candidate_id FROM candidate_applications WHERE application_id = '<id lần ứng tuyển>';
$profile = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/candidates/<id>/unified-profile' -Headers $headers
$profile.applications | Select-Object jobTitle, appliedAt, stage, fullName, phone
```

Sprint 4 chưa có danh sách ứng viên (S5-02); trang hồ sơ chỉ mở từ email báo rút hồ sơ (task 346) hoặc đường dẫn trực tiếp (câu hỏi 25, ý 9).

### Ghi chú cho frontend (task 358)

- Giữ đường dẫn `GET /candidates/{id}/unified-profile`. Thêm giá trị `INTERVIEW` vào kiểu giai đoạn (frontend đang có `INTERVIEWING`, nên một hồ sơ ở `INTERVIEW` làm trang lỗi); hiện `stageName` của server thay vì tự dịch.
- Mọi lần ứng tuyển trong response đều xem được: không có cờ `accessible`, không có dòng tóm tắt. Không tự lọc thêm.
- Mỗi lần ứng tuyển có `fullName`, `email`, `phone` và `coverLetter` của riêng nó. Khi `contactMismatch` là `true`, `fullName` và `phone` ở phần đầu là `null`: hiện cảnh báo "Các lần ứng tuyển có họ tên hoặc số điện thoại khác nhau" cùng giá trị của từng lần, và **không** tạo liên kết `tel:` từ `null`.
- Không có `cvFileUrl`: tải CV bằng `GET /applications/{id}/cv` (task 362, [ghi chú cho frontend](#ghi-chú-cho-frontend-task-359)); dùng `cvFileName` để hiển thị.
- Người phụ trách chỉ có id và họ tên (`recruiterId`/`recruiterName`, `hiringManagerId`/`hiringManagerName`); `recruiterId` vắng khi yêu cầu chưa có recruiter chính.
- Không có `currentTitle`, `experienceYears`, `candidateCode`, `rating`, `jobCode`; `tags`, `notes` luôn rỗng.
- Xử lý 401 (đăng nhập lại), 403 (không có quyền, hoặc không có lần ứng tuyển nào trong phạm vi: "Bạn không có quyền xem hồ sơ ứng viên này"), 404 (hồ sơ không tồn tại). **Không** hiện dữ liệu mẫu (mock) khi gặp lỗi.
- Mở lần ứng tuyển `currentApplicationId` trước, như hiện nay.
- Rút hồ sơ (task 345): lần ứng tuyển đã rút có `stage: "WITHDRAWN"`, `withdrawnAt` và có thể có `withdrawalReason` (frontend đã đọc `withdrawalReason` trong `ApplicationHistoryList`). Hiện lý do dưới dạng văn bản thuần, giữ xuống dòng, không render HTML.

## Xem CV (task 362)

`GET /api/v1/applications/{id}/cv` (Bearer), đúng đường dẫn frontend đang gọi (`candidateProfileService`, task 359). `{id}` là id **lần ứng tuyển** (`applications[].id` trong hồ sơ hợp nhất), không phải id hồ sơ ứng viên. Không thêm mã quyền, không migration. Mọi response, kể cả lỗi, có `Cache-Control: no-store`.

### Thứ tự kiểm và lỗi

| Trường hợp | Kết quả | Nhật ký |
|---|---|---|
| Không có token, token hết hạn, phiên bị thu hồi, tài khoản bị khóa | 401 `UNAUTHORIZED` | không |
| Không có `CANDIDATES_READ_ALL` hay `CANDIDATES_READ_SCOPED` (matcher, rồi `requireReader` đọc lại quyền từ database) | 403 `FORBIDDEN`, với mọi id, có thật hay không | không |
| `{id}` không phải UUID | 400 `VALIDATION_ERROR` | không |
| Lần ứng tuyển không tồn tại, hoặc không gắn với hồ sơ ứng viên nào (chỉ xảy ra khi sửa dữ liệu bằng SQL) | 404 `APPLICATION_NOT_FOUND` "Không tìm thấy hồ sơ ứng tuyển." | không |
| Có thật nhưng ngoài phạm vi người gọi (`CandidateAccess.findApplication`, cùng đoạn SQL `READABLE`) | 403 `FORBIDDEN`, kể cả khi lần đó không có tệp CV | một dòng `DENIED` `CV` có `application_id`, ghi sau khi transaction đọc đã kết thúc |
| Trong phạm vi nhưng không có tệp CV | 404 `APPLICATION_CV_NOT_FOUND` "Hồ sơ ứng tuyển này không có tệp CV." | không (không có gì được hiển thị) |
| Được phép | 200 với nội dung tệp | một dòng `GRANTED` `CV` có `application_id`, trong chính transaction đọc |

Bước 404 đứng trước bước phạm vi và chỉ phụ thuộc bản ghi; bước phạm vi đứng trước bước kiểm tệp, nên người ngoài phạm vi không biết được lần ứng tuyển có tệp hay không. Nội dung tệp (`application_cvs.content`) chỉ được đọc **sau** cả hai bước, bằng một câu SQL riêng. Phần đọc chạy trong một transaction REPEATABLE READ (read-write chỉ vì ghi nhật ký); `ApplicationCvService` không tự mở transaction bằng annotation, nên dòng `DENIED` được ghi sau khi transaction đọc đã kết thúc và một request không bao giờ giữ hai kết nối database. Tệp tối đa 10 MiB, trả nguyên trong bộ nhớ như mọi API tệp khác của dự án.

### Response 200

| Header | Giá trị |
|---|---|
| `Content-Type` | đúng loại đã lưu: `application/pdf` hoặc `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (V26 chỉ cho hai loại này); không bao giờ đoán từ tên tệp hay nội dung |
| `Content-Length` | số byte của tệp |
| `Content-Disposition` | `inline` cho PDF, `attachment` cho DOCX, rồi `filename="<tên ASCII>"`, rồi `filename*=UTF-8''<tên mã hóa>` |
| `X-Content-Type-Options` | `nosniff` (đúng một giá trị) |
| `Content-Security-Policy` | `default-src 'none'; sandbox` |
| `Cache-Control` | `no-store` |

Ví dụ với tệp "CV Nguyễn Văn Đức.pdf":

```text
Content-Disposition: inline; filename="CV Nguyen Van Duc.pdf"; filename*=UTF-8''CV%20Nguy%E1%BB%85n%20V%C4%83n%20%C4%90%E1%BB%A9c.pdf
```

Tên tệp (`CvContentDisposition`, viết tay):

- **Đuôi tệp theo loại đã lưu**, không theo tên lúc tải lên: `.pdf` hay `.docx` ở cuối tên (không phân biệt hoa thường) được thay, đuôi khác được giữ phía trước (`CV.exe` lưu dạng DOCX thành `CV.exe.docx`).
- **Tên ASCII** (`filename="…"`, đứng trước): tách dấu (NFD) rồi bỏ dấu, `đ`→`d`, `Đ`→`D`; mọi ký tự ngoài `A–Z a–z 0–9`, khoảng trắng và `. _ ( ) -` thành `_` (nhiều `_` liền nhau gộp một); bỏ khoảng trắng, dấu chấm và `_` ở hai đầu; tối đa 150 ký tự, giữ đuôi; không còn gì thì `cv.pdf` hoặc `cv.docx`. Vì vậy dấu nháy kép, dấu chấm phẩy, dấu `\` và xuống dòng không bao giờ vào được header.
- **Tên đầy đủ** (`filename*`, đứng sau): tên đã lưu với đuôi theo loại, mã hóa UTF-8 theo RFC 5987 (mọi byte ngoài chữ cái ASCII, chữ số, dấu huyền ngược và `! # $ & + - . ^ _ | ~` thành `%XX`). Trình duyệt hiểu `filename*` sẽ dùng tên tiếng Việt này khi lưu tệp.
- Header được viết tay vì `ContentDisposition` của Spring, khi có charset, viết `filename="=?UTF-8?Q?…?="` (RFC 2047), và mẫu `filename="?([^";]+)` của frontend sẽ lấy đúng chuỗi đó.

### Các header bảo vệ cái gì

`nosniff`, CSP `sandbox` và `no-store` bảo vệ khi **mở thẳng đường dẫn** (một trình quản lý tải xuống, hoặc sau này nếu có phiên bằng cookie): trình duyệt không được đoán loại khác (ví dụ HTML) từ nội dung, tệp mở trong tab không được tải gì khác hay chạy script, và không lưu vào bộ nhớ đệm. Hiện tại API cần Bearer, nên trình duyệt không tự mở được đường dẫn này. Frontend tải nội dung bằng axios thành một Blob rồi hiện qua URL `blob:`; URL đó dùng chính sách của trang frontend, không dùng các header trên. Khi đó an toàn dựa vào ba điều: Blob giữ đúng `Content-Type` của server, chỉ PDF được hiện ngay trong trang (DOCX chỉ tải về), và CV có nội dung tự động bị từ chối ngay khi nộp (mục dưới).

Cùng quy tắc này (thứ tự kiểm, nhật ký `CV`) phải dùng cho API gợi ý dữ liệu CV (task 334 của TRAN DUC LUONG): gọi `requireReader`, `findApplication`, `recordGranted(CV, …)` và `recordDenied(CV, …)` như `ApplicationCvService`.

### Ví dụ (PowerShell)

```powershell
$body = @{ email = 'hr@example.test'; password = '<mật khẩu>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
# <id>: id lần ứng tuyển, lấy từ applications[].id của hồ sơ hợp nhất
$response = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/applications/<id>/cv' -Headers $headers -OutFile cv.bin -PassThru
$response.Headers['Content-Type']
$response.Headers['Content-Disposition']
```

### Ghi chú cho frontend (task 359)

- Giữ `GET /applications/{id}/cv` với `responseType: 'blob'`. Tạo và dùng Blob với đúng `Content-Type` server trả (không để trống, không tự đoán từ tên tệp).
- Chỉ hiện ngay trong trang khi `Content-Type` là `application/pdf`; DOCX chỉ cho tải về, không đưa Blob DOCX vào iframe. Xem trước DOCX là câu hỏi 25, ý 10.
- Frontend đọc lần khớp đầu tiên của `filename="?([^";]+)`, nên nhận **tên ASCII**; muốn hiện tên tiếng Việt thì dùng `cvFileName` của hồ sơ hợp nhất. `Content-Disposition` đã được mở cho script khác origin (`Access-Control-Expose-Headers`).
- Bỏ đường dự phòng `cvFileUrl` (không tồn tại). Tiếp tục thu hồi object URL khi rời trang (`URL.revokeObjectURL`).
- **Không** thêm thuộc tính `sandbox` vào iframe hiện PDF: trình xem PDF có sẵn của Chrome được cho là không chạy trong khung bị sandbox (cần kiểm lại trên Chrome). Muốn cách ly mạnh hơn thì vẽ PDF bằng pdf.js lên canvas.
- Lỗi: 401 (đăng nhập lại), 403 (không có quyền xem CV này), 404 `APPLICATION_NOT_FOUND` hoặc `APPLICATION_CV_NOT_FOUND`. Với `responseType: 'blob'`, body lỗi là một Blob chứa JSON: đọc bằng `await error.response.data.text()` rồi lấy `code` và `message`.
- Form nộp hồ sơ (task 323, 324) và, từ task 351, form giới thiệu: hiện `message` của server khi nhận 400 `APPLICATION_CV_ACTIVE_CONTENT`.

## Nội dung tự động trong CV (task 362)

Task 362 là API đầu tiên đưa CV đã lưu tới máy của nhân viên, nên từ task này `CvValidator` (của TRAN DUC LUONG) từ chối ngay **khi nộp** những CV có nội dung mà Word hay trình đọc PDF tự chạy, tự mở hoặc tự tải từ bên ngoài. Trả lời: 400 `APPLICATION_CV_ACTIVE_CONTENT` "CV có nội dung tự động mở tệp, mẫu hoặc chương trình bên ngoài, hoặc có tệp đính kèm. Vui lòng lưu lại CV dưới dạng PDF thông thường rồi gửi lại." Body không nêu tên tệp, tên phần hay địa chỉ nào. CV hỏng vẫn trả `APPLICATION_CV_INVALID` như trước.

Quy tắc áp dụng cho mọi đường nộp CV: API nộp hồ sơ công khai và, từ task 351, API giới thiệu nội bộ. API gợi ý dữ liệu CV (task 334) chỉ đọc CV đã lưu nên cũng được bảo vệ. CV đã lưu trước task 362 không được kiểm lại (API nộp hồ sơ mặc định tắt và chưa merge, nên chỉ có thể có trong database test hoặc demo; chưa kiểm trên VPS).

### DOCX

Lần đọc XML an toàn sẵn có (SAX, không DOCTYPE) đọc thêm từng `Relationship` của **mọi** phần `.rels`. Quan hệ có `TargetMode` khác `Internal` (so sau khi bỏ khoảng trắng hai đầu, không phân biệt hoa thường; giá trị lạ cũng bị coi là `External`) chỉ được chấp nhận khi:

- là **liên kết web hoặc thư** (`Type` là quan hệ hyperlink của OOXML thường hoặc strict) và `Target` bắt đầu bằng `http://`, `https://` hoặc `mailto:` (không phân biệt hoa thường): bấm vào thì mở trình duyệt hoặc chương trình thư; hoặc
- là **quan hệ khác** (mẫu `attachedTemplate`, `oleObject`, `frame`, `subDocument`, ảnh liên kết…) và `Target` là đường dẫn trên ổ đĩa của máy người soạn, không có máy chủ: `file:///C:/…`, `file:///C:\…`, `C:\…` hoặc `C:/…` (một chữ cái ASCII làm ổ đĩa). CV làm từ mẫu Word có sẵn trên máy thường giữ dạng này, và Word mở trên máy khác thì không tìm thấy tệp đó.

Mọi phép so không phân biệt hoa thường ở đây chỉ áp dụng cho chữ cái ASCII: `ı` (i không chấm) và `ſ` (s dài) không được tính là `i`, `s`.

Mọi trường hợp khác bị từ chối: đường dẫn UNC (`\\máy-chủ\…`, `//máy-chủ/…`), `file://máy-chủ/…`, `file:////…`, địa chỉ web hay giao thức khác ở quan hệ không phải hyperlink, đích tương đối hoặc rỗng, và hyperlink tới `file:` (kể cả ổ đĩa cục bộ), UNC, `javascript:` hay giao thức khác. Lý do: mẫu hoặc khung trên máy chủ khác khiến Word tự tải nội dung của người gửi khi mở tệp; với đường dẫn UNC hoặc `file://máy-chủ`, Windows còn tự đăng nhập vào máy chủ đó và lộ mã băm NTLM của người đọc. Bộ đọc chỉ bật cờ; `validate` đổi cờ thành lỗi sau khi đọc xong, sau mọi kiểm tra cấu trúc của gói ZIP và trước khi POI mở gói.

**Hai cách đọc gói ZIP phải khớp nhau.** `ZipInputStream` (và POI) đọc các phần theo phần đầu cục bộ (local header), lần lượt từ đầu tệp; Word, như các chương trình đọc ZIP thông thường, tìm các phần qua thư mục trung tâm (central directory) ở cuối tệp. Một tệp có thể cho hai cách đọc thấy hai nội dung khác nhau: một phần chỉ đổi tên ở local header (ví dụ `word/_rels/settings.xmlA.xml` thay cho `word/_rels/settings.xml.rels`, nên được đọc như XML thường), các phần nằm sau một chữ ký thư mục trung tâm giả (`PK\1\2`) làm lần đọc tuần tự dừng sớm, hoặc thư mục trung tâm trỏ tới một local header thứ hai giấu trong dữ liệu của phần khác. Vì vậy, sau lần đọc tuần tự, `CvValidator` mở lại gói qua thư mục trung tâm (commons-compress, có sẵn qua POI): thư mục trung tâm phải liệt kê đúng các phần đã đọc, cùng tên, cùng thứ tự (tên do trường Unicode Path của Info-ZIP đặt cũng được tính), nếu không là `APPLICATION_CV_INVALID`; sau đó mọi phần `.rels` được đọc lại từ chỗ thư mục trung tâm trỏ tới, giữ giới hạn 5 MiB mỗi phần và 100 MiB tổng (các mục của thư mục trung tâm có thể dùng chung dữ liệu), và được kiểm như trên. Quan hệ không được phép ở bất kỳ cách đọc nào đều làm CV bị từ chối; lỗi cấu trúc, kể cả hai cách đọc không khớp, vẫn được báo trước.

### PDF

Sau khi PDFBox đọc tệp, mọi đối tượng gián tiếp trong bảng tham chiếu chéo, **kể cả đối tượng nằm trong object stream nén** (PDFBox 3 lưu mặc định như vậy), và trailer được duyệt cùng các từ điển, mảng trực tiếp bên trong (một tham chiếu gián tiếp được duyệt như đối tượng riêng của nó). Bị từ chối khi có:

- hành động `/S /Launch` (mở chương trình hay tệp) hoặc `/S /JavaScript`;
- khóa `/JS`, `/JavaScript` (cây tên JavaScript của tài liệu), `/EmbeddedFiles` hoặc `/EF` (tệp đính kèm);
- `/Type /EmbeddedFile` hoặc chú thích `/Subtype /FileAttachment`.

Liên kết `/URI` vẫn được chấp nhận. JavaScript bị từ chối **tạm thời**, chờ BA/PO (câu hỏi 25, ý 11). Nội dung của các luồng (stream) không bao giờ được giải mã để kiểm; chỉ từ điển của luồng được xem. Việc duyệt có giới hạn và đóng an toàn: sâu quá 64 tầng trực tiếp, quá 1.000.000 nút, hoặc một đối tượng trong bảng tham chiếu chéo mà PDFBox không đọc được (một trình đọc dễ dãi có thể dựng lại bảng và vẫn chạy được nó) đều trả `APPLICATION_CV_INVALID`.

### Rủi ro còn lại

- DOCX: giả định Word tìm phần và dữ liệu của phần theo đặc tả ZIP, qua thư mục trung tâm (tên trong thư mục trung tâm, dữ liệu sau local header mà nó trỏ tới); chưa kiểm được với Word thật. Một trình đọc làm khác (ví dụ tự sửa tệp hỏng bằng cách dò lại các local header) có thể thấy phần khác; các phần mà lần đọc tuần tự thấy cũng đã được kiểm.
- DOCX: đối tượng OLE và ActiveX **nhúng bên trong** tệp (không phải liên kết ra ngoài) không bị từ chối; người đọc phải bấm đúp và Word cảnh báo. Mã trường trong nội dung (`INCLUDEPICTURE`, `INCLUDETEXT`, `DDEAUTO`) không nằm trong `.rels` nên không được kiểm.
- PDF: hành động `GoToR`, `SubmitForm`, `ImportData` và biểu mẫu XFA (script nằm trong nội dung luồng) không được kiểm; đối tượng không có trong bảng tham chiếu chéo không được duyệt.
- Lỗi của chính trình đọc (khai thác bộ phân tích) và mã độc: không có gì được quét (câu hỏi 25, ý 11).
- Từ chối nhầm: CV làm từ mẫu Word nằm trên máy chủ hoặc thư mục mạng dùng chung, PDF có JavaScript của biểu mẫu, và PDF hơi hỏng mà trước đây vẫn qua (một đối tượng không đọc được) bị từ chối; thông báo hướng dẫn ứng viên lưu lại dưới dạng PDF thông thường.

## Kiểm thử

- `CandidateProfileMigrationTest`: nâng cấp từ migration ngay trước V32 với ba hồ sơ ứng tuyển viết bằng SQL (hai hồ sơ ở hai yêu cầu, email gõ khác nhau `A@Example.TEST` và `a@example.test` giữa hai NBSP, cùng email chuẩn hóa; một hồ sơ của người khác) → đúng hai hồ sơ ứng viên, email chuẩn hóa trùng email của các hồ sơ ứng tuyển, ba liên kết, `created_at` là lần nhận sớm nhất, `linked_at` là thời điểm nhận; `job_applications` không đổi; Flyway `validate()` qua. Trên database dừng ở V32: unique email, CHECK email, mỗi lần ứng tuyển một liên kết, hai khóa ngoại, xóa hồ sơ ứng viên còn liên kết bị từ chối (23503), xóa hồ sơ ứng tuyển xóa liên kết và giữ hồ sơ ứng viên.
- `CandidateLinkIntegrationTest`: `store()` liên kết hai lần ứng tuyển của `" A@EXAMPLE.test "` (giữa hai NBSP) và `a@example.test` vào một hồ sơ ứng viên, người khác vào hồ sơ khác; hồ sơ trùng (409) không tạo liên kết hay hồ sơ ứng viên; hai lần ứng tuyển đầu tiên cùng email vào hai yêu cầu chạy cùng lúc: lần sau chờ đúng ở câu chèn `candidates` (kiểm bằng `pg_blocking_pids`), lần trước commit → một hồ sơ ứng viên, hai liên kết; rollback → lần sau tự tạo; rollback của caller và lỗi chèn CV không để lại hồ sơ ứng viên; hồ sơ nộp qua `PostgresApplicationReceiptWriter` được liên kết; lần ứng tuyển đầu tiên qua lần kiểm hạn lúc 23:59:59 của ngày hết hạn rồi chờ lần kia tới sau nửa đêm → 404 `JOB_POSTING_NOT_FOUND`, không còn hồ sơ ứng tuyển, CV hay liên kết nào của nó, chỉ một hồ sơ ứng viên.
- Các test của module hồ sơ ứng tuyển (lưu trữ, nộp hồ sơ, xác nhận, hạn mức IP, pipeline) chạy lại không đổi.
- `CandidateAccessIntegrationTest` (task 363): cây phòng ban TOP → PARENT → CHILD và SIBLING, ba lần ứng tuyển (hai của cùng một người ở CHILD và SIBLING), người đọc lấy từ phiên thật qua `requireReader`. HR_MANAGER và ADMIN đọc tất cả (ghim có chủ ý, câu hỏi 25 ý 4); recruiter chính và hỗ trợ chỉ đọc yêu cầu của mình, recruiter khác không đọc gì; bàn giao chuyển quyền ngay; Approver (V33_1) không đọc gì khi không có liên hệ, đọc được khi là trưởng phòng ban hoặc recruiter được phân công; trưởng phòng ban đọc phòng ban mình và mọi phòng ban con cháu, trưởng phòng ban ngang hàng thì không; người tạo yêu cầu không đọc được; Interviewer chỉ đọc khi là trưởng phòng ban; tài khoản không vai trò 403; token hết hạn, phiên bị thu hồi, tài khoản bị khóa hoặc bị vô hiệu hóa 401; gỡ hoặc thêm quyền có hiệu lực ở lần gọi sau; với mọi cặp người đọc × lần ứng tuyển, kết quả SQL bằng quy tắc Java (`isAssigned`, `findManagedDepartmentIds`), và danh sách luôn khớp `findApplication`; nhật ký: một dòng `GRANTED` cho mỗi lần ứng tuyển, thời điểm cắt tới micro giây, rollback không để lại dòng; một dòng `DENIED` đã commit, ném lỗi nếu còn transaction; `UPDATE`/`DELETE` bị chặn (23514), `TRUNCATE` được; các CHECK.
- `RolePermissionSeedMigrationTest`: `EXPECTED_GRANTS` của APPROVER có `CANDIDATES_READ_SCOPED` thay cho `CANDIDATES_READ_ALL`; chỉ HR_MANAGER và ADMIN có `CANDIDATES_READ_ALL`; vẫn 106 dòng.
- `CandidateProfileIntegrationTest` (task 361, HTTP thật): không token 401; không có quyền đọc ứng viên 403 với id có thật lẫn id lạ, không ghi nhật ký; id lạ và hồ sơ không còn lần ứng tuyển nào 404 `CANDIDATE_NOT_FOUND` với cả sáu vai trò có quyền, id sai định dạng 400; người đọc `SCOPED` không có lần nào trong phạm vi (Recruiter, Approver, Interviewer) 403 và đúng một dòng `DENIED` không có lần ứng tuyển; HR_MANAGER và ADMIN thấy cả ba lần ứng tuyển, mới nhất trước, đúng tập khóa của phần đầu và của từng lần (khóa trống bị bỏ), nguồn, người phụ trách, thư giới thiệu, thông tin CV; recruiter chính, hỗ trợ và trưởng phòng ban chỉ thấy lần trong phạm vi; lần ngoài phạm vi không để lộ id, tin, tên vị trí, phòng ban, người phụ trách, ngày hay giai đoạn, và `createdAt`/`updatedAt` lấy từ lần được đọc; hồ sơ "cài" bằng email người khác chỉ hiện cho recruiter của nó đúng hồ sơ đó; khác họ tên hoặc khác số điện thoại làm phần đầu `null` và `contactMismatch`, phần đầu tính trên phạm vi người đọc; response không chứa nội dung, mã băm CV, mã tra cứu hay mã băm của nó, email chuẩn hóa, email nhân viên, `requisitionId`, `cvFileUrl`; mỗi lần đọc ghi một dòng `GRANTED` cho mỗi lần ứng tuyển được trả; yêu cầu đã tuyển đủ hoặc hủy vẫn được liệt kê, tên vị trí là bản chụp, tên phòng ban là tên hiện tại, recruiter hỗ trợ không bao giờ hiện là recruiter.
- `CvContentDispositionTest` (task 362): "CV Nguyễn Văn Đức.pdf" cho `filename="CV Nguyen Van Duc.pdf"` và `filename*` đúng từng byte; dạng tách dấu (NFD) và `đ`; dấu nháy kép, chấm phẩy, `\`, CR/LF, dấu phân cách dòng Unicode thành `_` (header vẫn đúng hai tham số); tên chỉ có ký hiệu hoặc không có ký tự nhìn thấy thành `cv.pdf`/`cv.docx`; đuôi thiếu, sai hoặc viết hoa theo loại đã lưu; cắt còn 150 ký tự, giữ đuôi; mẫu `filename="?([^";]+)` của frontend lấy tên ASCII; mỗi loại đã lưu có đúng kiểu hiển thị và đuôi (PDF `inline`, DOCX `attachment`), loại lạ ném lỗi; `filename*` chỉ giữ attr-char.
- `CvActiveContentTest` (task 362): DOCX có mẫu `attachedTemplate` ngoài tới https, http, UNC (`\\máy-chủ`, `//máy-chủ`, `\máy-chủ`), `file://máy-chủ`, `file:////`, ftp, đích tương đối hoặc rỗng bị từ chối; mẫu trên ổ đĩa cục bộ (`file:///C:/…`, `file:///c:\…`, `C:\…`, `d:/…`) và ảnh liên kết `C:\img\a.png` được nhận; `oleObject`, `frame`, `subDocument`, ảnh tới máy chủ bị từ chối; `TargetMode` viết thường, có khoảng trắng hoặc lạ bị coi là External; chữ giống chữ ASCII (`ı`, `ſ`) không làm `TargetMode` thành Internal hay giao thức thành web, thư hoặc ổ cục bộ; hyperlink http, https, mailto (cả bản strict, cả chữ hoa) được nhận, hyperlink `file:` (kể cả ổ cục bộ), UNC, `javascript:`, ftp, rỗng bị từ chối; mọi phần `.rels` đều được đọc; lỗi cấu trúc vẫn là `APPLICATION_CV_INVALID`. Gói ZIP viết tay từng byte: một phần chỉ đổi tên ở local header (có hoặc không có mẫu), các phần sau chữ ký `PK\1\2` giả (kể cả khi lần đọc tuần tự cũng thấy mẫu), thư mục trung tâm theo thứ tự khác, trường Unicode Path đặt tên khác đều là `APPLICATION_CV_INVALID`; phần `.rels` có mẫu giấu trong dữ liệu của phần khác bị phát hiện dù chỉ thư mục trung tâm hay chỉ lần đọc tuần tự thấy nó; lần đọc thứ hai giữ giới hạn 5 MiB mỗi phần và 100 MiB tổng (21 mục cùng trỏ tới một phần 5 MiB); cùng gói viết tay không có mẹo thì được nhận. PDF có OpenAction JavaScript, Launch trên liên kết, tệp đính kèm trong cây tên, chú thích FileAttachment, cây tên JavaScript, JavaScript trong object stream nén (và cùng tài liệu không nén) bị từ chối; `/S` gián tiếp và tên viết bằng `#xx` vẫn bị phát hiện; đối tượng không đọc được, quá 64 tầng hoặc quá 1.000.000 nút là `APPLICATION_CV_INVALID`; liên kết URI, PDF của `CvValidatorTest` và tệp biên đúng 10 MiB được nhận; body lỗi không chứa tên tệp. `CvValidatorTest` của TRAN DUC LUONG chạy lại không đổi.
- `CandidateCvIntegrationTest` (task 362, HTTP thật): PDF 200 đúng từng byte với `Content-Type`, `Content-Length`, `Content-Disposition` (`inline`, tên ASCII trước), đúng một `nosniff`, CSP, `no-store`, một dòng `GRANTED` `CV`; DOCX là `attachment` với loại DOCX; loại đã lưu thắng tên tệp sai đuôi; recruiter chính và trưởng phòng ban đọc được CV trong phạm vi, `Content-Disposition` được mở cho CORS; ngoài phạm vi 403 với một dòng `DENIED` có `application_id`, kể cả khi lần đó không có tệp; lần ứng tuyển lạ hoặc không gắn hồ sơ ứng viên 404 `APPLICATION_NOT_FOUND` với mọi người đọc, id sai định dạng 400, không ghi nhật ký; không có tệp 404 `APPLICATION_CV_NOT_FOUND`; tài khoản không vai trò 403, không token 401; mỗi lần đọc ghi lại; API nộp hồ sơ công khai trả 400 `APPLICATION_CV_ACTIVE_CONTENT` cho DOCX có mẫu từ xa và không lưu gì, cùng tài liệu không có mẫu thì 201.
- `ApplicationWithdrawalIntegrationTest` (task 345, HTTP thật): sau khi ứng viên rút lần ứng tuyển vào yêu cầu Java, recruiter chính của yêu cầu đó thấy `stage: "WITHDRAWN"`, `withdrawnAt` (cắt tới micro giây) và `withdrawalReason` giữ xuống dòng, `updatedAt` là `withdrawnAt`; recruiter của yêu cầu khác chỉ thấy lần ứng tuyển của mình, không có khóa rút hồ sơ, `updatedAt` là ngày nộp, và body không chứa lý do, thời điểm rút hay id của lần đã rút; HR_MANAGER thấy cả hai lần với `updatedAt` là `withdrawnAt`. `CandidateProfileIntegrationTest` chạy lại không đổi (lần chưa rút không có hai khóa này).

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 25, ý (1)–(11); câu hỏi 3 và 6 (Approver, Admin); câu hỏi 19, ý 8 (recruiter thấy cả hồ sơ hay chỉ đơn vào yêu cầu của mình).
