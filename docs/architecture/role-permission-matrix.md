# Ma trận vai trò và quyền

## Mục đích và trạng thái

Tài liệu phục vụ Jira TKNHTTDNB1-119 "Xác định danh sách Role và Permission" và TKNHTTDNB1-120 "Xác định quyền của từng Role" thuộc story TKNHTTDNB1-14. Cả hai task yêu cầu kết hợp với BA/PO.

**Trạng thái: đề xuất của nhóm backend, chờ BA/PO xác nhận. Đây chưa phải bản chốt.** Nội dung mô tả đúng những gì database đang cấp (Flyway V3, V5 và V7_1) và những gì server đang kiểm. Sau khi BA/PO trả lời các câu hỏi ở mục 8, backend sẽ điều chỉnh bằng migration mới theo mục 4.

Nguồn: bảng `2. User Roles` của đặc tả "HỆ THỐNG TUYỂN DỤNG NỘI BỘ", Jira TKNHTTDNB1-14 và TKNHTTDNB1-205. Cơ chế kiểm quyền được mô tả trong [thiết kế phân quyền](authorization.md). Test `RolePermissionSeedMigrationTest` so dữ liệu seed với hằng `EXPECTED_GRANTS` viết tay trong test, là bản chép lại mục 5.1 và 5.2. Migration đổi quyền mà chưa sửa `EXPECTED_GRANTS` sẽ làm test thất bại. `ApiAuthorizationMatrixIntegrationTest` đọc lại chính hằng này để biết vai trò nào được gọi API nào, nên quyền mong đợi chỉ được viết ở một chỗ. Test không đọc file này, nên người đổi ma trận phải tự cập nhật tài liệu trong cùng thay đổi (mục 4).

## 1. Bảy vai trò

| # | Mã | Tên (cột `display_name`) | Tài khoản nội bộ, đăng nhập | Mục đích theo bảng nguồn |
|---|---|---|---|---|
|1|`CANDIDATE`|Ứng viên|Không (`internal=false`)|Nộp CV, theo dõi hồ sơ, xác nhận lịch, phản hồi offer|
|2|`RECRUITER`|Nhân viên tuyển dụng|Có|Vận hành tuyển dụng hằng ngày: sàng lọc CV, điều phối pipeline, đặt lịch, soạn offer|
|3|`HIRING_MANAGER`|Trưởng bộ phận|Có|Sở hữu vị trí: tạo yêu cầu tuyển dụng, xem ứng viên của vị trí mình, quyết định tuyển|
|4|`INTERVIEWER`|Người phỏng vấn|Có|Xem lịch, đọc CV, nộp phiếu đánh giá theo khung năng lực|
|5|`HR_MANAGER`|Trưởng phòng Nhân sự|Có|Giám sát mọi vị trí, phân công recruiter, ngân sách headcount, báo cáo|
|6|`APPROVER`|Người duyệt|Có|Ban giám đốc hoặc cấp duyệt theo hạn mức: duyệt yêu cầu tuyển dụng và offer vượt hạn mức lương|
|7|`ADMIN`|Quản trị hệ thống|Có|Quản lý tài khoản, vai trò, danh mục dùng chung, xem nhật ký|

Bảng `user_roles` chỉ nhận sáu vai trò nội bộ, vì vậy một tài khoản nội bộ không thể mang vai trò `CANDIDATE`. Một tài khoản có thể có nhiều vai trò.

## 2. Danh mục quyền

### Quy tắc đặt tên

Mã quyền nghiệp vụ có dạng `<MODULE>_<READ|WRITE>_<ALL|SCOPED>`, ví dụ `CANDIDATES_READ_SCOPED`.

- `READ`: xem dữ liệu của module.
- `WRITE`: tạo, sửa và chuyển trạng thái dữ liệu của module. Mọi vai trò có `WRITE` cũng có `READ` cùng phạm vi.
- `ALL`: mọi bản ghi của module.
- `SCOPED`: chỉ những bản ghi gắn với người dùng. Ý nghĩa cụ thể theo từng module ở bảng dưới. Server bắt buộc phải lọc dữ liệu theo phạm vi này; frontend không tự lọc thay.

Có 11 module × 2 thao tác × 2 phạm vi = 44 mã: V3 tạo 40 mã của mười module đầu, V7_1 thêm 4 mã của `SALARY_RANGES`. Database tạo đủ 44 mã, kể cả các mã chưa vai trò nào được cấp, ví dụ `ORGANIZATION_READ_SCOPED`. Khi cần cấp thêm quyền, chỉ cần thêm dòng `role_permissions`.

