# API đăng nhập và cách đọc code

TKNHTTDNB1-90 đăng nhập nhân sự nội bộ bằng email/mật khẩu. Backend trả vai trò; frontend dùng vai trò mở trang phù hợp trong subtask giao diện riêng. Ứng viên bên ngoài không có tài khoản nội bộ.

## Đăng nhập

**POST** `http://localhost:8080/api/v1/auth/login`, header `Content-Type: application/json`.

```json
{
  "email": "admin@congty.test",
  "password": "MatKhauDemo123!"
}
```

Dữ liệu trên là minh họa; dùng email/password bạn cấu hình để tạo Admin, không dùng tài khoản PostgreSQL.

Thành công trả **200**:

```json
{
  "accessToken": "<JWT>",
  "refreshToken": "<token ngẫu nhiên>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresAt": "<thời điểm ISO 8601 UTC>",
  "user": {
    "id": "<UUID>",
    "email": "admin@congty.test",
    "fullName": "Quản trị viên",
    "roles": ["ADMIN"]
  }
}
```

Access token giống vé truy cập có thời hạn 15 phút (`900` giây), được ký để server phát hiện sửa đổi. Refresh token cấp vé mới, hết hạn sau 7 ngày nếu không gia hạn. Gia hạn thành công cấp refresh token mới và kéo dài phiên thêm 7 ngày. Không đưa token vào URL hoặc log.

Email lạ, sai password, tài khoản vô hiệu hóa và bị khóa đều trả **401**, cùng nội dung:

```json
{
  "code": "LOGIN_FAILED",
  "message": "Không thể đăng nhập bằng thông tin đã cung cấp.",
  "fieldErrors": {}
}
```

Sau 5 lần sai liên tiếp cho tài khoản có thật, khóa 15 phút từ lần sai thứ 5. Trong lúc khóa, password đúng vẫn bị từ chối; lần thử tiếp theo không kéo dài khóa. Đúng thời điểm hết khóa có thể thử lại. Đăng nhập thành công xóa bộ đếm sai. Email bỏ khoảng trắng hai đầu và chuyển chữ thường; password giữ nguyên.

Thiếu email/password, email sai định dạng hoặc JSON sai trả **400**. Response validation có `code=VALIDATION_ERROR`, `message=Vui lòng kiểm tra dữ liệu đã nhập.`, `fieldErrors` gồm lỗi từng trường. JSON sai có `code=INVALID_JSON`.

Giới hạn đầu vào password là 200 ký tự. Password quá 72 byte UTF-8 bị từ chối với 401 vì BCrypt chỉ nhận tối đa 72 byte; quá cả 200 ký tự thì validation trả 400 trước. Response không có password hoặc password hash.

## Thử bằng PowerShell hoặc Postman

Sau khi API đang chạy, nhập password qua prompt để không lưu trực tiếp trong lịch sử terminal:

```powershell
$loginCredential = Get-Credential -UserName 'admin@congty.test' -Message 'Tài khoản API'
$loginJson = @{
    email = $loginCredential.UserName
    password = $loginCredential.GetNetworkCredential().Password
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/api/v1/auth/login' `
    -ContentType 'application/json' -Body $loginJson
$login.user
```

Thay email bằng Admin của bạn. Không in `$login` khi chia sẻ màn hình vì chứa token. Với Postman, chọn POST, **Body → raw → JSON**, gửi body cùng cấu trúc.

## API hỗ trợ phiên

| Method/path | Đầu vào | Thành công |
|---|---|---|
| `GET /api/v1/health` | Không cần đăng nhập | 200, server đang chạy |
| `POST /api/v1/auth/login` | Body `email`, `password` | 200, token và tài khoản |
| `GET /api/v1/auth/me` | `Authorization: Bearer <accessToken>` | 200, tài khoản hiện tại |
| `POST /api/v1/auth/refresh` | Body `{"refreshToken":"<token>"}` | 200, token mới |
| `POST /api/v1/auth/logout` | `Authorization: Bearer <accessToken>` | 204, không body |

Xem người vừa đăng nhập:

```powershell
$authHeaders = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/auth/me' -Headers $authHeaders
```

Gia hạn khi access token sắp hết hoặc hết hạn:

```powershell
$refreshJson = @{ refreshToken = $login.refreshToken } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/api/v1/auth/refresh' `
    -ContentType 'application/json' -Body $refreshJson
