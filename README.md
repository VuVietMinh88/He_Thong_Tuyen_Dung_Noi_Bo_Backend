# Hệ thống tuyển dụng nội bộ — Backend

Team K3S4_N3. Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring Data JPA, PostgreSQL.
Subtask [TKNHTTDNB1-584](https://ttcs-k3s4-n3.atlassian.net/browse/TKNHTTDNB1-584): kết nối Backend/Frontend bằng REST API. Chưa có nghiệp vụ, authentication hoặc migration.

## Chạy cùng Frontend

1. Cài JDK 21 trở lên, chạy PostgreSQL và tạo database `recruitment`.
2. Sao chép `.env.example` thành `.env`, điền `DB_USERNAME`, `DB_PASSWORD` và kiểm tra `DB_URL`. Không commit `.env`.
3. Giữ `SERVER_PORT=8080`, `CORS_ALLOWED_ORIGINS=http://localhost:5173`.
4. Từ thư mục Backend chạy Windows: `./mvnw.cmd spring-boot:run`; Linux/macOS: `sh ./mvnw spring-boot:run`.
5. Ở repository Frontend, chạy `npm ci`, sao chép `.env.example` thành `.env` với `VITE_API_BASE_URL=http://localhost:8080/api`, rồi `npm run dev`.
6. Mở `http://localhost:5173`, bấm **Kiểm tra kết nối Backend**. Thành công hiển thị `UP`.

Hibernate không tự tạo bảng (`ddl-auto=none`). Backend vẫn cần PostgreSQL để khởi động do cấu hình JPA hiện tại.

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
