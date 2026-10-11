# API đăng nhập và cách đọc code

Luồng quên mật khẩu của các task106–110: [API đặt lại mật khẩu](password-reset.md).

[Khóa hành chính](account-locking.md) của162–166 chặn login/refresh/JWT và thu hồi các phiên tài khoản đích. Mở khóa không khôi phục token cũ; người dùng đăng nhập lại. Khóa hành chính độc lập với việc chặn trình duyệt 15 phút do nhập sai và trạng thái chờ kích hoạt.

TKNHTTDNB1-90 đăng nhập nhân sự nội bộ bằng email/mật khẩu. Backend trả vai trò; frontend dùng vai trò mở trang phù hợp trong subtask giao diện riêng. Ứng viên bên ngoài không có tài khoản nội bộ.

TKNHTTDNB1-93 hoàn thiện kiểm tra Access Token trên luồng đăng nhập này. API và cấu trúc JSON giữ tương thích với phần giao diện.

## Đăng nhập

**POST** `http://localhost:8080/api/v1/auth/login`, headers `Content-Type: application/json` và `X-Device-Id: <UUID của trình duyệt>`.

Frontend tạo UUID ngẫu nhiên một lần và giữ trong localStorage qua tải lại trang, đổi email và đăng xuất. Mọi lần đăng nhập của cùng trình duyệt gửi lại UUID này. Thiếu hoặc sai định dạng header trả **400** `LOGIN_DEVICE_REQUIRED`. Client cũ cần cập nhật cùng đợt phát hành backend.

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

### Quy tắc Access Token

Backend chỉ cấp token sau khi xác thực thành công. JWT được ký bằng **HS256**, với các trường sau:

| Trường trong JWT | Ý nghĩa |
|---|---|
| `iss` | Nơi cấp token: `ttcs-backend` |
| `aud` | API nhận token: `ttcs-api` |
| `sub` | UUID tài khoản đã đăng nhập |
| `jti` | UUID phiên đăng nhập trong `auth_sessions` |
| `iat` | Thời điểm cấp token |
| `exp` | Thời điểm hết hạn, sau thời điểm cấp 900 giây |

JWT được ký, không được mã hóa nội dung. Không đưa mật khẩu hoặc dữ liệu hồ sơ vào token. Vai trò hiện tại được đọc từ database khi gọi API cần xác thực.

Client gửi `Authorization: Bearer <accessToken>` khi gọi `/me` hoặc `/logout`. Backend kiểm chữ ký, nơi cấp, API nhận, thời hạn và phiên đăng nhập còn hoạt động, sau đó kiểm tài khoản còn được bật. Token thiếu `exp`, hết hạn, bị sửa hoặc không thuộc phiên hợp lệ đều trả **401** với `code=UNAUTHORIZED`. Nếu token có `nbf` (thời điểm bắt đầu được dùng), backend cũng kiểm tra thời điểm đó.

Token chỉ có hiệu lực khi thời gian hiện tại **nhỏ hơn** `exp`: ví dụ cấp lúc 10:00:00 thì hết hạn từ 10:15:00. `expiresIn` trong response là thời hạn lúc cấp, không phải bộ đếm tự cập nhật. Thời hạn 15 phút là cấu hình hiện có của dự án; subtask Jira 93 không quy định thời hạn riêng.

Email lạ, sai password, tài khoản vô hiệu hóa và bị Admin khóa đều trả **401** khi trình duyệt chưa tới ngưỡng chặn, cùng nội dung:

```json
{
  "code": "LOGIN_FAILED",
  "message": "Không thể đăng nhập bằng thông tin đã cung cấp.",
  "fieldErrors": {}
}
```