$authHeaders = @{ Authorization = "Bearer $($login.accessToken)" }
```

Refresh token cũ dùng một lần; thay cả hai token bằng response mới. Logout hủy phiên hiện tại ngay trên server, kể cả access token chưa hết hạn; phiên trên thiết bị khác còn hoạt động. Access token đã hết hạn thì gia hạn trước logout. Refresh token hết hạn thì đăng nhập lại.

```powershell
Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/api/v1/auth/logout' -Headers $authHeaders
```

Response đăng nhập đặt `Cache-Control: no-store`. Luồng dùng Bearer header/body, không dùng cookie tự gửi của trình duyệt. CORS mặc định cho localhost 5173/3000 và các origin cấu hình rõ; CORS không thay thế xác thực hoặc phân quyền.

## Code hoạt động như thế nào

```text
Frontend / Postman gửi JSON
    → AuthController nhận và kiểm tra dữ liệu
    → AuthService kiểm tra tài khoản, password và khóa
    → AccountRepository đọc PostgreSQL
    → TokenService tạo token, AuthSessionRepository lưu phiên
    → AuthController trả JSON
```

1. **`auth/LoginRequest.java`** định nghĩa dữ liệu vào bằng Java `record` (một cấu trúc chứa dữ liệu gọn). `@NotBlank`, `@Email`, `@Size` là điều kiện Spring kiểm tra trước service. `toString()` che thông tin đăng nhập.
2. **`auth/AuthController.java`** định nghĩa URL/method. `@RestController` yêu cầu trả JSON. Controller gọi service và chọn HTTP response.
3. **`auth/AuthService.java`** tìm tài khoản, so sánh BCrypt, từ chối khóa/vô hiệu hóa, tăng bộ đếm sai, tạo phiên khi đúng. Email lạ cũng được so với hash giả để giảm chênh lệch thời gian giữa email có thật và không có thật.
4. **`account/AccountRepository.java`** giao tiếp database qua Spring Data JPA. `findByEmailForUpdate()` khóa hàng trong transaction để 5 request sai đồng thời không ghi đè bộ đếm của nhau.
5. **`account/Account.java`** ánh xạ bảng `user_accounts`, chứa quy tắc khóa 5 lần/15 phút. `@Transactional(noRollbackFor = AuthenticationFailureException.class)` trên login giữ bộ đếm sai khi ném lỗi 401; rollback mặc định sẽ làm mất bộ đếm.
6. **`security/AuthConfiguration.java`** cấp BCrypt, khóa ký và bộ kiểm JWT. BCrypt băm một chiều; `matches()` so sánh password, không giải mã. Xem [PasswordEncoder của Spring Security](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
7. **`auth/TokenService.java`** tạo JWT 15 phút, refresh token ngẫu nhiên. Database chỉ lưu SHA-256 refresh token; client giữ token gốc. Access token gắn với một phiên database.
8. **`security/SecurityConfiguration.java`** bảo vệ trước controller: kiểm chữ ký/thời hạn JWT và phiên/tài khoản. Endpoint chưa khai báo quyền bị chặn. Vai trò được đọc từ database, không tin dữ liệu client tự gửi.
9. **`common/ApiExceptionHandler.java`** đổi lỗi nghiệp vụ/validation thành JSON chung, không trả password.
10. **`database/migrations/V1__create_accounts_and_sessions.sql`** tạo bảng. Maven sao chép SQL vào JAR; Flyway chạy migration, Hibernate chỉ kiểm schema.

Muốn đổi thời gian khóa, đọc `Account`; đổi JSON response, đọc `TokenResponse`/`CurrentUserResponse`; thêm API, khai báo quyền tại `SecurityConfiguration` và thêm test. Quyền theo module/dữ liệu, quên/đổi mật khẩu và quản trị tài khoản thuộc các subtask riêng.
