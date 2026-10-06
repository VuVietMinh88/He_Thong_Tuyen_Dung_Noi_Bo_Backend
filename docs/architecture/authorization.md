# Phân quyền theo vai trò

Nguồn ban đầu: bảng `2. User Roles` của đặc tả `HỆ THỐNG TUYỂN DỤNG NỘI BỘ` và Jira TKNHTTDNB1-14, 119–127. Đây là ma trận khởi tạo; BA/PO cần xác nhận nếu nghiệp vụ thay đổi. Danh sách vai trò, mã quyền và các câu hỏi chờ BA/PO nằm ở [ma trận vai trò và quyền](role-permission-matrix.md). Bảy vai trò nghiệp vụ gồm sáu vai trò nội bộ `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER` và `CANDIDATE` bên ngoài. Ứng viên không có `user_accounts`/phiên đăng nhập nội bộ; luồng ứng viên công khai sẽ có cơ chế riêng khi được xây dựng.

Flyway V3 bổ sung bảng `roles`, `permissions`, `role_permissions` và khóa ngoại từ `user_roles.role`. Bảng `user_roles` V1 cùng dữ liệu tài khoản sẵn có được giữ lại. Vai trò Candidate có `internal=false` và không thể đưa vào `user_roles` vì ràng buộc sáu vai trò nội bộ hiện có. Không sửa nội dung V1/V2 đã áp dụng.

Ma trận nguồn dùng `F` toàn quyền, `W` ghi trong phạm vi được giao, `R` chỉ đọc, dấu `*` chỉ dữ liệu của bản thân/vị trí/vòng phỏng vấn liên quan. V3 biến các ô đó thành quyền đọc/ghi với phạm vi `ALL` hoặc `SCOPED`. Riêng ô Recruiter–hồ sơ ứng viên vốn ghi `F` trong bảng nguồn được giới hạn thành `SCOPED` theo tiêu chí Jira: recruiter không xem ứng viên của vị trí không thuộc mình. Admin được toàn quyền trên tất cả module theo ghi chú của bảng nguồn. Các thao tác xác thực cá nhân dùng `SELF_PROFILE_READ` và `SELF_SECURITY_WRITE` (V3) cùng `SELF_PROFILE_WRITE` (V5) cho sáu vai trò nội bộ.

Khi nhận Bearer token, server xác thực JWT, phiên và tài khoản, sau đó đọc `user_roles` nối `role_permissions` từ PostgreSQL để cấp quyền cho yêu cầu hiện tại. Thay đổi vai trò/quyền có hiệu lực ở yêu cầu kế tiếp. API chưa khai báo quyền bị từ chối mặc định. Thiếu/xấu/hết phiên trả 401; có phiên nhưng thiếu quyền trả 403 và thông báo tiếng Việt. `GET /auth/permissions` chỉ trả quyền của người đang đăng nhập để giao diện hiển thị phù hợp, không thay thế kiểm tra trên server.

Nhóm 154–158 bổ sung [PUT/DELETE vai trò tài khoản](../api/account-roles.md). Chỉ ADMIN có USER_ADMIN_WRITE_ALL được dùng; service khóa tài khoản thực hiện và tài khoản đích theo thứ tự UUID rồi khóa phiên, kiểm lại quyền còn hiệu lực trước khi ghi. Admin không thể tự bỏ ADMIN. Thay đổi chỉ tác động user_roles, dùng lại V1/V3, không sửa các migration đã áp dụng. Các yêu cầu đồng thời trên cùng tài khoản được tuần tự hóa để giữ các vai trò còn lại. Cùng access token sẽ nhận quyền mới ở yêu cầu sau khi transaction thay đổi đã commit.

Hiện không bắt buộc tài khoản phải luôn có vai trò: sau khi gỡ vai trò cuối, xác thực/refresh vẫn có thể thành công nếu tài khoản còn được truy cập, nhưng các API bảo vệ trả 403 vì không còn quyền SELF_* hay quyền nghiệp vụ. API quản trị có thể gán lại vai trò. [Khóa hành chính 162–166](../api/account-locking.md) dùng trạng thái V6 riêng, chặn truy cập và thu hồi phiên; các service ghi kiểm lại trạng thái và phiên sau khi lấy khóa bản ghi. Frontend cần tải lại quyền/menu sau thay đổi và xử lý 401/403; cập nhật UI không thuộc nhóm BE này.