| Module | Dòng trong bảng nguồn | `SCOPED` nghĩa là (đề xuất) | API backend hiện có |
|---|---|---|---|
|`ORGANIZATION`|Danh mục tổ chức & vị trí|Chưa vai trò nào dùng; nếu cần, đề xuất là phòng ban mình phụ trách|Phòng ban, cây tổ chức (195–196), xóa phòng ban không còn được dùng (197), kiểm quyền ghi phòng ban (198), chức danh (203), khung năng lực (212), gán khung năng lực cho chức danh (214), tiêu chí đánh giá theo chức danh (215), câu hỏi phỏng vấn (221), tìm kiếm/lọc câu hỏi phỏng vấn (223), danh mục tuyển dụng dùng chung (228–231)|
|`REQUISITIONS`|Yêu cầu tuyển dụng|Hiring Manager: yêu cầu của bộ phận mình. Recruiter: yêu cầu được phân công. Approver: yêu cầu được chuyển cho mình duyệt|Tạo nháp (244); xem danh sách, chi tiết và sửa nháp (245), `SCOPED` lọc theo phòng ban phụ trách; phòng ban ghi vào body khi tạo/sửa cũng phải thuộc phạm vi đó (249)|
|`JOB_POSTINGS`|Tin tuyển dụng|Recruiter: tin của vị trí được phân công|Trang giới thiệu công ty trên cổng tuyển dụng (237), chỉ dùng `JOB_POSTINGS_WRITE_ALL`; chưa có API tin tuyển dụng|
|`CANDIDATES`|Hồ sơ ứng viên & pipeline|Recruiter: ứng viên của vị trí được phân công. Hiring Manager: ứng viên của vị trí mình sở hữu. Interviewer: ứng viên trong vòng mình phỏng vấn. Candidate: hồ sơ của chính mình|Chưa có|
|`INTERVIEWS`|Lịch phỏng vấn|Interviewer: vòng mình tham gia. Hiring Manager: vị trí mình sở hữu. Candidate: lịch của chính mình|Chưa có|
|`EVALUATIONS`|Phiếu đánh giá|Interviewer: phiếu của mình ở vòng mình tham gia. Hiring Manager: phiếu thuộc vị trí mình sở hữu|Chưa có|
|`OFFERS`|Offer & onboarding|Recruiter: offer của vị trí được phân công. Approver: offer chờ mình duyệt. Hiring Manager: offer của vị trí mình. Candidate: offer gửi cho mình|Chưa có|
|`NOTIFICATIONS`|Email & thông báo|Thông báo gửi cho chính mình hoặc thuộc vị trí mình liên quan|Chưa có|
|`REPORTS`|Báo cáo & dashboard|Recruiter: số liệu vị trí được phân công. Hiring Manager: số liệu vị trí mình sở hữu|Chưa có|
|`USER_ADMIN`|Người dùng & nhật ký|Chưa vai trò nào dùng|Tài khoản, vai trò, khóa tài khoản (145–166)|
|`SALARY_RANGES`|Không có dòng riêng; tách khỏi "Danh mục tổ chức & vị trí" theo Jira 205 (V7_1)|Chưa định nghĩa, chưa vai trò nào dùng; server coi như không có quyền|Dải lương `salaryMin`/`salaryMax` của chức danh (205)|

Cột `SCOPED` là đề xuất của backend. Module đầu tiên kiểm `SCOPED` theo từng bản ghi là yêu cầu tuyển dụng (task 245, 249): backend tạm hiểu `REQUISITIONS_*_SCOPED` là yêu cầu của phòng ban người gọi phụ trách (`departments.manager_user_id`), tính cả phòng ban con cháu, cho cả ba vai trò HIRING_MANAGER, RECRUITER, APPROVER; người tạo không quyết định quyền xem. Từ task 249, phòng ban ghi vào body khi tạo/sửa cũng phải thuộc phạm vi đó. Các module khác chưa có API dùng `SCOPED`. Xem [API yêu cầu tuyển dụng](../api/requisitions.md).

### Quyền tự phục vụ

| Mã | Dùng cho | Migration |
|---|---|---|
|`SELF_PROFILE_READ`|Xem tài khoản, quyền và hồ sơ của chính mình|V3|
|`SELF_PROFILE_WRITE`|Sửa hồ sơ cá nhân của chính mình|V5|
|`SELF_SECURITY_WRITE`|Đăng xuất, đổi mật khẩu|V3|

Ba mã này được cấp cho cả sáu vai trò nội bộ và không cấp cho `CANDIDATE`. Bảng `permissions` có tổng cộng 47 mã (44 mã nghiệp vụ và 3 mã tự phục vụ).

## 3. Chuyển ký hiệu bảng nguồn sang mã quyền

| Ký hiệu | Ý nghĩa trong bảng nguồn | Mã được cấp cho module |
|---|---|---|
|F|Toàn quyền|`_READ_ALL`, `_WRITE_ALL`|
|W|Ghi trong phạm vi được giao|`_READ_SCOPED`, `_WRITE_SCOPED`|
|W*|Ghi, chỉ dữ liệu của mình/vị trí mình/vòng mình tham gia|`_READ_SCOPED`, `_WRITE_SCOPED`|
|R|Chỉ xem|`_READ_ALL`|
|R*|Chỉ xem dữ liệu của mình/vị trí mình/vòng mình tham gia|`_READ_SCOPED`|
|–|Không truy cập|Không cấp|

V3 xử lý `W` giống `W*`, vì chú thích của bảng nguồn ghi `W` là "trong phạm vi được giao". Database hiện không phân biệt "phạm vi được giao" với "của chính mình". Sự khác nhau đó chỉ thể hiện ở cách từng API lọc dữ liệu sau này. Xem câu hỏi 2.

## 4. Cách thay đổi ma trận

1. Tạo migration mới trong `database/migrations`, dùng số phiên bản lớn hơn migration mới nhất đang có (hiện là V13) và chưa được giữ chỗ cho subtask khác (V12 đang được giữ chỗ cho các subtask làm song song). **Không sửa các migration đã có (V1–V11, V13)**, đặc biệt V3, V5 và V7_1. Các migration này đã chạy trên database của các thành viên; sửa lại sẽ làm Flyway báo lỗi checksum.
2. Thay đổi quyền bằng `INSERT`/`DELETE` trên `role_permissions`. Chỉ thêm dòng vào `permissions` khi cần mã mới, như V7_1 đã thêm bốn mã `SALARY_RANGES_*` cho dải lương.

   ```sql
   -- Ví dụ minh họa, không phải quyết định đã chốt
   DELETE FROM role_permissions WHERE role_code = 'APPROVER' AND permission_code = 'CANDIDATES_READ_ALL';
   INSERT INTO role_permissions (role_code, permission_code) VALUES ('APPROVER', 'CANDIDATES_READ_SCOPED');
   ```

