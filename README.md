# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Java21, Spring Boot4.1.1, Maven Wrapper và PostgreSQL. Đây là repo Backend riêng, Maven chạy ngay từ thư mục gốc. Giữ kết nối Frontend của task584 và bổ sung các task backend theo từng commit/nhánh.

## Môi trường thống nhất của nhóm

Frontend và Backend nằm trong hai repo riêng. Backend dùng **PostgreSQL 16**, database và user `recruitment`, cổng `5432`, container `recruitment-postgres`. Cài và mở [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/), rồi làm theo [hướng dẫn Docker của nhóm](devops/docker/README.md).

Sao chép `.env.example` thành `.env` nếu chưa có; đặt `DB_PASSWORD` theo container local của nhóm, điền JWT secret và thông tin bootstrap admin riêng. Nếu đã chạy container bằng lệnh `docker run` của nhóm, dùng luôn container đó. Nếu chưa tạo container, chạy từ root Backend:

```powershell
docker compose --env-file .env -f devops/docker/compose.yaml up -d postgres
```

Frontend kết nối `http://localhost:8080/api/v1` khi chạy local. Bản chạy thật dùng tên miền `internal-hire.com` (Frontend), `api.internal-hire.com` (API) và gửi email từ `support@internal-hire.com`; xem [tên miền và email](docs/deployment/domain-and-mail.md). Backend giữ mã Java trong `src/`, SQL trong `database/migrations/`; Maven chạy ở root, không `cd backend`. Các commit đã chuyển từ repo cũ đã được điều chỉnh cấu trúc này. Thay đổi cấu hình tiếp theo được bổ sung bằng commit mới, giữ lịch sử đã chia sẻ.

## Chạy và kiểm thử

