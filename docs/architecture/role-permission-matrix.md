# Ma trận vai trò và quyền

## Mục đích và trạng thái

Tài liệu phục vụ Jira TKNHTTDNB1-119 "Xác định danh sách Role và Permission" và TKNHTTDNB1-120 "Xác định quyền của từng Role" thuộc story TKNHTTDNB1-14. Cả hai task yêu cầu kết hợp với BA/PO.

**Trạng thái: đề xuất của nhóm backend, chờ BA/PO xác nhận. Đây chưa phải bản chốt.** Nội dung mô tả đúng những gì database đang cấp (Flyway V3 và V5) và những gì server đang kiểm. Quyền cụ thể của từng vai trò (Jira 120) được bổ sung ở các mục tiếp theo. Khi BA/PO thay đổi, backend điều chỉnh bằng migration mới theo mục 4.

Nguồn: bảng `2. User Roles` của đặc tả "HỆ THỐNG TUYỂN DỤNG NỘI BỘ", Jira TKNHTTDNB1-14 và TKNHTTDNB1-205. Cơ chế kiểm quyền được mô tả trong [thiết kế phân quyền](authorization.md). Test `RolePermissionSeedMigrationTest` kiểm tra database có đúng bảy vai trò ở mục 1 và đúng danh mục mã quyền ở mục 2. Test không đọc file này, nên người đổi danh mục phải tự cập nhật tài liệu trong cùng thay đổi (mục 4).

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

Có 10 module × 2 thao tác × 2 phạm vi = 40 mã. Database tạo đủ 40 mã, kể cả các mã chưa vai trò nào được cấp, ví dụ `ORGANIZATION_READ_SCOPED`. Khi cần cấp thêm quyền, chỉ cần thêm dòng `role_permissions`.

| Module | Dòng trong bảng nguồn | `SCOPED` nghĩa là (đề xuất) | API backend hiện có |
|---|---|---|---|
|`ORGANIZATION`|Danh mục tổ chức & vị trí|Chưa vai trò nào dùng; nếu cần, đề xuất là phòng ban mình phụ trách|Phòng ban, cây tổ chức (195–196)|
|`REQUISITIONS`|Yêu cầu tuyển dụng|Hiring Manager: yêu cầu của bộ phận mình. Recruiter: yêu cầu được phân công. Approver: yêu cầu được chuyển cho mình duyệt|Chưa có|
|`JOB_POSTINGS`|Tin tuyển dụng|Recruiter: tin của vị trí được phân công|Chưa có|
|`CANDIDATES`|Hồ sơ ứng viên & pipeline|Recruiter: ứng viên của vị trí được phân công. Hiring Manager: ứng viên của vị trí mình sở hữu. Interviewer: ứng viên trong vòng mình phỏng vấn. Candidate: hồ sơ của chính mình|Chưa có|
|`INTERVIEWS`|Lịch phỏng vấn|Interviewer: vòng mình tham gia. Hiring Manager: vị trí mình sở hữu. Candidate: lịch của chính mình|Chưa có|
|`EVALUATIONS`|Phiếu đánh giá|Interviewer: phiếu của mình ở vòng mình tham gia. Hiring Manager: phiếu thuộc vị trí mình sở hữu|Chưa có|
|`OFFERS`|Offer & onboarding|Recruiter: offer của vị trí được phân công. Approver: offer chờ mình duyệt. Hiring Manager: offer của vị trí mình. Candidate: offer gửi cho mình|Chưa có|
|`NOTIFICATIONS`|Email & thông báo|Thông báo gửi cho chính mình hoặc thuộc vị trí mình liên quan|Chưa có|
|`REPORTS`|Báo cáo & dashboard|Recruiter: số liệu vị trí được phân công. Hiring Manager: số liệu vị trí mình sở hữu|Chưa có|
|`USER_ADMIN`|Người dùng & nhật ký|Chưa vai trò nào dùng|Tài khoản, vai trò, khóa tài khoản (145–166)|

Cột `SCOPED` là đề xuất của backend. Các API nghiệp vụ dùng phạm vi này chưa được xây dựng, nên hiện chưa có code kiểm quyền sở hữu từng bản ghi.

### Quyền tự phục vụ

