# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Skeleton mới cho API và xử lý server; chưa có nghiệp vụ hoặc migration.
Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring Data JPA, PostgreSQL.

## Chạy

Cần JDK 21 trở lên và PostgreSQL. Tạo database recruitment, sao chép .env.example thành .env và điền thông tin kết nối riêng. Không commit .env.
Windows: `./mvnw.cmd spring-boot:run`. Linux/macOS: `sh ./mvnw spring-boot:run`.
Build: `./mvnw.cmd clean verify` hoặc `sh ./mvnw clean verify`.
Chưa có test nghiệp vụ; verify kiểm tra build. Hibernate không tự tạo bảng; chưa thêm Flyway.

## Git Flow

main ổn định, develop tích hợp, được tạo từ main khi initialization.
Mỗi Jira Subtask có branch riêng chứa Subtask ID: feature/, bugfix/, refactor/, chore/.
Branch cá nhân → PR → review ít nhất một thành viên + CI/test → develop.
Develop ổn định → PR + review → main. Không push trực tiếp main/develop.
Conflict xử lý trên branch cá nhân, test/build lại trước merge.
