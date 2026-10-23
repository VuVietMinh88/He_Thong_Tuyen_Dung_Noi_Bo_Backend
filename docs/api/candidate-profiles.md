# Hồ sơ ứng viên hợp nhất

Phạm vi TKNHTTDNB1-360 (một hồ sơ ứng viên liên kết mọi lần ứng tuyển của cùng một người), story TKNHTTDNB1-48 (S4-09): một trang gồm thông tin cá nhân, CV xem trước ngay trong trang, lịch sử ứng tuyển các vị trí, nhãn và ghi chú; hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này; chỉ recruiter phụ trách và hiring manager của vị trí đó mở được. Task 360 chỉ thêm phần dữ liệu (migration V32 và việc liên kết khi lưu hồ sơ). Task 363 thêm quy tắc ai được xem và nhật ký truy cập (migration V33, V33_1), vẫn **chưa có API đọc**: API hồ sơ và lịch sử ứng tuyển là task 361, xem CV là task 362.

Tiêu chí của story S4-09:

- "Hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này": phần dữ liệu đã có (360). Mỗi lần ứng tuyển được gắn với đúng một hồ sơ ứng viên theo email đã chuẩn hóa; API hiển thị là task 361.
- "Một trang gồm thông tin cá nhân, CV xem trước…": chưa có (361, 362).
- "Chỉ recruiter phụ trách và hiring manager của vị trí đó mở được": quy tắc và nhật ký đã có (363), các API 361, 362 sẽ dùng. **Chưa đạt với HR_MANAGER và ADMIN** (giữ `CANDIDATES_READ_ALL`, câu hỏi 6 và 25 ý 4), xem [Ai được xem](#ai-được-xem-task-363).

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

- API đọc hồ sơ ứng viên, lịch sử ứng tuyển và CV: task 361, 362 (quyền xem và nhật ký truy cập đã có từ task 363).
- Gộp hồ sơ ứng viên theo số điện thoại hoặc gộp tay, so sánh cạnh nhau, hoàn tác trong 24 giờ: S5-01.
- Xác minh email trước khi gộp (câu hỏi 25, ý 1).
- Xóa hồ sơ ứng viên theo yêu cầu của chính ứng viên (câu hỏi 25, ý 3).
- Nhãn và ghi chú: S5-04.

## Ai được xem (task 363)

Task 363 thêm quy tắc ai được đọc hồ sơ ứng viên và CV, cùng nhật ký mọi lần đọc. Chưa có API nào dùng: API hồ sơ (361) và CV (362) là nơi đầu tiên gọi. Quy tắc nằm ở một chỗ, `vn.ttcs.recruitment.candidate.CandidateAccess`; không thêm mã quyền, không thêm route.

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
- Quyền tính theo **từng lần ứng tuyển**: một người nộp vào nhiều yêu cầu thì người đọc `SCOPED` chỉ được đọc các lần thuộc phạm vi của mình (API 361 quyết định phần hiển thị).
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

## Kiểm thử

- `CandidateProfileMigrationTest`: nâng cấp từ migration ngay trước V32 với ba hồ sơ ứng tuyển viết bằng SQL (hai hồ sơ ở hai yêu cầu, email gõ khác nhau `A@Example.TEST` và `a@example.test` giữa hai NBSP, cùng email chuẩn hóa; một hồ sơ của người khác) → đúng hai hồ sơ ứng viên, email chuẩn hóa trùng email của các hồ sơ ứng tuyển, ba liên kết, `created_at` là lần nhận sớm nhất, `linked_at` là thời điểm nhận; `job_applications` không đổi; Flyway `validate()` qua. Trên database dừng ở V32: unique email, CHECK email, mỗi lần ứng tuyển một liên kết, hai khóa ngoại, xóa hồ sơ ứng viên còn liên kết bị từ chối (23503), xóa hồ sơ ứng tuyển xóa liên kết và giữ hồ sơ ứng viên.
- `CandidateLinkIntegrationTest`: `store()` liên kết hai lần ứng tuyển của `" A@EXAMPLE.test "` (giữa hai NBSP) và `a@example.test` vào một hồ sơ ứng viên, người khác vào hồ sơ khác; hồ sơ trùng (409) không tạo liên kết hay hồ sơ ứng viên; hai lần ứng tuyển đầu tiên cùng email vào hai yêu cầu chạy cùng lúc: lần sau chờ đúng ở câu chèn `candidates` (kiểm bằng `pg_blocking_pids`), lần trước commit → một hồ sơ ứng viên, hai liên kết; rollback → lần sau tự tạo; rollback của caller và lỗi chèn CV không để lại hồ sơ ứng viên; hồ sơ nộp qua `PostgresApplicationReceiptWriter` được liên kết; lần ứng tuyển đầu tiên qua lần kiểm hạn lúc 23:59:59 của ngày hết hạn rồi chờ lần kia tới sau nửa đêm → 404 `JOB_POSTING_NOT_FOUND`, không còn hồ sơ ứng tuyển, CV hay liên kết nào của nó, chỉ một hồ sơ ứng viên.
- Các test của module hồ sơ ứng tuyển (lưu trữ, nộp hồ sơ, xác nhận, hạn mức IP, pipeline) chạy lại không đổi.
- `CandidateAccessIntegrationTest` (task 363): cây phòng ban TOP → PARENT → CHILD và SIBLING, ba lần ứng tuyển (hai của cùng một người ở CHILD và SIBLING), người đọc lấy từ phiên thật qua `requireReader`. HR_MANAGER và ADMIN đọc tất cả (ghim có chủ ý, câu hỏi 25 ý 4); recruiter chính và hỗ trợ chỉ đọc yêu cầu của mình, recruiter khác không đọc gì; bàn giao chuyển quyền ngay; Approver (V33_1) không đọc gì khi không có liên hệ, đọc được khi là trưởng phòng ban hoặc recruiter được phân công; trưởng phòng ban đọc phòng ban mình và mọi phòng ban con cháu, trưởng phòng ban ngang hàng thì không; người tạo yêu cầu không đọc được; Interviewer chỉ đọc khi là trưởng phòng ban; tài khoản không vai trò 403; token hết hạn, phiên bị thu hồi, tài khoản bị khóa hoặc bị vô hiệu hóa 401; gỡ hoặc thêm quyền có hiệu lực ở lần gọi sau; với mọi cặp người đọc × lần ứng tuyển, kết quả SQL bằng quy tắc Java (`isAssigned`, `findManagedDepartmentIds`), và danh sách luôn khớp `findApplication`; nhật ký: một dòng `GRANTED` cho mỗi lần ứng tuyển, thời điểm cắt tới micro giây, rollback không để lại dòng; một dòng `DENIED` đã commit, ném lỗi nếu còn transaction; `UPDATE`/`DELETE` bị chặn (23514), `TRUNCATE` được; các CHECK.
- `RolePermissionSeedMigrationTest`: `EXPECTED_GRANTS` của APPROVER có `CANDIDATES_READ_SCOPED` thay cho `CANDIDATES_READ_ALL`; chỉ HR_MANAGER và ADMIN có `CANDIDATES_READ_ALL`; vẫn 106 dòng.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 25, ý (1)–(6); câu hỏi 3 và 6 (Approver, Admin).