3. Trong cùng thay đổi, cập nhật `EXPECTED_GRANTS` trong `src/test/java/vn/ttcs/recruitment/auth/RolePermissionSeedMigrationTest.java` và mục 5–7 của tài liệu này. `ApiAuthorizationMatrixIntegrationTest` tự đọc `EXPECTED_GRANTS`, không cần sửa quyền ở enum `Identity`; chạy thêm test này (hoặc `verify` đầy đủ) để thấy API nào đổi kết quả cho phép/từ chối. Test chỉ kiểm `EXPECTED_GRANTS`, không kiểm tài liệu, nên phải sửa tài liệu bằng tay. Nếu điều kiện của endpoint thay đổi, sửa thêm `SecurityConfiguration`, phần kiểm lại trong service tương ứng (`AccountProvisioningService`, `AccountManagementService`, `AccountRoleService`, `AccountLockService` cho tài khoản; `DepartmentService` cho phòng ban; `PositionService` cho chức danh và việc ẩn dải lương; `CompetencyFrameworkService` cho khung năng lực; `EvaluationCriteriaService` cho tiêu chí đánh giá theo chức danh; `InterviewQuestionService` cho câu hỏi phỏng vấn; `RequisitionService` cho yêu cầu tuyển dụng; `RecruitmentCatalogService` cho danh mục tuyển dụng; `CompanyProfileService` cho trang giới thiệu công ty; `ProfileService` cho hồ sơ cá nhân) và [thiết kế phân quyền](authorization.md).
4. Thêm vai trò mới cần thêm bước: trong migration mới, `INSERT INTO roles` (vì `user_roles` và `role_permissions` có khóa ngoại tới `roles`) và sửa ràng buộc CHECK của `user_roles`; thêm giá trị vào enum `Role` trong Java; thêm vai trò vào `EXPECTED_GRANTS` và thêm một hằng cho vai trò đó vào enum `Identity` của `ApiAuthorizationMatrixIntegrationTest`.
5. Chạy `./mvnw.cmd test -Dtest=RolePermissionSeedMigrationTest` (Windows) hoặc `sh ./mvnw test -Dtest=RolePermissionSeedMigrationTest` ở thư mục gốc của repo Backend. Sau đó chạy `verify` đầy đủ trước khi tạo Pull Request.

Sau khi migrate, quyền mới có hiệu lực ở yêu cầu kế tiếp. Người dùng không cần đăng nhập lại; frontend chỉ cần tải lại danh sách quyền.

## 5. Ma trận theo vai trò

### 5.1 Ký hiệu nguồn và quyền đã seed

Viết tắt: RA = `_READ_ALL`, WA = `_WRITE_ALL`, RS = `_READ_SCOPED`, WS = `_WRITE_SCOPED`. Mã đầy đủ là tên module ghép với hậu tố, ví dụ `CANDIDATES` + RS = `CANDIDATES_READ_SCOPED`. Cột ADMIN lấy theo ghi chú "Admin toàn quyền mọi module" của bảng nguồn, trừ dải lương (mục 7.9).

| Module | CANDIDATE | INTERVIEWER | HIRING_MANAGER | RECRUITER | APPROVER | HR_MANAGER | ADMIN |
|---|---|---|---|---|---|---|---|
|`ORGANIZATION`|–|R · RA|R · RA|R · RA|R · RA|F · RA, WA|F · RA, WA|
|`REQUISITIONS`|–|–|W* · RS, WS|W · RS, WS|W* · RS, WS|F · RA, WA|F · RA, WA|
|`JOB_POSTINGS`|R · RA|–|R · RA|W · RS, WS|R · RA|F · RA, WA|F · RA, WA|
|`CANDIDATES`|R* · RS|R* · RS|R* · RS|**F → W\*** · RS, WS|R · RA|F · RA, WA|F · RA, WA|
|`INTERVIEWS`|R* · RS|R* · RS|R* · RS|F · RA, WA|–|F · RA, WA|F · RA, WA|
|`EVALUATIONS`|–|W* · RS, WS|R* · RS|R · RA|R · RA|F · RA, WA|F · RA, WA|
|`OFFERS`|R* · RS|–|R* · RS|W · RS, WS|W* · RS, WS|F · RA, WA|F · RA, WA|
|`NOTIFICATIONS`|R* · RS|R* · RS|R* · RS|F · RA, WA|–|F · RA, WA|F · RA, WA|
|`REPORTS`|–|–|R* · RS|R* · RS|R · RA|F · RA, WA|F · RA, WA|
|`USER_ADMIN`|–|–|–|–|–|R · RA|F · RA, WA|
|`SALARY_RANGES`|–|–|–|–|–|**F** · RA, WA|**–**|
|Tự phục vụ (`SELF_*`)|–|3 mã|3 mã|3 mã|3 mã|3 mã|3 mã|

Ô in đậm là khác biệt so với bảng nguồn; lý do ở mục 7.1 (Recruiter – hồ sơ ứng viên) và 7.9 (dải lương). Bảng nguồn không có dòng dải lương riêng: theo Jira 205, chỉ HR_MANAGER được cấp, và ADMIN không được cấp dù ghi chú "Admin toàn quyền mọi module".

### 5.2 Danh sách mã chính xác trong `role_permissions`

Ba mã tự phục vụ `SELF_PROFILE_READ`, `SELF_PROFILE_WRITE`, `SELF_SECURITY_WRITE` có trong mọi vai trò nội bộ và được tính vào cột tổng. Tổng cộng có 104 dòng (102 dòng của V3/V5 và 2 dòng của V7_1).