Sau 5 lần đăng nhập bị từ chối liên tiếp trên một trình duyệt, chặn trình duyệt đó 15 phút từ lần thứ 5. Bộ đếm dùng chung giữa các email, kể cả email không tồn tại; không sửa trạng thái tài khoản. Bốn lần đầu trả **401** `LOGIN_FAILED`; lần thứ 5 và các lần đang bị chặn trả **429** `LOGIN_DEVICE_LOCKED`, header `Retry-After` là số giây còn lại. CORS cho phép frontend đọc header này. Frontend hiển thị thời gian do backend trả, không tự khóa theo email.

Trong lúc bị chặn, mật khẩu đúng trên cùng trình duyệt vẫn bị từ chối; thử tiếp không kéo dài thời gian. Trình duyệt khác (kể cả cùng IP) vẫn đăng nhập được và không xóa khóa của trình duyệt trước. Phiên đang hoạt động không bị thu hồi bởi việc nhập sai. Đăng nhập thành công chỉ xóa bộ đếm của trình duyệt đang dùng. Email bỏ khoảng trắng hai đầu và chuyển chữ thường; password giữ nguyên.

Đây là nhận diện theo hồ sơ trình duyệt, không phải khóa máy vật lý: chế độ riêng tư, xóa dữ liệu trình duyệt hoặc chủ động thay UUID sẽ tạo định danh mới. UUID không phải thông tin xác thực. Khóa/bộ đếm lưu trong PostgreSQL nên backend khởi động lại không làm mất trạng thái. Khóa do quản trị viên vẫn chặn tài khoản trên mọi trình duyệt.

Thiếu email/password, email sai định dạng hoặc JSON sai trả **400**. Response validation có `code=VALIDATION_ERROR`, `message=Vui lòng kiểm tra dữ liệu đã nhập.`, `fieldErrors` gồm lỗi từng trường. JSON sai có `code=INVALID_JSON`.

Giới hạn đầu vào password là 200 ký tự. Password quá 72 byte UTF-8 bị tính là đăng nhập sai vì BCrypt chỉ nhận tối đa 72 byte (401, hoặc 429 khi trình duyệt tới ngưỡng chặn); quá cả 200 ký tự thì validation trả 400 trước. Response không có password hoặc password hash.

## Thử bằng PowerShell hoặc Postman

Sau khi API đang chạy, nhập password qua prompt để không lưu trực tiếp trong lịch sử terminal:

```powershell
$loginCredential = Get-Credential -UserName 'admin@congty.test' -Message 'Tài khoản API'
$browserId = [guid]::NewGuid().ToString() # Tạo một lần; dùng lại khi thử đăng nhập tiếp.
$loginJson = @{
    email = $loginCredential.UserName
    password = $loginCredential.GetNetworkCredential().Password
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post `
    -Uri 'http://localhost:8080/api/v1/auth/login' `
    -Headers @{ 'X-Device-Id' = $browserId } `
    -ContentType 'application/json' -Body $loginJson