| Mã | Dùng cho | Migration |
|---|---|---|
|`SELF_PROFILE_READ`|Xem tài khoản, quyền và hồ sơ của chính mình|V3|
|`SELF_PROFILE_WRITE`|Sửa hồ sơ cá nhân của chính mình|V5|
|`SELF_SECURITY_WRITE`|Đăng xuất, đổi mật khẩu|V3|

Ba mã này được cấp cho cả sáu vai trò nội bộ và không cấp cho `CANDIDATE`. Bảng `permissions` có tổng cộng 43 mã.

## 3. Chuyển ký hiệu bảng nguồn sang mã quyền

| Ký hiệu | Ý nghĩa trong bảng nguồn | Mã được cấp cho module |
|---|---|---|
|F|Toàn quyền|`_READ_ALL`, `_WRITE_ALL`|
|W|Ghi trong phạm vi được giao|`_READ_SCOPED`, `_WRITE_SCOPED`|
|W*|Ghi, chỉ dữ liệu của mình/vị trí mình/vòng mình tham gia|`_READ_SCOPED`, `_WRITE_SCOPED`|
|R|Chỉ xem|`_READ_ALL`|
|R*|Chỉ xem dữ liệu của mình/vị trí mình/vòng mình tham gia|`_READ_SCOPED`|
|–|Không truy cập|Không cấp|

V3 xử lý `W` giống `W*`, vì chú thích của bảng nguồn ghi `W` là "trong phạm vi được giao". Database hiện không phân biệt "phạm vi được giao" với "của chính mình". Sự khác nhau đó chỉ thể hiện ở cách từng API lọc dữ liệu sau này; BA/PO cần xác nhận cách hiểu này.

## 4. Cách thay đổi ma trận

1. Tạo migration mới trong `database/migrations`, đánh số từ `V7__...` trở đi. **Không sửa V1–V6**, đặc biệt V3 và V5. Các migration này đã chạy trên database của các thành viên; sửa lại sẽ làm Flyway báo lỗi checksum.
2. Thay đổi quyền bằng `INSERT`/`DELETE` trên `role_permissions`. Chỉ thêm dòng vào `permissions` khi cần mã mới, ví dụ mã cho dải lương.

   ```sql
   -- Ví dụ minh họa, không phải quyết định đã chốt
   DELETE FROM role_permissions WHERE role_code = 'APPROVER' AND permission_code = 'CANDIDATES_READ_ALL';
   INSERT INTO role_permissions (role_code, permission_code) VALUES ('APPROVER', 'CANDIDATES_READ_SCOPED');
   ```

3. Trong cùng thay đổi, cập nhật kỳ vọng trong `src/test/java/vn/ttcs/recruitment/auth/RolePermissionSeedMigrationTest.java` và mục 1–2 của tài liệu này. Nếu thay đổi chạm tới mã quyền mà API hiện có kiểm tra, cập nhật thêm enum `Identity` trong `ApiAuthorizationMatrixIntegrationTest`; lệnh test ở bước 5 không phát hiện thiếu sót này, chỉ `verify` đầy đủ mới phát hiện. Test không kiểm tài liệu, nên phải sửa tài liệu bằng tay. Nếu điều kiện của endpoint thay đổi, sửa thêm `SecurityConfiguration`, phần kiểm lại trong service tương ứng và [thiết kế phân quyền](authorization.md).
4. Thêm vai trò mới cần thêm bước: trong migration mới, `INSERT INTO roles` (vì `user_roles` và `role_permissions` có khóa ngoại tới `roles`) và sửa ràng buộc CHECK của `user_roles`; thêm giá trị vào enum `Role` trong Java; thêm vai trò vào danh sách vai trò mong đợi của `RolePermissionSeedMigrationTest` và enum `Identity` của `ApiAuthorizationMatrixIntegrationTest`.
5. Chạy `./mvnw.cmd test -Dtest=RolePermissionSeedMigrationTest` (Windows) hoặc `sh ./mvnw test -Dtest=RolePermissionSeedMigrationTest` ở thư mục gốc của repo Backend. Sau đó chạy `verify` đầy đủ trước khi tạo Pull Request.

Sau khi migrate, quyền mới có hiệu lực ở yêu cầu kế tiếp. Người dùng không cần đăng nhập lại; frontend chỉ cần tải lại danh sách quyền.