| Vai trò | Tổng | Mã quyền nghiệp vụ |
|---|---|---|
|`ADMIN`|23|`ORGANIZATION_READ_ALL`, `ORGANIZATION_WRITE_ALL`, `REQUISITIONS_READ_ALL`, `REQUISITIONS_WRITE_ALL`, `JOB_POSTINGS_READ_ALL`, `JOB_POSTINGS_WRITE_ALL`, `CANDIDATES_READ_ALL`, `CANDIDATES_WRITE_ALL`, `INTERVIEWS_READ_ALL`, `INTERVIEWS_WRITE_ALL`, `EVALUATIONS_READ_ALL`, `EVALUATIONS_WRITE_ALL`, `OFFERS_READ_ALL`, `OFFERS_WRITE_ALL`, `NOTIFICATIONS_READ_ALL`, `NOTIFICATIONS_WRITE_ALL`, `REPORTS_READ_ALL`, `REPORTS_WRITE_ALL`, `USER_ADMIN_READ_ALL`, `USER_ADMIN_WRITE_ALL`|
|`HR_MANAGER`|24|Giống `ADMIN` nhưng không có `USER_ADMIN_WRITE_ALL`; có thêm `SALARY_RANGES_READ_ALL`, `SALARY_RANGES_WRITE_ALL` (V7_1)|
|`RECRUITER`|18|`ORGANIZATION_READ_ALL`, `REQUISITIONS_READ_SCOPED`, `REQUISITIONS_WRITE_SCOPED`, `JOB_POSTINGS_READ_SCOPED`, `JOB_POSTINGS_WRITE_SCOPED`, `CANDIDATES_READ_SCOPED`, `CANDIDATES_WRITE_SCOPED`, `INTERVIEWS_READ_ALL`, `INTERVIEWS_WRITE_ALL`, `EVALUATIONS_READ_ALL`, `OFFERS_READ_SCOPED`, `OFFERS_WRITE_SCOPED`, `NOTIFICATIONS_READ_ALL`, `NOTIFICATIONS_WRITE_ALL`, `REPORTS_READ_SCOPED`|
|`HIRING_MANAGER`|13|`ORGANIZATION_READ_ALL`, `REQUISITIONS_READ_SCOPED`, `REQUISITIONS_WRITE_SCOPED`, `JOB_POSTINGS_READ_ALL`, `CANDIDATES_READ_SCOPED`, `INTERVIEWS_READ_SCOPED`, `EVALUATIONS_READ_SCOPED`, `OFFERS_READ_SCOPED`, `NOTIFICATIONS_READ_SCOPED`, `REPORTS_READ_SCOPED`|
|`APPROVER`|12|`ORGANIZATION_READ_ALL`, `REQUISITIONS_READ_SCOPED`, `REQUISITIONS_WRITE_SCOPED`, `JOB_POSTINGS_READ_ALL`, `CANDIDATES_READ_ALL`, `EVALUATIONS_READ_ALL`, `OFFERS_READ_SCOPED`, `OFFERS_WRITE_SCOPED`, `REPORTS_READ_ALL`|
|`INTERVIEWER`|9|`ORGANIZATION_READ_ALL`, `CANDIDATES_READ_SCOPED`, `INTERVIEWS_READ_SCOPED`, `EVALUATIONS_READ_SCOPED`, `EVALUATIONS_WRITE_SCOPED`, `NOTIFICATIONS_READ_SCOPED`|
|`CANDIDATE`|5|`JOB_POSTINGS_READ_ALL`, `CANDIDATES_READ_SCOPED`, `INTERVIEWS_READ_SCOPED`, `OFFERS_READ_SCOPED`, `NOTIFICATIONS_READ_SCOPED`. Không có mã tự phục vụ|

Năm quyền của `CANDIDATE` được seed để ghi lại ma trận nghiệp vụ. Hiện không tài khoản nào dùng được chúng (xem mục 7).

## 6. API hiện có, quyền cần có và vai trò được phép

URL dùng tiền tố `/api/v1`. "6 vai trò nội bộ" là ADMIN, HR_MANAGER, RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER.

