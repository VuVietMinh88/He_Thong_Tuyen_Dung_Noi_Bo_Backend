# Thiết kế Access Token, Refresh Token và phiên đăng nhập

TKNHTTDNB1-96, thuộc [story TKNHTTDNB1-11](https://ttcs-k3s4-n3.atlassian.net/browse/TKNHTTDNB1-11). Thiết kế dùng lại hợp đồng của [API xác thực](../api/auth.md), phục vụ các API refresh/logout và xử lý hết hạn phiên. Jira chưa quy định thời hạn riêng; 15 phút và 7 ngày dưới đây là chính sách hiện có của dự án.

## Token và dữ liệu lưu trữ

| Thành phần | Nội dung và cách dùng |
|---|---|
| Access token | JWT ký HS256, hiệu lực15phút, gửi bằng `Authorization: Bearer ...` |
| Claims | `iss=ttcs-backend`, `aud=ttcs-api`, `sub=account UUID`, `jti=session UUID`, `iat`, `exp` |
| Refresh token | 32byte ngẫu nhiên từ SecureRandom, Base64URL không padding,43ký tự; gửi trong JSON của `/refresh` |
| Database | `auth_sessions` lưu session/user ID, SHA-256 của refresh token, thời điểm tạo/hết hạn/thu hồi; không lưu token gốc |
| Tài khoản | Mật khẩu BCrypt; enabled và vai trò lấy lại từ database khi xác thực, không tin vai trò do client gửi |

JWT được ký chứ không mã hóa; không chứa mật khẩu hoặc hồ sơ tuyển dụng. `jti` định danh phiên, nên các JWT cấp trong cùng một phiên cùng trỏ về một bản ghi. Thu hồi phiên vô hiệu hóa cả JWT chưa hết hạn. Các phản hồi chứa token hoặc thông tin xác thực đặt `Cache-Control: no-store`; không ghi token vào URL/log.

## Trạng thái và thời hạn

Phiên hoạt động khi `revoked_at IS NULL` và `now < expires_at`, đồng thời tài khoản còn enabled. Access JWT còn phải vượt qua kiểm tra chữ ký, issuer, audience, exp bắt buộc và nbf nếu có. Tại đúng mốc hết hạn token/phiên đã không hợp lệ.

| Thao tác | Chuyển trạng thái |
|---|---|
| Login thành công | Tạo phiên mới với refresh hết hạn sau7ngày; thiết bị khác có phiên độc lập |
| GET `/me` hoặc gọi API | Kiểm tra phiên; không tự kéo dài thời hạn refresh |
| Refresh thành công | Giữ session ID, thay hash refresh, kéo dài hạn7ngày từ lần refresh; cấp access JWT15phút |
| Refresh cũ/không hợp lệ | Trả401, không gia hạn hay thay hash đang hợp lệ |
| Logout | Đặt revoked_at trên phiên hiện tại; không ảnh hưởng phiên thiết bị khác |
| Phiên hết hạn/thu hồi | Không cấp token mới qua refresh; người dùng đăng nhập lại để tạo phiên mới |

Không dùng cookie xác thực tự gửi, không dựa vào HTTP session của server. CORS chỉ cho các origin cấu hình; nó không thay thế kiểm tra quyền.

## API và xử lý đồng thời

- `POST /login`: xác thực email/password trước khi tạo phiên. Header bearer cũ không phải thông tin xác thực của endpoint này.
- `POST /refresh`: xác thực bằng refresh token trong body; không cần access JWT còn hạn. Header bearer hết hạn do interceptor gắn kèm không được chặn refresh hợp lệ. Trả cùng cấu trúc TokenResponse như login, thay cả access/refresh ở client.
- `POST /logout`: dùng access JWT còn hạn. Trong transaction, khóa phiên theo jti và kiểm lại sub/phiên hoạt động trước khi thu hồi. Trả204 không body. Gọi lại với phiên đã thu hồi trả401; client vẫn xóa thông tin phiên local.
- `GET /me`: cần JWT hợp lệ, session tồn tại đúng user và còn hoạt động, account enabled.

Refresh dùng khóa ghi trên bản ghi tìm bằng hash; hai request dùng cùng refresh token chỉ một request được200, request còn lại401. Logout khóa cùng bản ghi. Nếu refresh thắng trước, logout thu hồi luôn cặp token vừa cấp; nếu logout thắng trước, refresh bị từ chối. Phiên đã thu hồi không thể được phục hồi bởi refresh.

Rotation làm token cũ hết hiệu lực. Phiên hiện tại chỉ lưu hash mới, không lưu lịch sử token để phát hiện đánh cắp/tự thu hồi toàn bộ token family. Client cần một luồng refresh tại một thời điểm, tránh retry token cũ sau khi mất response; khi không có cặp token hợp lệ phải đăng nhập lại.

## Lỗi và trách nhiệm frontend

| Tình huống | HTTP / code | Xử lý client |
|---|---|---|
| Sai thông tin login |401 `LOGIN_FAILED` | Hiện lỗi chung |
| JWT không hợp lệ, hết hạn hoặc session không còn hoạt động |401 `UNAUTHORIZED` | Với request cần đăng nhập, thử refresh một lần nếu còn refresh token |
| Refresh sai, hết hạn, bị thay thế/thu hồi hoặc account disabled |401 `SESSION_INVALID` | Dừng refresh, xóa token, yêu cầu đăng nhập lại |
| JSON/refresh token sai định dạng |400 `INVALID_JSON` / `VALIDATION_ERROR` | Sửa request; không retry vô hạn |
| Không có quyền |403 `FORBIDDEN` | Không refresh để chữa lỗi phân quyền |

Khi access hết hạn, client gửi refresh rồi retry request ban đầu tối đa một lần. Khi logout với access hết hạn, refresh trước rồi logout bằng access mới; nếu refresh thất bại thì phiên không còn dùng được và client xóa token. Mất mạng/5xx không đồng nghĩa refresh token bị thu hồi: xử lý lỗi kết nối riêng, không lặp refresh vô hạn.

Việc giữ bản nháp phiếu đánh giá, chọn nơi lưu token và điều phối các tab do các task frontend97/101/102 thực hiện. Backend không lưu hoặc xóa dữ liệu đang nhập trong trình duyệt, cũng không replay thao tác ghi nghiệp vụ thay client.

## Thành phần và kiểm chứng

- `AuthController`: validation và HTTP contract; `AuthService`: transaction login/refresh/logout, account/session checks.
- `TokenService`: sinh/ký JWT và tạo/hash refresh; `AuthSessionRepository`: khóa ghi khi thay đổi phiên.
- `AuthConfiguration` / `SecurityConfiguration`: xác thực JWT, bearer resolution và quyền endpoint.
- `AuthIntegrationTest`: HTTP thật với PostgreSQL tạm; kiểm rotation, đồng thời, logout, account disabled và thời điểm hết hạn. `TokenServiceTest`: chữ ký/claims và mốc15phút.
- Schema giữ nguyên [V1](../../database/migrations/V1__create_accounts_and_sessions.sql); không cần migration cho các subtask này.

Cấu hình bearer resolver theo [Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html). Không triển khai lại cơ chế JWT hoặc thuật toán ký.
