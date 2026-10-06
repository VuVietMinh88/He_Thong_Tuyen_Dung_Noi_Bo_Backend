# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring Data JPA, PostgreSQL.
Chưa có nghiệp vụ hoặc migration; Hibernate không tự tạo bảng (`ddl-auto=none`).

## Yêu cầu môi trường

- Git và JDK 21 trở lên.
- Docker Desktop (Linux containers) hoặc Docker Engine với Docker Compose.
- Maven Wrapper có sẵn; không cần cài Maven hoặc PostgreSQL native.

Các lệnh dưới chạy tại thư mục gốc repository Backend. Docker Engine phải đang hoạt động.

## Khởi động PostgreSQL

```sh
docker compose up -d
docker ps --filter "name=recruitment-postgres"
docker compose ps
docker compose exec postgres pg_isready -U recruitment -d recruitment
docker compose exec postgres psql -U recruitment -d recruitment -c "SHOW timezone;"
```

Compose chạy `postgres:16`, container `recruitment-postgres`, database/user `recruitment`, password local `recruitment123`, host `localhost`, port `5432`, timezone `UTC`. Port chỉ được publish trên loopback `127.0.0.1`.
Container có healthcheck; chờ trạng thái `healthy` trước khi chạy Backend.

Dữ liệu nằm trong Docker named volume **recruitment_postgres_data**, mount tại `/var/lib/postgresql/data`, không nằm trong source code hoặc Git. Không commit database data, dump hoặc backup.
Credential trên chỉ dành cho môi trường local dùng chung của team, không dùng cho production.

Nếu port 5432 hoặc tên container đã được sử dụng, kiểm tra `docker ps` và môi trường PostgreSQL hiện tại trước khi chạy. Không tự xóa container/volume có dữ liệu. Một container tạo bằng `docker run` không được Compose tự nhận quản lý; cần thống nhất cách chuyển đổi và bảo toàn dữ liệu trước khi thay thế.
Các biến POSTGRES_DB/USER/PASSWORD khởi tạo database khi volume mới; đổi chúng không tự cập nhật credential của database trong volume đã có dữ liệu.

## Cấu hình Backend local

Sao chép `.env.example` thành `.env`:

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Linux/macOS:

```sh
cp .env.example .env
```

Nếu đã có `.env`, cập nhật các giá trị cần thiết thay vì ghi đè. Spring Boot đọc `.env` theo định dạng properties qua `spring.config.import` hiện tại:

```properties
DB_URL=jdbc:postgresql://localhost:5432/recruitment
DB_USERNAME=recruitment
DB_PASSWORD=recruitment123
SERVER_PORT=8080
```

`.env` là cấu hình riêng trên máy và đã bị Git ignore; không commit file này hoặc secret thật. `.env.example` chỉ chứa giá trị local mẫu đã thống nhất.
Compose dùng giá trị local trong `docker-compose.yml`; thay đổi `.env` của Backend không tự đổi cấu hình database của Compose.
Không thay đổi CORS hoặc API contract trong công việc này.

## Chạy Backend

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC'
```

Linux/macOS hoặc shell hỗ trợ `mvnw`:

```sh
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments='-Duser.timezone=UTC'
```

Trên một số môi trường Windows/JDK, JVM dùng `Asia/Saigon`. Trong môi trường đã kiểm chứng, identifier này bị PostgreSQL từ chối khi JDBC mở kết nối, gây `FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"`. Tham số `-Duser.timezone=UTC` áp dụng cho lần chạy JVM để tránh lỗi; đây không phải lỗi chung của Java 24 hay PostgreSQL. Timezone UTC của container không thay thế tham số JVM này.

Dấu hiệu khởi động thành công: `HikariPool-1 - Start completed`, `Tomcat started on port 8080`, `Started RecruitmentApplication`.

Kiểm tra API kết nối:

```sh
curl http://localhost:8080/api/health
```

Expected response khi thay đổi của Subtask TKNHTTDNB1-584 đã được tích hợp: HTTP 200, `{"status":"UP"}`.
**Lưu ý:** branch này xuất phát từ `develop`, hiện chưa có endpoint health/CORS. Các thay đổi đó nằm trong [PR #1](https://github.com/VuVietMinh88/He_Thong_Tuyen_Dung_Noi_Bo_Backend/pull/1); nếu chưa tích hợp, HTTP 404 tại `/api/health` không phải lỗi Docker/PostgreSQL. Không thêm hoặc thay đổi API trong PR chuẩn hóa database này.

Để kiểm tra integration sau khi PR API được tích hợp, Frontend dùng `VITE_API_BASE_URL=http://localhost:8080/api`, chạy `npm ci` và `npm run dev`, mở `http://localhost:5173`, bấm **Kiểm tra kết nối Backend**. Backend cần giữ origin CORS tương ứng theo cấu hình của PR API.

## Dừng và gỡ PostgreSQL local

Dừng container, giữ dữ liệu:

```sh
docker compose stop
```

Gỡ container/network, mặc định **giữ named volume**:

```sh
docker compose down
```

Chỉ khi chủ động muốn xóa toàn bộ database local:

```sh
docker compose down -v
```

**Cảnh báo:** `-v` xóa named volume và dữ liệu PostgreSQL local. Không dùng khi cần giữ dữ liệu.

## Kiểm tra build

Windows: `./mvnw.cmd clean verify`; Linux/macOS: `sh ./mvnw clean verify`.
Build thành công không thay thế việc kiểm tra PostgreSQL và Backend đang chạy thực tế.

## Git Flow

main ổn định, develop tích hợp.
Branch `chore/TKNHTTDNB1-postgres-docker-compose` được tạo từ develop.
Branch cá nhân feature/bugfix/refactor/chore → PR → review ít nhất một thành viên + CI/test → develop.
Develop ổn định → PR + review → main. Không push trực tiếp main/develop.
Conflict xử lý trên branch cá nhân, test/build lại trước merge.