| # | Method và URL | Server yêu cầu | Vai trò được phép theo seed hiện tại |
|---|---|---|---|
|1|`GET /health`|Công khai khi không gửi token; gửi Bearer hỏng vẫn bị 401|Mọi người|
|2|`POST /auth/login`|Công khai, bỏ qua header Bearer|Mọi người có tài khoản|
|3|`POST /auth/refresh`|Công khai, bỏ qua header Bearer; xác thực bằng refresh token trong body|Mọi người có phiên hợp lệ|
|4|`POST /auth/forgot-password`|Công khai, bỏ qua header Bearer|Mọi người|
|5|`POST /auth/reset-password`|Công khai, bỏ qua header Bearer; xác thực bằng token trong body|Người có link đặt lại mật khẩu|
|6|`POST /auth/activate-account`|Công khai, bỏ qua header Bearer; xác thực bằng token trong body|Người có link kích hoạt|
|7|`GET /auth/me`|`SELF_PROFILE_READ`|6 vai trò nội bộ|
|8|`GET /auth/permissions`|`SELF_PROFILE_READ`|6 vai trò nội bộ|
|9|`GET /profile`|`SELF_PROFILE_READ`|6 vai trò nội bộ|
|10|`PUT /profile`|`SELF_PROFILE_WRITE`|6 vai trò nội bộ|
|11|`POST /auth/logout`|`SELF_SECURITY_WRITE`|6 vai trò nội bộ|
|12|`POST /auth/change-password`|`SELF_SECURITY_WRITE`|6 vai trò nội bộ|
|13|`GET /accounts`|`USER_ADMIN_READ_ALL`|ADMIN, HR_MANAGER|
|14|`GET /accounts/{id}`|`USER_ADMIN_READ_ALL`|ADMIN, HR_MANAGER|
|15|`POST /accounts`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|16|`PUT /accounts/{id}`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|17|`PUT /accounts/{id}/roles/{role}`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|18|`DELETE /accounts/{id}/roles/{role}`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|19|`PUT /accounts/{id}/lock`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|20|`DELETE /accounts/{id}/lock`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`|ADMIN|
|21|`GET /departments`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|22|`GET /departments/tree`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|23|`GET /departments/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|24|`POST /departments`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|25|`PUT /departments/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|26|`GET /api/health` (không có `/v1`)|Công khai; API sức khỏe cũ giữ lại để tương thích|Mọi người|
|27|`GET /positions`|`ORGANIZATION_READ_ALL`; `salaryMin`/`salaryMax` chỉ có trong response khi có thêm `SALARY_RANGES_READ_ALL`|6 vai trò nội bộ; chỉ HR_MANAGER thấy dải lương|
|28|`GET /positions/{id}`|`ORGANIZATION_READ_ALL`; `salaryMin`/`salaryMax` chỉ có trong response khi có thêm `SALARY_RANGES_READ_ALL`|6 vai trò nội bộ; chỉ HR_MANAGER thấy dải lương|
|29|`POST /positions`|`ORGANIZATION_WRITE_ALL` **và** `SALARY_RANGES_WRITE_ALL`|HR_MANAGER|
|30|`PUT /positions/{id}`|`ORGANIZATION_WRITE_ALL` **và** `SALARY_RANGES_WRITE_ALL`|HR_MANAGER|
|31|`GET /competency-frameworks`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|32|`GET /competency-frameworks/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|33|`POST /competency-frameworks`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|34|`PUT /competency-frameworks/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|35|`PUT /positions/{id}/competency-framework`|`ORGANIZATION_WRITE_ALL` (không cần `SALARY_RANGES_WRITE_ALL`)|ADMIN, HR_MANAGER|
|36|`DELETE /positions/{id}/competency-framework`|`ORGANIZATION_WRITE_ALL` (không cần `SALARY_RANGES_WRITE_ALL`)|ADMIN, HR_MANAGER|
|37|`GET /positions/{id}/evaluation-criteria`|`ORGANIZATION_READ_ALL`; response không bao giờ có dải lương|6 vai trò nội bộ|
|38|`GET /interview-questions/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|39|`POST /interview-questions`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|40|`PUT /interview-questions/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|41|`GET /interview-questions`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|42|`POST /requisitions`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`; tạo bản nháp; `SCOPED` chỉ tạo cho phòng ban mình phụ trách (cả phòng ban con), phòng ban khác trả 403 (task 249)|ADMIN, HR_MANAGER (mọi phòng ban); RECRUITER, HIRING_MANAGER, APPROVER (theo phòng ban)|
|43|`GET /requisitions`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`; `SCOPED` chỉ nhận yêu cầu của phòng ban mình phụ trách (cả phòng ban con)|ADMIN, HR_MANAGER (mọi yêu cầu); RECRUITER, HIRING_MANAGER, APPROVER (theo phòng ban)|
|44|`GET /requisitions/{id}`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`; yêu cầu ngoài phạm vi `SCOPED` trả 403|ADMIN, HR_MANAGER (mọi yêu cầu); RECRUITER, HIRING_MANAGER, APPROVER (theo phòng ban)|
|45|`PUT /requisitions/{id}`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`; chỉ bản nháp, yêu cầu ngoài phạm vi `SCOPED` trả 403; phòng ban mới trong body cũng phải thuộc phạm vi, nếu không trả 403 (task 249)|ADMIN, HR_MANAGER (mọi yêu cầu); RECRUITER, HIRING_MANAGER, APPROVER (theo phòng ban)|
|46|`DELETE /departments/{id}`|`ORGANIZATION_WRITE_ALL`; phòng ban còn yêu cầu tuyển dụng chưa đóng, phòng con hoặc tài khoản trả 409, chỉ ngừng áp dụng được (task 197)|ADMIN, HR_MANAGER|
|47|`GET /recruitment-catalogs/{type}/items`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|48|`GET /recruitment-catalogs/{type}/items/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|49|`POST /recruitment-catalogs/{type}/items`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|50|`PUT /recruitment-catalogs/{type}/items/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|51|`DELETE /recruitment-catalogs/{type}/items/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|52|`PUT /recruitment-catalogs/{type}/order`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|
|53|`GET /company-profile`|`JOB_POSTINGS_WRITE_ALL`|ADMIN, HR_MANAGER|
|54|`PUT /company-profile`|`JOB_POSTINGS_WRITE_ALL`|ADMIN, HR_MANAGER|
|55|`POST /company-profile/preview`|`JOB_POSTINGS_WRITE_ALL`|ADMIN, HR_MANAGER|
|56|`GET /public/company-profile`|Công khai khi không gửi token; gửi Bearer hỏng vẫn bị 401|Mọi người, kể cả ứng viên không có tài khoản|