- Chọn JDK21 trở lên; từ thư mục gốc chạy ./mvnw.cmd clean verify (Windows) hoặc sh ./mvnw clean verify. Test dùng PostgreSQL 16 tạm qua embedded-postgres, không cần Docker và không dùng database trong .env.
- Sao chép .env.example thành .env nếu chưa có; điền DB_URL/DB_USERNAME/DB_PASSWORD, AUTH_JWT_SECRET và cấu hình riêng. Không commit secret hoặc chép đè cấu hình đang dùng. Flyway áp dụng migration; Hibernate chỉ validate schema.
- Chạy ./mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC' trên Windows; Linux/macOS dùng sh ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments='-Duser.timezone=UTC'. Main class RecruitmentApplication; working directory là repo root.
- GET /api/health vẫn trả JSON {"status":"UP"} không cần token để Frontend kiểm tra kết nối. GET /api/v1/health giữ contract backend có message. Đây là kiểm tra HTTP, không chứng minh database healthy.
- CORS_ALLOWED_ORIGINS là danh sách origin cụ thể phân cách bằng dấu phẩy. Endpoint health cũ chỉ GET/HEAD/OPTIONS; /api/v1/** hỗ trợ method của API và Authorization, không gửi credential cookie. CORS không thay thế quyền truy cập backend.

## Cấu trúc

- pom.xml, mvnw, mvnw.cmd, .mvn/: build từ repo root.
- src/main/java/vn/ttcs/recruitment/: application, account, auth, security, health và các module được thêm theo task.
- src/main/resources/: cấu hình; src/test/java/: unit/integration tests.
- database/migrations/: lịch sử S5 và các bước bổ sung, đóng gói vào db/migration.
- database/migrations-feedback/: lịch sử feedback và các bước bổ sung, đóng gói vào db/migration-feedback.
- database/migrations-shared/: migration mới từ V55 dùng chung cho cả hai lịch sử.
- docs/: hợp đồng API, architecture, database và cách chạy. devops/docker/: PostgreSQL local tùy chọn.

## Chọn lịch sử database khi chạy bản backend thống nhất

Một backend chứa cả tính năng ứng viên/Sprint 5 và feedback/auth. Với database mới, dùng `APP_MIGRATION_LINEAGE=s5` (mặc định). Với database đã có dữ liệu, chọn `s5` hoặc `feedback` theo tên script V26 trong `flyway_schema_history`; không đổi lựa chọn sau khi V26 đã chạy. Backend giữ nguyên SQL cũ và chỉ chạy các bước còn thiếu để hai lịch sử đạt cùng cấu trúc.

Xem [bảng đối chiếu và cách nâng cấp database](docs/database/migration-lineages.md) trước khi nối bản này với database cũ. Không đổi tên SQL đã chạy, dùng `repair` hoặc bật out-of-order để bỏ qua xung đột.

## Tài liệu API đang có

- [account-handover](docs/api/account-handover.md)
- [account-import](docs/api/account-import.md)
- [account-locking](docs/api/account-locking.md)
- [account-roles](docs/api/account-roles.md)
- [application-storage](docs/api/application-storage.md)
- [public-applications](docs/api/public-applications.md)
- [application-duplicates](docs/api/application-duplicates.md)
- [application-ip-quota](docs/api/application-ip-quota.md)
- [application-confirmation](docs/api/application-confirmation.md)
- [application-tracking](docs/api/application-tracking.md)
- [application-board](docs/api/application-board.md)
- [application-board-positions](docs/api/application-board-positions.md)
- [application-board-drag-drop](docs/api/application-board-drag-drop.md)
- [application-stages](docs/api/application-stages.md)
- [application-screening](docs/api/application-screening.md)
- [application-timeline](docs/api/application-timeline.md)
- [candidate-profiles](docs/api/candidate-profiles.md)
- [application-referrals](docs/api/application-referrals.md)
- [cv-suggestions](docs/api/cv-suggestions.md)
- [confirmed-contacts](docs/api/confirmed-contacts.md)
- [approval-flows](docs/api/approval-flows.md)
- [accounts](docs/api/accounts.md)
- [auth](docs/api/auth.md)
- [avatars](docs/api/avatars.md)
- [company-profile](docs/api/company-profile.md)
- [competency-frameworks](docs/api/competency-frameworks.md)
- [departments](docs/api/departments.md)
- [evaluation-criteria](docs/api/evaluation-criteria.md)
- [headcount-plans](docs/api/headcount-plans.md)
- [interview-questions](docs/api/interview-questions.md)
- [interviews](docs/api/interviews.md)
- [interview-invitations](docs/api/interview-invitations.md)
- [interview-evaluations](docs/api/interview-evaluations.md)
- [evaluation-reports](docs/api/evaluation-reports.md)
- [personal-interview-calendar](docs/api/personal-interview-calendar.md)
- [interview-question-usage](docs/api/interview-question-usage.md)
- [hiring-decisions](docs/api/hiring-decisions.md)
- [job-posting-portal](docs/api/job-posting-portal.md)
- [job-posting-publication](docs/api/job-posting-publication.md)
- [job-postings](docs/api/job-postings.md)
- [password-reset](docs/api/password-reset.md)
- [positions](docs/api/positions.md)
- [profile](docs/api/profile.md)
- [recruitment-catalogs](docs/api/recruitment-catalogs.md)
- [requisition-approvals](docs/api/requisition-approvals.md)
- [requisition-recruiters](docs/api/requisition-recruiters.md)
- [requisition-status](docs/api/requisition-status.md)
- [requisitions](docs/api/requisitions.md)

Phân quyền: [thiết kế](docs/architecture/authorization.md) và [ma trận vai trò và quyền](docs/architecture/role-permission-matrix.md) (đề xuất chờ BA/PO xác nhận).

Xem [cách chạy](docs/getting-started.md), [database](docs/database/README.md) và [Git flow](docs/scrum/git-workflow.md). Nhánh chứa Subtask ID ngay sau dấu /; commit và tiêu đề PR type: description không có ID. Chỉ push nhánh cá nhân và PR vào develop, chờ ít nhất một teammate review cùng CI/Test; không push trực tiếp main/develop hoặc tự merge.
