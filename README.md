# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring Data JPA, PostgreSQL.
Subtask [TKNHTTDNB1-584](https://ttcs-k3s4-n3.atlassian.net/browse/TKNHTTDNB1-584): kết nối Backend/Frontend bằng REST API. Chưa có nghiệp vụ, authentication hoặc migration.

## Chạy Backend cùng Frontend

### 1. Khởi động PostgreSQL

Cài JDK 21 trở lên, chạy PostgreSQL tại `localhost:5432` và tạo database `recruitment`.
Sao chép `.env.example` thành `.env`, điền `DB_USERNAME`, `DB_PASSWORD` và kiểm tra `DB_URL`. Đây là cấu hình local; không commit `.env` hoặc thông tin bí mật.
Giữ `SERVER_PORT=8080`, `CORS_ALLOWED_ORIGINS=http://localhost:5173`.

Hibernate không tự tạo bảng (`ddl-auto=none`). Backend vẫn cần PostgreSQL để khởi động do cấu hình JPA hiện tại.

### 2. Khởi động Spring Boot

Từ thư mục Backend, chạy với timezone UTC để tránh lỗi kết nối đã được xác nhận trên môi trường Windows/JDK:

Windows PowerShell:

```powershell
./mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC'
```

Linux/macOS hoặc shell hỗ trợ `mvnw`:

```sh
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments='-Duser.timezone=UTC'
```

Trên một số môi trường Windows/JDK, JVM sử dụng timezone `Asia/Saigon`. Trong môi trường đã kiểm chứng, PostgreSQL không chấp nhận identifier này khi JDBC mở kết nối và báo `FATAL: invalid value for parameter "TimeZone": "Asia/Saigon"`. Chạy JVM với `-Duser.timezone=UTC` tránh lỗi đó cho lần chạy ứng dụng; không cần đổi database configuration. Đây là vấn đề timezone của môi trường JVM khi kết nối PostgreSQL, không phải lỗi chung của Java 24 hoặc PostgreSQL.

Dấu hiệu khởi động thành công: `Tomcat started on port 8080` và `Started RecruitmentApplication`.

### 3. Kiểm tra Backend

```sh
curl http://localhost:8080/api/health
```

Expected response: `{"status":"UP"}`. Chi tiết API contract và CORS ở phần dưới.

### 4. Khởi động Frontend

Ở repository Frontend, sao chép `.env.example` thành `.env` với `VITE_API_BASE_URL=http://localhost:8080/api`, rồi chạy:

```sh
npm ci
npm run dev
```

Mở `http://localhost:5173`, bấm **Kiểm tra kết nối Backend**. Thành công hiển thị `UP`.

## API contract và CORS

`GET http://localhost:8080/api/health` trả HTTP 200, Content-Type JSON:

```json
{"status":"UP"}
```

Endpoint xác nhận ứng dụng đang phục vụ HTTP; không phải kiểm tra sức khỏe database hoặc API nghiệp vụ.
Có thể gọi `curl http://localhost:8080/api/health`. Để kiểm tra CORS, gửi header `Origin: http://localhost:5173`; response có `Access-Control-Allow-Origin: http://localhost:5173`.
Preflight OPTIONS với `Access-Control-Request-Method: GET` cũng được chấp nhận.

CORS áp dụng `/api/**`, chỉ cho GET/HEAD/OPTIONS, headers Accept/Content-Type, không gửi credentials.
`CORS_ALLOWED_ORIGINS` nhận các origin cụ thể cách nhau bằng dấu phẩy; production đặt domain Frontend thực tế (scheme + host + port, không có path). Không dùng wildcard.
Khi đổi port/domain Frontend phải cập nhật origin. CORS không thay thế authentication/authorization.

## Kiểm tra

Windows: `./mvnw.cmd clean verify`; Linux/macOS: `sh ./mvnw clean verify`.
`HealthApiTest` kiểm tra JSON contract, origin được phép/bị chặn, preflight và cấu hình production qua Spring MVC MockMvc, không cần database.
Test này không chứng minh kết nối browser → Backend → PostgreSQL lúc chạy thực tế. Kiểm tra đó cần PostgreSQL và hai ứng dụng đang chạy.

## Git Flow

Branch `feature/TKNHTTDNB1-584-integrate-api` được tạo từ develop.
Branch cá nhân feature/bugfix/refactor/chore → PR + review ít nhất một thành viên + CI/test → develop.
Develop ổn định → PR + review → main. Không push trực tiếp main/develop.
Conflict xử lý trên branch cá nhân, test/build lại trước merge.