Ngoài bộ lọc trong `SecurityConfiguration`, service của tài khoản (13–20), phòng ban (21–25, 46), chức danh (27–30, 35–36), khung năng lực (31–34), tiêu chí đánh giá theo chức danh (37), câu hỏi phỏng vấn (38–41), yêu cầu tuyển dụng (42–45), danh mục tuyển dụng (47–52), trang giới thiệu công ty (53–55) và hồ sơ cá nhân (9–10) kiểm lại mã quyền trước khi xử lý; với thao tác ghi, việc kiểm lại diễn ra sau khi khóa bản ghi. Thao tác ghi tài khoản yêu cầu đồng thời vai trò `ADMIN` và mã `USER_ADMIN_WRITE_ALL`, ở cả `SecurityConfiguration` lẫn `AccountProvisioningService`, `AccountManagementService`, `AccountRoleService` và `AccountLockService`. Vì vậy, nếu sau này cấp `USER_ADMIN_WRITE_ALL` cho vai trò khác, vai trò đó vẫn nhận 403 cho tới khi sửa cả năm chỗ này. Tương tự, tạo/sửa chức danh (29–30) yêu cầu cả hai mã ở `SecurityConfiguration` lẫn `PositionService`; còn việc ẩn dải lương ở dòng 27–30 và 35–36 là kiểm tra theo trường trong `PositionService`, không có matcher URL riêng. Gán/bỏ khung năng lực của chức danh (35–36, task 214) không đổi dải lương nên chỉ cần `ORGANIZATION_WRITE_ALL`: ADMIN làm được dù không tạo/sửa được chức danh, và response gửi ADMIN vẫn không có dải lương. Tiêu chí đánh giá theo chức danh (37, task 215) chỉ gồm chức danh không kèm lương, khung và tiêu chí, nên cả sáu vai trò nội bộ đọc được, kể cả INTERVIEWER chấm phỏng vấn theo các tiêu chí này. Câu hỏi phỏng vấn (38–40, task 221) gắn với tiêu chí của khung năng lực nên theo cùng quy tắc với khung: sáu vai trò nội bộ đọc được, chỉ ADMIN và HR_MANAGER tạo/sửa. Tìm kiếm và lọc câu hỏi (41, task 223) là thao tác đọc nên dùng cùng `ORGANIZATION_READ_ALL`; người thiếu quyền nhận 403 trước mọi kiểm tra tham số. Tương tự, giới hạn phòng ban của `SCOPED` ở dòng 42–45 là kiểm tra theo bản ghi trong `RequisitionService`; matcher URL chỉ kiểm mã quyền.

Mọi URL không có trong bảng đều bị từ chối mặc định. Khi bộ lọc Bearer gặp token thiếu, sai hoặc phiên đã hết, server trả **401** `UNAUTHORIZED` kèm `WWW-Authenticate: Bearer`. Khi phiên hợp lệ nhưng thiếu quyền, server trả **403** `FORBIDDEN` với thông báo tiếng Việt. Cả hai phản hồi này đều có `Cache-Control: no-store`. Riêng refresh token hỏng và các lần service kiểm lại phiên thấy phiên đã mất trả 401 với mã `SESSION_INVALID` qua `ApiExceptionHandler`, không kèm hai header trên. Quyền được đọc lại từ database ở mỗi yêu cầu, nên thay đổi vai trò có hiệu lực ngay ở yêu cầu kế tiếp.

## 7. Khác biệt so với bảng nguồn và các giả định

1. **Recruiter – hồ sơ ứng viên.** Bảng nguồn ghi `F`, nhưng seed dùng `W*` (`CANDIDATES_READ_SCOPED`, `CANDIDATES_WRITE_SCOPED`). Story 14 yêu cầu recruiter không xem được ứng viên của vị trí không thuộc mình. Tiêu chí của story được ưu tiên hơn ô trong bảng.
2. **`W` được seed giống `W*`.** Các ô `W` không có dấu `*` của Recruiter (yêu cầu tuyển dụng, tin tuyển dụng, offer) được cấp `SCOPED` chứ không phải `ALL`. Recruiter chỉ làm việc trên vị trí được phân công.
3. **Nhiều vai trò thì cộng quyền.** Quyền của tài khoản là hợp của quyền mọi vai trò được gán. Hệ thống không có quyền "cấm", nên thêm vai trò chỉ mở rộng quyền. Đề xuất: khi một người có cả `_READ_ALL` và `_READ_SCOPED` của cùng module, API sau này áp dụng phạm vi rộng hơn.
4. **Tài khoản không có vai trò.** Tài khoản vẫn đăng nhập và refresh được. Tuy vậy, mọi API được bảo vệ đều trả 403, kể cả `/auth/me`, `/profile`, đăng xuất và đổi mật khẩu, vì tài khoản không còn mã `SELF_*`. Admin có thể gán lại vai trò. Hiện không có quy tắc "mỗi tài khoản phải có ít nhất một vai trò".
5. **Ứng viên là tác nhân bên ngoài.** `CANDIDATE` có `internal=false` và không có tài khoản nội bộ. Ràng buộc CHECK trên `user_roles` (V1) chỉ nhận sáu vai trò nội bộ nên chặn việc gán `CANDIDATE`; khóa ngoại sang `roles` (V3) chặn thêm các mã vai trò không tồn tại. Cổng ứng viên sau này sẽ cần cơ chế xác thực riêng; cơ chế đó chưa được thiết kế.
6. **Admin có toàn quyền mọi module, trừ dải lương.** Quyền này gồm cả dữ liệu cá nhân của ứng viên, phiếu đánh giá và offer (`*_ALL`), theo ghi chú "Admin toàn quyền mọi module". Ngoại lệ duy nhất là module `SALARY_RANGES` (mục 7.9 và câu hỏi 4): ADMIN không xem được dải lương và không tạo/sửa được chức danh. Phần mô tả vai trò chỉ nêu quản lý tài khoản, danh mục và nhật ký. Xem câu hỏi 6.
7. **HR Manager chỉ xem tài khoản.** `USER_ADMIN_READ_ALL` cho phép xem danh sách và chi tiết tài khoản, không cho tạo, sửa, gán vai trò hay khóa tài khoản. Nhật ký hệ thống (audit log) trong dòng "Người dùng & nhật ký" chưa được xây dựng và chưa có API.
8. **Recruiter xem lịch và phiếu của mọi vị trí.** Theo bảng nguồn, Recruiter có `INTERVIEWS` = F và `EVALUATIONS` = R, nên được seed `ALL`. Do đó Recruiter có thể xem lịch phỏng vấn và phiếu đánh giá của ứng viên thuộc vị trí không được phân công, dù hồ sơ ứng viên đã bị giới hạn. Xem câu hỏi 8.
9. **Dải lương có module quyền riêng `SALARY_RANGES` (V7_1, task 205).** Bảng nguồn đặt "vị trí" trong dòng "Danh mục tổ chức & vị trí", tức module `ORGANIZATION`, mà cả sáu vai trò nội bộ đều có `ORGANIZATION_READ_ALL`; quyền đó không đủ để che dải lương. V7_1 thêm bốn mã `SALARY_RANGES_*` và chỉ cấp `SALARY_RANGES_READ_ALL`, `SALARY_RANGES_WRITE_ALL` cho HR_MANAGER, theo Jira 205 "chỉ Trưởng phòng Nhân sự xem được dải lương". ADMIN cố ý không được cấp, khác ghi chú "Admin toàn quyền mọi module"; vì lương là trường bắt buộc khi tạo/sửa chức danh, ADMIN cũng không tạo/sửa được chức danh. Người thiếu `SALARY_RANGES_READ_ALL` nhận response chức danh không có khóa `salaryMin`/`salaryMax` (mục 6, dòng 27–30). Xem câu hỏi 4.
10. **Quyền `SCOPED` mới được kiểm theo từng bản ghi ở yêu cầu tuyển dụng.** Các API ứng viên, offer, báo cáo chưa tồn tại. Yêu cầu tuyển dụng (dòng 42–45, task 245 và 249) giới hạn người `SCOPED` ở phòng ban mình phụ trách, kể cả phòng ban con, khi xem, sửa và khi chọn phòng ban lúc tạo (dòng 42) hoặc sửa (dòng 45); RECRUITER và APPROVER thường không phụ trách phòng ban nào nên không thấy và không tạo được yêu cầu nào cho tới khi có luồng phân công/duyệt (câu hỏi 2).
11. **Trang giới thiệu công ty thuộc module `JOB_POSTINGS`.** Bảng nguồn không có dòng riêng cho cổng tuyển dụng; backend xếp trang giới thiệu (story S2-09, mục 6 dòng 53–56) vào tin tuyển dụng. Đọc bản trong trình soạn, xem trước và lưu đều cần `JOB_POSTINGS_WRITE_ALL` (ADMIN, HR_MANAGER). Trang dùng chung cho cả công ty nên `JOB_POSTINGS_WRITE_SCOPED` của Recruiter không đủ, và `JOB_POSTINGS_READ_ALL` (Hiring Manager, Approver) chỉ xem trang qua API công khai. Nếu BA/PO muốn Recruiter soạn trang, cần cấp `JOB_POSTINGS_WRITE_ALL` hoặc thêm mã quyền riêng bằng migration mới.

