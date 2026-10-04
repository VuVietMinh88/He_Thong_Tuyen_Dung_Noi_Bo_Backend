# Dữ liệu API đăng nhập

SQL gốc ở `database/migrations/`. Maven đóng gói SQL vào `db/migration/` trong backend JAR, nên JAR chạy migration được trên máy khác. Không có bản SQL thứ hai trong `src/main/resources`.

`database/seeds/` dành cho script tạo dữ liệu mẫu theo cấu trúc chung của nhóm; hiện chỉ có `.gitkeep` để giữ thư mục trong Git. Khi bổ sung seed, cần hướng dẫn chạy riêng. Flyway hiện chỉ nạp các migration được Maven đóng gói từ `database/migrations/`.

| Bảng | Nội dung |
|---|---|
| `user_accounts` | UUID, email, tên, BCrypt hash, trạng thái, số lần sai, thời điểm hết khóa/tạo |
| `user_roles` | Tài khoản và các vai trò được cấp |
| `auth_sessions` | UUID phiên, chủ phiên, SHA-256 refresh token, thời điểm tạo/hết hạn/thu hồi |
| `password_reset_tokens` | V2: chủ tài khoản, SHA-256 reset token, thời điểm tạo/hết hạn/đã dùng |
| `flyway_schema_history` | Flyway tự tạo để ghi lịch sử migration |

Email unique, chữ thường, bỏ khoảng trắng đầu/cuối. Bảng/cột dùng `snake_case`, Java dùng `camelCase`. Vai trò nội bộ: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Ứng viên không có tài khoản trong luồng này.

`TIMESTAMPTZ` lưu thời điểm có múi giờ; backend dùng UTC. Không lưu password, refresh token hoặc reset token gốc. API không trả hash ra client.

Flyway chạy migration một lần. V1 tạo tài khoản/phiên; V2 bổ sung `password_reset_tokens` cho task106–110, không đổi dữ liệuV1. Migration tiếp theo dùngV3, không sửaV1/V2 đã áp dụng. Hibernate `ddl-auto=validate` chỉ kiểm schema. Sao lưu database trước khi nâng cấp; khi backend khởi động với DB đang ởV1, Flyway sẽ tự chạyV2. Trong lần triển khai này chỉ kiểm upgrade trên PostgreSQL tạm.

Test dùng PostgreSQL tạm thời thật để kiểm transaction, khóa hàng và constraint; không dùng H2 thay thế.
