# Dữ liệu API đăng nhập

SQL gốc ở `database/migrations/`. Maven đóng gói SQL vào `db/migration/` trong backend JAR, nên JAR chạy migration được trên máy khác. Không có bản SQL thứ hai trong `src/main/resources`.

`database/seeds/` dành cho script tạo dữ liệu mẫu theo cấu trúc chung của nhóm; hiện chỉ có `.gitkeep` để giữ thư mục trong Git. Khi bổ sung seed, cần hướng dẫn chạy riêng. Flyway hiện chỉ nạp các migration được Maven đóng gói từ `database/migrations/`.

| Bảng | Nội dung |
|---|---|
| `user_accounts` | UUID, email, tên, BCrypt hash, trạng thái, số lần sai, thời điểm hết khóa/tạo |
| `user_roles` | Tài khoản và các vai trò được cấp |
| `auth_sessions` | UUID phiên, chủ phiên, SHA-256 refresh token, thời điểm tạo/hết hạn/thu hồi |
| `flyway_schema_history` | Flyway tự tạo để ghi lịch sử migration |

Email unique, chữ thường, bỏ khoảng trắng đầu/cuối. Bảng/cột dùng `snake_case`, Java dùng `camelCase`. Vai trò nội bộ: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Ứng viên không có tài khoản trong luồng này.

`TIMESTAMPTZ` lưu thời điểm có múi giờ; backend dùng UTC. Không lưu password hoặc refresh token gốc. API không trả hash ra client.

Flyway chạy migration một lần. Đã áp dụng V1 thì tạo `V2__ten_thay_doi.sql`, không sửa V1. Hibernate `ddl-auto=validate` chỉ kiểm schema. V1 dành cho database mới; ghép với schema đã có dữ liệu cần đánh giá riêng trước khi chạy.

Test dùng PostgreSQL tạm thời thật để kiểm transaction, khóa hàng và constraint; không dùng H2 thay thế.