## 8. Câu hỏi cần BA/PO xác nhận

1. **Phạm vi ứng viên của Recruiter.** BA/PO có đồng ý thay `F` bằng `W*` (chỉ ứng viên của vị trí được phân công) không? "Được phân công" xác định theo yêu cầu tuyển dụng hay theo vị trí, và HR Manager phân công ở đâu?
2. **`W` hay `W*` cho Recruiter.** Ở yêu cầu tuyển dụng, tin tuyển dụng và offer, Recruiter đang chỉ thao tác trên vị trí được phân công. Có cần cho Recruiter thao tác trên mọi vị trí (`ALL`) không? Riêng yêu cầu tuyển dụng: Recruiter có được tạo mới không, hay chỉ Hiring Manager tạo? Từ task 245 backend tạm hiểu `REQUISITIONS_*_SCOPED` của cả Recruiter, Hiring Manager và Approver là "yêu cầu của phòng ban mình phụ trách (kể cả phòng ban con)", nên Recruiter và Approver không phụ trách phòng ban nào sẽ không thấy yêu cầu nào. Từ task 249 cách hiểu này áp dụng cả khi tạo/sửa: Recruiter hoặc Approver không phụ trách phòng ban nào nhận 403 khi tạo, còn người được HR đặt làm người phụ trách một phòng ban thì tạo và sửa được yêu cầu của phòng ban đó, vì backend kiểm theo mã quyền và cây phòng ban, không theo tên vai trò. Nếu BA/PO chốt chỉ Hiring Manager được tạo, cần đổi cả quyền seed lẫn `RequisitionService`. Cần xác nhận Recruiter thấy yêu cầu theo phân công nào, và Approver thấy yêu cầu nào trước khi có luồng duyệt.
3. **Approver xem toàn bộ ứng viên.** Approver đang có `CANDIDATES_READ_ALL` và `EVALUATIONS_READ_ALL`, tức xem được dữ liệu cá nhân của mọi ứng viên. Có nên giới hạn ở ứng viên và offer đang chờ chính người đó duyệt không?
4. **Ai được xem dải lương.** Story 14 yêu cầu Interviewer không xem được; Jira 205 ghi "chỉ Trưởng phòng Nhân sự xem được dải lương". **Trạng thái: backend đã tạm áp dụng (task 205, V7_1), chờ BA/PO xác nhận.** Hiện chỉ HR_MANAGER có `SALARY_RANGES_READ_ALL` và `SALARY_RANGES_WRITE_ALL`; mọi vai trò khác, kể cả ADMIN, không thấy dải lương và không tạo/sửa được chức danh. Cần xác nhận: Admin có cần quản lý danh mục chức danh (tức cần cả hai mã) không? Approver (duyệt offer vượt hạn mức), Recruiter (soạn offer) và Hiring Manager có cần xem dải lương không, và nếu có thì xem toàn bộ hay chỉ của vị trí liên quan (`SALARY_RANGES_READ_SCOPED`, hiện chưa có ý nghĩa)? Đổi quyết định chỉ cần migration mới thêm/xóa dòng `role_permissions` theo mục 4.
5. **Hạn mức lương khi duyệt offer.** "Vượt hạn mức lương" được so với dải lương của chức danh hay với hạn mức riêng của từng cấp duyệt? Recruiter có bị giới hạn mức lương khi soạn offer không? Có cần lưu hạn mức theo từng Approver không? Task 206 đã chuẩn bị phần so với dải lương của chức danh (`SalaryBandService`: `BELOW`/`WITHIN`/`ABOVE`, tính cả hai đầu dải) và tạm quyết định chức danh ngừng áp dụng không có dải lương chuẩn (`POSITION_INACTIVE`), kể cả với offer đang chờ duyệt; cần BA/PO xác nhận quyết định này cùng câu hỏi trên.
6. **Admin và dữ liệu cá nhân ứng viên.** Có giữ toàn quyền của Admin với hồ sơ ứng viên, phiếu đánh giá và offer không, hay thu hẹp Admin về tài khoản, danh mục và nhật ký theo nguyên tắc quyền tối thiểu?
7. **HR Manager và tài khoản.** Chỉ xem tài khoản như hiện tại có đủ không? HR Manager có cần tạo tài khoản, gán vai trò Recruiter hoặc xem nhật ký hệ thống không?
8. **Recruiter xem lịch và phiếu đánh giá.** Có nên giới hạn `INTERVIEWS` và `EVALUATIONS` của Recruiter về vị trí được phân công, cho nhất quán với hồ sơ ứng viên?
9. **Thông báo cho Approver.** Bảng nguồn ghi Approver không truy cập "Email & thông báo". Approver sẽ biết có yêu cầu hoặc offer chờ duyệt bằng cách nào?
10. **Menu theo vai trò (Jira 130, việc của BA/PO).** Mỗi vai trò thấy những menu nào, và mỗi menu dựa trên mã quyền nào?
11. **Tài khoản không vai trò.** Giữ hành vi ở mục 7.4, hay bắt buộc mỗi tài khoản phải có ít nhất một vai trò?
12. **Nhiều vai trò.** Xác nhận quy tắc cộng quyền ở mục 7.3, ví dụ một người vừa là Hiring Manager vừa là Interviewer.

