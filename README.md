# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Java21, Spring Boot4.1.1, Maven Wrapper và PostgreSQL. Đây là repo Backend riêng, Maven chạy ngay từ thư mục gốc. Giữ kết nối Frontend của task584 và bổ sung các task backend theo từng commit/nhánh.

## Chạy và kiểm thử

- Chọn JDK21 trở lên; từ thư mục gốc chạy ./mvnw.cmd clean verify (Windows) hoặc sh ./mvnw clean verify. Test dùng PostgreSQL tạm, không dùng database trong .env.
- Sao chép .env.example thành .env nếu chưa có; điền DB_URL/DB_USERNAME/DB_PASSWORD, AUTH_JWT_SECRET và cấu hình riêng. Không commit secret hoặc chép đè cấu hình đang dùng. Flyway áp dụng migration; Hibernate chỉ validate schema.
- Chạy ./mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC' trên Windows; Linux/macOS dùng sh ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments='-Duser.timezone=UTC'. Main class RecruitmentApplication; working directory là repo root.
- GET /api/health vẫn trả JSON {"status":"UP"} không cần token để Frontend kiểm tra kết nối. GET /api/v1/health giữ contract backend có message. Đây là kiểm tra HTTP, không chứng minh database healthy.
- CORS_ALLOWED_ORIGINS là danh sách origin cụ thể phân cách bằng dấu phẩy. Endpoint health cũ chỉ GET/HEAD/OPTIONS; /api/v1/** hỗ trợ method của API và Authorization, không gửi credential cookie. CORS không thay thế quyền truy cập backend.

## Cấu trúc

- pom.xml, mvnw, mvnw.cmd, .mvn/: build từ repo root.
- src/main/java/vn/ttcs/recruitment/: application, account, auth, security, health và các module được thêm theo task.
- src/main/resources/: cấu hình; src/test/java/: unit/integration tests.
- database/migrations/: SQL được Maven đóng gói vào db/migration trong JAR.
- docs/: hợp đồng API, architecture, database và cách chạy. devops/docker/: PostgreSQL local tùy chọn.

## Tài liệu API đang có

- [account-roles](docs/api/account-roles.md)
- [accounts](docs/api/accounts.md)
- [auth](docs/api/auth.md)
- [password-reset](docs/api/password-reset.md)
- [profile](docs/api/profile.md)

Xem [cách chạy](docs/getting-started.md), [database](docs/database/README.md) và [Git flow](docs/scrum/git-workflow.md). Nhánh chứa Subtask ID ngay sau dấu /; commit và tiêu đề PR type: description không có ID. Chỉ push nhánh cá nhân và PR vào develop, chờ ít nhất một teammate review cùng CI/Test; không push trực tiếp main/develop hoặc tự merge.