$login.user
```

Thay email bằng Admin của bạn. Không in `$login` khi chia sẻ màn hình vì chứa token. Với Postman, chọn POST, **Body → raw → JSON**, gửi body cùng cấu trúc; thêm `X-Device-Id` trong tab Headers và giữ nguyên giá trị qua các lần thử.

## API hỗ trợ phiên

| Method/path | Đầu vào | Thành công |
|---|---|---|
| `GET /api/v1/health` | Không cần đăng nhập | 200, server đang chạy |
| `POST /api/v1/auth/login` | Header `X-Device-Id`; body `email`, `password` | 200, token và tài khoản |
| `GET /api/v1/auth/me` | `Authorization: Bearer <accessToken>` | 200, tài khoản hiện tại |
| `POST /api/v1/auth/refresh` | Body `{"refreshToken":"<token>"}` | 200, token mới |
| `POST /api/v1/auth/logout` | `Authorization: Bearer <accessToken>` | 204, không body |
| `POST /api/v1/auth/change-password` | Bearer token; body `currentPassword`, `newPassword` | 200, giữ phiên hiện tại và thu hồi phiên khác |
| `GET /api/v1/auth/permissions` | Bearer token | 200, danh sách quyền hiện hành |

Đổi mật khẩu khi đang đăng nhập:

```json
{"currentPassword":"<mật khẩu hiện tại>","newPassword":"<mật khẩu mới>"}
```

Mật khẩu mới cần ít nhất 8 ký tự, có chữ và số, tối đa 72 byte UTF-8 để BCrypt xử lý đầy đủ. Mật khẩu hiện tại sai trả 400 `CURRENT_PASSWORD_INCORRECT`; dữ liệu không đạt yêu cầu trả 400 `VALIDATION_ERROR`; thiếu/hết hạn Access Token trả 401. Thành công trả `{"message":"Đổi mật khẩu thành công. Các phiên đăng nhập khác đã được thu hồi."}` với `Cache-Control: no-store`. Refresh token của phiên hiện tại tiếp tục dùng được; các phiên khác bị từ chối ngay. Liên kết đặt lại mật khẩu đang còn hiệu lực của tài khoản cũng bị vô hiệu hóa.

`GET /api/v1/auth/permissions` trả `{"permissions":["..."]}` với `Cache-Control: no-store`. Mỗi quyền có mã gồm module, thao tác `READ`/`WRITE` và phạm vi `ALL`/`SCOPED`, ví dụ `CANDIDATES_READ_SCOPED`. Quyền được đọc lại từ PostgreSQL ở mỗi yêu cầu đã xác thực; frontend dùng danh sách này để hiển thị, còn backend quyết định quyền truy cập thực tế. Xem [thiết kế phân quyền](../architecture/authorization.md).

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

### Hợp đồng Refresh Token — TKNHTTDNB1-98

`POST /api/v1/auth/refresh` xác thực bằng `refreshToken` trong JSON. Không cần gửi access token; nếu interceptor vẫn gắn header `Authorization` cũ/hết hạn thì endpoint bỏ qua header này và kiểm tra refresh token. JWT gắn kèm không thể thay thế refresh token bị thiếu hoặc không hợp lệ.

Token đúng định dạng gồm43 ký tự Base64URL. Thiếu/null/sai định dạng trả 400 (`VALIDATION_ERROR`; JSON sai trả `INVALID_JSON`). Token đúng định dạng nhưng không tồn tại, bị thay thế, thu hồi, hết hạn hoặc tài khoản bị vô hiệu hóa trả 401 `SESSION_INVALID`, không gia hạn phiên. Lỗi không trả lại giá trị token.

Thành công trả 200 cùng cấu trúc JSON như login, có `Cache-Control: no-store`, không tạo cookie. Database lưu hash của refresh mới và thời hạn7 ngày tính từ lần gia hạn. Hai request đồng thời dùng cùng token chỉ một request thành công; frontend cần điều phối một request refresh tại một thời điểm và cập nhật đồng thời cả hai token. Xem [thiết kế phiên](../architecture/auth-sessions.md).

### Hợp đồng Logout — TKNHTTDNB1-99

`POST /api/v1/auth/logout` cần bearer access token còn hạn, không cần body. Thành công trả 204, body rỗng và `Cache-Control: no-store`. Backend khóa phiên, kiểm lại chủ sở hữu và trạng thái còn hoạt động rồi thu hồi. Logout không xóa tài khoản hoặc dữ liệu nghiệp vụ.

Thiếu JWT, JWT sai/hết hạn hoặc phiên đã bị thu hồi trả 401 `UNAUTHORIZED`; nếu phiên đổi trạng thái trong lúc request chờ khóa thì trả 401 `SESSION_INVALID`. Client xóa cặp token khi logout thành công hoặc nhận401; lỗi mạng/5xx cần được xử lý riêng vì chưa xác nhận server đã thu hồi phiên.

Logout thu hồi mọi access/refresh token của đúng phiên đó, kể cả token mới được cấp bởi một request refresh chạy đồng thời. Các phiên đăng nhập khác vẫn hoạt động. Gọi logout lần nữa bằng cùng phiên trả 401; không tạo hoặc phục hồi phiên.

### Hết hạn và khôi phục phiên — TKNHTTDNB1-100

- Access JWT hết hạn đúng sau 900 giây. Khi JWT vẫn còn hạn nhưng phiên database đã hết hạn/thu hồi, `/me` và `/logout` vẫn trả 401. JWT hợp lệ không bỏ qua trạng thái phiên.
- Refresh/phiên hết hạn đúng thời điểm `refreshExpiresAt`. Gọi `/me` không kéo dài thời hạn; chỉ refresh thành công mới đặt mốc 7 ngày mới. Refresh được gửi trước hạn nhưng phải chờ khóa tới sau hạn cũng bị từ chối.
- Header bearer cũ/sai/hết hạn không chặn `POST /login`; endpoint vẫn bắt buộc email/password hợp lệ. Login lại tạo phiên mới, không phục hồi phiên đã hết hạn.
- Với API cần đăng nhập: khi nhận401 `UNAUTHORIZED`, client refresh một lần, thay cặp token và retry request ban đầu tối đa một lần. Refresh401 `SESSION_INVALID` thì dừng và yêu cầu login lại. Không chạy vòng lặp refresh khi chính `/login` hoặc `/refresh` báo lỗi.
- Refresh400 là request sai;403 là thiếu quyền;5xx/mất mạng xử lý như lỗi dịch vụ/kết nối. Không xóa dữ liệu biểu mẫu chỉ vì một lỗi mạng.

Backend giữ nguyên các mã lỗi/JSON đã công bố. Phần bảo toàn dữ liệu đang nhập, điều phối các tab và giao diện đăng nhập lại thuộc các task frontend 97/101/102; client chịu trách nhiệm lưu bản nháp theo thiết kế của phần đó.

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
4. **`account/AccountRepository.java`** giao tiếp database qua Spring Data JPA. `findByEmailForUpdate()` tuần tự hóa login với đổi/reset mật khẩu và khóa quản trị.
5. **`auth/LoginDevice.java`** chứa quy tắc 5 lần/15 phút. `LoginDeviceRepository` tạo hàng nếu chưa có rồi khóa hàng trình duyệt trước khi đọc tài khoản. Login giữ transaction khi trả lỗi 401 hoặc 429 để không mất bộ đếm, kể cả các lần thử đồng thời. `account/Account.java` giữ mật khẩu/trạng thái tài khoản; các cột khóa đăng nhập cũ không còn quyết định đăng nhập.
6. **`security/AuthConfiguration.java`** cấp BCrypt, khóa ký và bộ kiểm JWT. BCrypt băm một chiều; `matches()` so sánh password, không giải mã. Xem [PasswordEncoder của Spring Security](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
7. **`auth/TokenService.java`** tạo JWT 15 phút, refresh token ngẫu nhiên. Database chỉ lưu SHA-256 refresh token; client giữ token gốc. Access token gắn với một phiên database.
8. **`security/SecurityConfiguration.java`** bảo vệ trước controller: kiểm chữ ký/thời hạn JWT và phiên/tài khoản. Endpoint chưa khai báo quyền bị chặn. Vai trò được đọc từ database, không tin dữ liệu client tự gửi.
9. **`common/ApiExceptionHandler.java`** đổi lỗi nghiệp vụ/validation thành JSON chung, không trả password.
10. **`database/migrations/V1__create_accounts_and_sessions.sql`** tạo bảng. Maven sao chép SQL vào JAR; Flyway chạy migration, Hibernate chỉ kiểm schema.

Muốn đổi thời gian chặn, đọc `LoginDevice`; đổi JSON response, đọc `TokenResponse`/`CurrentUserResponse`; thêm API, khai báo quyền tại `SecurityConfiguration` và thêm test. Bảng `login_devices` được thêm bằng migration mới, không sửa migration đã áp dụng.
