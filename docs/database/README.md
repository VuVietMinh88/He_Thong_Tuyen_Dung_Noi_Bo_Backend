# Dữ liệu API đăng nhập

SQL gốc ở `database/migrations/`. Maven đóng gói SQL vào `db/migration/` trong backend JAR, nên JAR chạy migration được trên máy khác. Không có bản SQL thứ hai trong `src/main/resources`.

`database/seeds/` dành cho script tạo dữ liệu mẫu theo cấu trúc chung của nhóm; hiện chỉ có `.gitkeep` để giữ thư mục trong Git. Khi bổ sung seed, cần hướng dẫn chạy riêng. Flyway hiện chỉ nạp các migration được Maven đóng gói từ `database/migrations/`.

| Bảng | Nội dung |
|---|---|
| `user_accounts` | UUID, email, tên, BCrypt hash, trạng thái, số lần sai, thời điểm hết khóa/tạo |
| `user_roles` | Tài khoản và các vai trò được cấp |
| `auth_sessions` | UUID phiên, chủ phiên, SHA-256 refresh token, thời điểm tạo/hết hạn/thu hồi |
| `password_reset_tokens` | V2: chủ tài khoản, SHA-256 reset token, thời điểm tạo/hết hạn/đã dùng |
| `roles`, `permissions`, `role_permissions` | V3: vai trò, quyền và ma trận cấp quyền |
| `account_activation_tokens` | V4: chủ tài khoản chờ kích hoạt, SHA-256 token, thời điểm tạo/hết hạn/đã dùng |
| `flyway_schema_history` | Flyway tự tạo để ghi lịch sử migration |

Email unique, chữ thường, bỏ khoảng trắng đầu/cuối. Bảng/cột dùng `snake_case`, Java dùng `camelCase`. Vai trò nội bộ: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Ứng viên không có tài khoản trong luồng này.

`TIMESTAMPTZ` lưu thời điểm có múi giờ; backend dùng UTC. Không lưu password, refresh token, reset token hoặc activation token gốc. API không trả hash ra client.

Flyway chạy migration một lần. V1 tạo tài khoản/phiên; V2 bổ sung reset token; V3 bổ sung ma trận quyền; V4 bổ sung kích hoạt tài khoản cho145/149. Migration mới dùngV5, không sửaV1–V4 đã áp dụng. Hibernate `ddl-auto=validate` chỉ kiểm schema. Sao lưu database trước khi nâng cấp; khi backend khởi động, Flyway sẽ chạy các migration chưa áp dụng. Kiểm upgrade dùng PostgreSQL tạm, không tự chạy trên DB làm việc.

Test dùng PostgreSQL tạm thời thật để kiểm transaction, khóa hàng và constraint; không dùng H2 thay thế.
