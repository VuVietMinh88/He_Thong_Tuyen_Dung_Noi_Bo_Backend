# Dữ liệu tài khoản và phòng ban

SQL gốc ở `database/migrations/`. Maven đóng gói SQL vào `db/migration/` trong backend JAR, nên JAR chạy migration được trên máy khác. Không có bản SQL thứ hai trong `src/main/resources`.

`database/seeds/` dành cho script tạo dữ liệu mẫu theo cấu trúc chung của nhóm; hiện chỉ có `.gitkeep` để giữ thư mục trong Git. Khi bổ sung seed, cần hướng dẫn chạy riêng. Flyway hiện chỉ nạp các migration được Maven đóng gói từ `database/migrations/`.

| Bảng | Nội dung |
|---|---|
| `user_accounts` | UUID, email, tên, BCrypt hash, trạng thái, số lần sai, thời điểm hết khóa/tạo; V5 thêm phone/display_title/department_id; V6 thêm admin_locked_at/admin_lock_reason/admin_locked_by nullable |
| `user_roles` | Tài khoản và các vai trò được cấp |
| `auth_sessions` | UUID phiên, chủ phiên, SHA-256 refresh token, thời điểm tạo/hết hạn/thu hồi |
| `password_reset_tokens` | V2: chủ tài khoản, SHA-256 reset token, thời điểm tạo/hết hạn/đã dùng |
| `roles`, `permissions`, `role_permissions` | V3: vai trò, quyền và ma trận cấp quyền |
| `account_activation_tokens` | V4: chủ tài khoản chờ kích hoạt, SHA-256 token, thời điểm tạo/hết hạn/đã dùng |
| `departments` | V5: UUID, mã duy nhất, tên, phòng cha, người phụ trách bắt buộc, active, thời điểm tạo |
| `positions` | V7: UUID, mã duy nhất, tên, cấp bậc, lương tối thiểu/tối đa (VND, `BIGINT`), active, thời điểm tạo/cập nhật |
| `flyway_schema_history` | Flyway tự tạo để ghi lịch sử migration |

Email unique, chữ thường, bỏ khoảng trắng đầu/cuối. Bảng/cột dùng `snake_case`, Java dùng `camelCase`. Vai trò nội bộ: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Ứng viên không có tài khoản trong luồng này.

`TIMESTAMPTZ` lưu thời điểm có múi giờ; backend dùng UTC. Không lưu password, refresh token, reset token hoặc activation token gốc. API không trả hash ra client.

Flyway chạy migration một lần. V1 tạo tài khoản/phiên; V2 bổ sung reset token; V3 bổ sung ma trận quyền; V4 bổ sung kích hoạt tài khoản cho145/149; V5 bổ sung dữ liệu phòng ban/hồ sơ và SELF_PROFILE_WRITE cho sáu vai trò nội bộ; V6 bổ sung trạng thái khóa hành chính độc lập; V7 tạo danh mục chức danh và dải lương. Migration mới dùng số phiên bản kế tiếp, không sửa V1–V7 đã áp dụng. Hibernate `ddl-auto=validate` chỉ kiểm schema. Sao lưu database trước khi nâng cấp; khi backend khởi động, Flyway sẽ chạy các migration chưa áp dụng. Kiểm upgrade dùng PostgreSQL tạm, không tự chạy trên DB làm việc.

V6 giữ nguyên dữ liệu cũ và thêm ba cột mặc địnhNULL. `admin_locked_at` khácNULL nghĩa là bị Admin khóa; `admin_lock_reason` không trống/tốiđa500, `admin_locked_by` tham chiếu tài khoản Admin. Cả ba phải cùngNULL hoặc cùng có giá trị. FK RESTRICT không cho xóa tài khoản còn được tham chiếu là người khóa. Mở khóa xóa metadata hiện tại; chưa có lịch sử audit. `enabled` và `locked_until` giữ ý nghĩa kích hoạt/khóa15phút. Xem [API khóa/mở khóa](../api/account-locking.md).

V5 giữ dữ liệu hiện tại; ba cột mới của tài khoản cũ làNULL, không tự gán phòng ban hoặc tạo dữ liệu mẫu. Không cần tạo lại DB/copy lại.env. Phòng ban dùng `parent_id` tham chiếu chính bảng để lưu nhiều cấp; mỗi phòng bắt buộc có `manager_user_id` là tài khoản tồn tại. Mã/tên không trống, mã duy nhất. FK RESTRICT giữ phòng cha, người phụ trách và phòng đang có thành viên khỏi bị xóa tùy tiện; FK không tự cascade xóa tài khoản.

[API phòng ban 195–196](../api/departments.md) dùng lại schema của 194: tạo/sửa, danh sách/cây, kiểm người phụ trách và chu trình nhiều cấp. Mọi API ghi cây phối hợp bằng transaction advisory lock trước khi kiểm tra/ghi, tránh hai yêu cầu đồng thời tạo chu trình. CHECK của V5 chỉ chặn tự làm cha; SQL thủ công vẫn có thể tạo chu trình dài vì không đi qua service. Ngừng áp dụng giữ các phòng con và liên kết nhân sự. Task 197 về xóa khi có yêu cầu tuyển dụng mở còn phụ thuộc bảng/module chưa có; chưa có endpoint DELETE hoặc nghiệm thu toàn bộ story 23. Nhóm 195–196 không thêm migration mới.

V7 chỉ tạo bảng mới `positions`, không đổi dữ liệu hoặc quyền hiện có. Mỗi chức danh có `code` duy nhất (tối đa 50 ký tự, ràng buộc `positions_code_key`), `name` (tối đa 255) và `level` là cấp bậc dạng chữ tự do như `Junior` (tối đa 50); cả ba không trống, không có dấu cách ở đầu/cuối. Lương là số nguyên đồng VND, lưu `BIGINT` và Java dùng `long`, không dùng số thực. CHECK bắt buộc `0 <= salary_min <= salary_max`; cho phép lương tối thiểu bằng tối đa. Dải lương này là hạn mức công ty đã duyệt, dùng để so mức lương đề xuất khi duyệt offer về sau. `active` mặc định TRUE; `created_at`/`updated_at` do backend ghi theo UTC, không có giá trị mặc định. Task 202 chỉ có schema, entity `Position` và `PositionRepository`. Task 203 dùng lại schema này cho [API chức danh](../api/positions.md), không thêm migration; chưa có quyền riêng để xem dải lương. Task 204 chỉ thêm kiểm tra ở API (mỗi mức lương tối đa 1.000.000.000.000 đồng, lỗi theo trường khi lương tối thiểu lớn hơn tối đa); V7 giữ nguyên nên trần lương không có CHECK trong database.

Nếu cần phục hồi schema/dữ liệu, dùng bản sao lưu đã kiểm tra; không xóa dòng flyway_schema_history hoặc sửa nội dung migration cũ. Các cột nullable cho phép giữ dữ liệu cũ trong quá trình chuyển đổi; rollback phiên bản ứng dụng không đồng nghĩa rollback schema.

Test dùng PostgreSQL tạm thời thật để kiểm transaction, khóa hàng và constraint; không dùng H2 thay thế. Test nâng cấp cố định `target` của Flyway tới phiên bản mà nó kiểm (ví dụ V4→V6), nên thêm migration mới không làm đổi số migration được chạy trong các test cũ.