Nhóm 195–196 bổ sung [API phòng ban và cây tổ chức](../api/departments.md). Đọc cần ORGANIZATION_READ_ALL; tạo/sửa cần ORGANIZATION_WRITE_ALL. V3 cấp quyền đọc cho sáu vai trò nội bộ, quyền ghi cho ADMIN và HR_MANAGER. Service khóa tài khoản người gọi/người phụ trách theo thứ tự UUID, rồi phiên và advisory lock cho cây; sau khi chờ, kiểm lại trạng thái, phiên, hạn JWT và quyền trước khi ghi. Đổi người phụ trách không tự đổi vai trò hay phòng ban của tài khoản. Quyền đọc cây là ALL theo ma trận hiện tại, không suy ra quyền đọc dữ liệu tuyển dụng của mọi phòng ban.

## Phạm vi dữ liệu: `AccessScope`

Matcher URL trong `SecurityConfiguration` chỉ trả lời câu hỏi "có quyền vào module này không". Để biết được xem/sửa **bao nhiêu** dữ liệu, service dùng `AccessScope` cùng enum `PermissionModule` (mười module nghiệp vụ của V3, gói `vn.ttcs.recruitment.security`). Các quyền `SELF_PROFILE_*`/`SELF_SECURITY_WRITE` không theo mẫu `<MODULE>_<READ|WRITE>_<ALL|SCOPED>` nên vẫn kiểm tra trực tiếp bằng `hasAuthority` trong `SecurityConfiguration`, không qua `AccessScope`. `AccessScope.read(..., module)` và `AccessScope.write(..., module)` trả về:

- `ALL`: toàn bộ dữ liệu của module;
- `SCOPED`: chỉ bản ghi được giao cho người gọi, ví dụ yêu cầu tuyển dụng của trưởng bộ phận, ứng viên thuộc vị trí recruiter phụ trách, vòng phỏng vấn được phân công;
- `NONE`: không có quyền.

Tham số đầu có thể là `Authentication` của yêu cầu (đọc các quyền `PERM_*` đã nạp) hoặc tập mã quyền mới đọc từ `PermissionService.forUser`, dùng khi service kiểm lại quyền sau khi khóa bản ghi như các service hiện có. Người có nhiều vai trò được `ALL` nếu một vai trò cấp `ALL`. Không đăng nhập, xác thực ẩn danh hoặc chỉ có `ROLE_*` đều là `NONE`. Quyền ghi không suy ra quyền đọc vì V3 cấp từng hành động thành dòng riêng. `orDeny()` ném `AccessDeniedException` khi kết quả là `NONE`, nên server trả 403 `FORBIDDEN` chuẩn.

`AccessScope` chỉ xác định phạm vi; lọc từng bản ghi và ẩn từng trường vẫn là việc của service từng module. API ứng viên, yêu cầu tuyển dụng và dải lương chưa tồn tại trong backend hiện tại, nên kiểm soát mức bản ghi/trường của Jira 14 và 127 (recruiter chỉ xem ứng viên của vị trí mình, người phỏng vấn không xem dải lương) chỉ hoàn tất khi các module đó được xây dựng theo checklist dưới đây. Chỉ kiểm tra quyền module là chưa đủ để nghiệm thu toàn bộ Jira 127.

## Khi thêm API mới

1. Khai báo matcher (phương thức + đường dẫn) trong `SecurityConfiguration` với quyền `PERM_<MODULE>_<READ|WRITE>_<ALL|SCOPED>` phù hợp. API không khai báo bị `denyAll()` từ chối mặc định. Nếu API phục vụ cả người có `ALL` lẫn `SCOPED`, matcher cho phép cả hai mã rồi service phân biệt bằng `AccessScope`.
2. Thêm dòng endpoint vào bảng `ENDPOINTS` của `ApiAuthorizationMatrixIntegrationTest`; test này làm build thất bại khi có endpoint chưa được khai báo trong ma trận. Nếu endpoint dùng mã quyền của module mới, bổ sung mã đó vào quyền mong đợi của từng vai trò trong enum `Identity`. `Rule` hiện chỉ nhận một mã quyền; matcher chấp nhận cả `ALL` lẫn `SCOPED` cần mở rộng `Rule` thành điều kiện "một trong các mã".
3. Trong service, gọi `AccessScope.read/write(..., PermissionModule.<MODULE>).orDeny()`. Với `SCOPED`, đưa điều kiện phân công vào câu truy vấn (lọc ở tầng cơ sở dữ liệu), không tải toàn bộ rồi lọc trong bộ nhớ hay giao cho frontend.
4. Loại các trường hạn chế, ví dụ dải lương, khỏi response với vai trò không được xem; không dựa vào giao diện để ẩn.
5. Từ chối bằng `AccessDeniedException`/`orDeny()` để server trả 403 `FORBIDDEN` chuẩn; không tự viết response 403 riêng. Với bản ghi nằm ngoài phạm vi `SCOPED`, mặc định cũng trả 403; nếu module muốn che cả việc bản ghi tồn tại (trả 404), ghi rõ quyết định đó trong tài liệu API của module.