## 9. Ghi chú tích hợp frontend

- Vai trò lấy từ `user.roles` trong response của `POST /auth/login`, hoặc từ `roles` của `GET /auth/me`. Đây là **mảng**, vì một người có thể có nhiều vai trò. Giá trị là mã ở mục 1, ví dụ `HR_MANAGER`.
- Quyền lấy từ `GET /auth/permissions`, response có dạng `{"permissions":["ORGANIZATION_READ_ALL","SELF_PROFILE_READ", ...]}` đã sắp xếp. Nên dựa vào mã quyền hơn là tên vai trò, vì quyền là hợp của nhiều vai trò và ma trận còn có thể thay đổi.
- Gợi ý, chờ Jira 130 chốt: hiện menu của một module khi người dùng có `<MODULE>_READ_ALL` hoặc `<MODULE>_READ_SCOPED`; hiện nút tạo/sửa khi có `<MODULE>_WRITE_ALL` hoặc `<MODULE>_WRITE_SCOPED`. Ví dụ, menu tài khoản dùng `USER_ADMIN_READ_ALL`, menu phòng ban dùng `ORGANIZATION_READ_ALL`, hồ sơ cá nhân dùng `SELF_PROFILE_READ`.
- Phòng ban: hiện nút tạo/sửa/xóa khi có `ORGANIZATION_WRITE_ALL`. Người phụ trách một phòng ban không vì thế mà có các nút này. Server vẫn trả 403 nếu quyền bị gỡ sau khi giao diện đã tải (task 198).
- Chức danh: hiện cột/ô dải lương khi có `SALARY_RANGES_READ_ALL`; hiện nút tạo/sửa chức danh khi có cả `ORGANIZATION_WRITE_ALL` và `SALARY_RANGES_WRITE_ALL`; hiện nút gán/bỏ khung năng lực khi có `ORGANIZATION_WRITE_ALL`. Với người không có quyền xem, response không có khóa `salaryMin`/`salaryMax`, nên giao diện phải xử lý trường vắng mặt thay vì hiển thị `0`.
- Yêu cầu tuyển dụng: hiện menu danh sách khi có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`; hiện nút tạo/sửa khi có `REQUISITIONS_WRITE_ALL` hoặc `REQUISITIONS_WRITE_SCOPED`. Server tự lọc danh sách theo phòng ban người gọi phụ trách và trả 403 khi mở/sửa yêu cầu ngoài phạm vi, hoặc khi phòng ban chọn trên form nằm ngoài phạm vi (task 249). Ô chọn phòng ban nên chỉ liệt kê các phòng ban người dùng phụ trách, tính từ `GET /departments/tree` (node có `managerUserId` là người đang đăng nhập và mọi node con cháu); người có `REQUISITIONS_WRITE_ALL` chọn được mọi phòng ban.
- Ẩn menu chỉ để trải nghiệm người dùng tốt hơn. Server luôn kiểm lại quyền ở mọi yêu cầu. Frontend vẫn cần xử lý 401 (refresh token hoặc đăng nhập lại) và 403 (báo không có quyền), và tải lại quyền sau khi đăng nhập hoặc khi vai trò thay đổi.
- Nhánh frontend `feature/TKNHTTDNB1-132-permission-mechanism` hiện dùng ví dụ một trường `role` đơn, tên vai trò như `HR`, `EMPLOYEE` và tên quyền như `VIEW_REPORT`, `APPROVE_RECRUITMENT`. Backend chưa có các tên này. Khi tích hợp, hai bên cần thống nhất dùng mã ở tài liệu này, ví dụ báo cáo tương ứng `REPORTS_READ_ALL` hoặc `REPORTS_READ_SCOPED`.
