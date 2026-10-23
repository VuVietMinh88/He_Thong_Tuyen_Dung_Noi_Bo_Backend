# API đặt lại mật khẩu — TKNHTTDNB1-106–110

Story TKNHTTDNB1-12: gửi liên kết qua email, hiệu lực **30 phút**, **dùng một lần**, cùng thông báo cho email có thật và không có thật. API thuộc backend Java; frontend dùng trang `/reset-password?token=...`.

Tài khoản bị [Admin khóa](account-locking.md) không được gửi email reset hoặc dùng link để vượt khóa. Endpoint forgot vẫn trả thông báo202 chung; khóa vô hiệu hóa các reset link chưa dùng. Sau khi mở khóa, cần yêu cầu link mới nếu quên mật khẩu; link cũ không hồi phục.

## 1. Yêu cầu liên kết

`POST /api/v1/auth/forgot-password`, `Content-Type: application/json`, `X-Device-Id: <UUID của trình duyệt>`:

```json
{"email":"admin@example.com"}
```

Email chỉ là ví dụ, cần dùng tài khoản đã có trong ứng dụng. Bỏ khoảng trắng hai đầu và chuyển chữ thường; kiểm email hợp lệ, tối đa 254 ký tự.

`X-Device-Id` là UUID ngẫu nhiên frontend tạo một lần và giữ trong `localStorage` của trình duyệt, dùng chung giá trị khi đăng nhập và yêu cầu quên mật khẩu. Hai chức năng có bộ đếm riêng (`login_devices` và `password_reset_devices`). Header bắt buộc; backend chỉ nhận dạng chuẩn 8-4-4-4-12 chữ số hex, không phân biệt hoa thường (cùng UUID viết hoa hay viết thường là một trình duyệt), không nhận dạng rút gọn, không dấu gạch hay có ngoặc.

Trả **202 Accepted**, `Cache-Control: no-store`:

```json
{"message":"Nếu email thuộc tài khoản đang hoạt động, bạn sẽ nhận được liên kết đặt lại mật khẩu."}
```

202 nghĩa là yêu cầu đã được xếp hàng xử lý. Không xác nhận email tồn tại hoặc thư đã tới inbox. Tài khoản vô hiệu hóa, email lạ và yêu cầu lặp trong60 giây đều có cùng response. Không trả token, địa chỉ email hay thời gian hết hạn trong response.

Thiếu/sai email hoặc JSON trả 400 theo `VALIDATION_ERROR`/`INVALID_JSON` hiện có. Khi hàng đợi đầy trả 503 với `code=PASSWORD_RESET_BUSY`; thử lại sau. Hàng đợi tối đa100 yêu cầu, một worker, chung cho mọi email. Thời gian đợi SMTP không nằm trên luồng trả HTTP.

### Giới hạn theo trình duyệt

Server đếm **mọi** yêu cầu hợp lệ của một trình duyệt (theo `X-Device-Id`), dù email có thuộc tài khoản hay không:

- Yêu cầu thứ 1 đến 5 trong 15 phút (tính từ yêu cầu đầu tiên được đếm) nhận 202 như trên.
- Từ yêu cầu thứ 6 trả **429** cho tới đúng 15 phút sau yêu cầu thứ 5. Yêu cầu gửi trong lúc bị chặn không được đếm và không kéo dài thời gian chặn. Hết thời gian chặn, yêu cầu kế tiếp mở một cửa sổ 15 phút mới.
- Chưa tới 5 yêu cầu mà 15 phút của cửa sổ đã qua thì yêu cầu kế tiếp cũng mở cửa sổ mới, đếm lại từ 1.
- Email có thật, email lạ và tài khoản vô hiệu hóa hoặc bị khóa được đếm như nhau và nhận cùng response (cả 202 lẫn 429), nên giới hạn này không tiết lộ email nào có tài khoản. Cooldown 60 giây theo tài khoản vẫn áp dụng riêng.
- Chặn đăng nhập và chặn quên mật khẩu độc lập: trình duyệt bị chặn gửi yêu cầu vẫn đăng nhập được và vẫn dùng được liên kết đã nhận.

Trả **429 Too Many Requests**, `Cache-Control: no-store`, `Retry-After: <số giây còn phải chờ>` (số nguyên, làm tròn lên, tối thiểu 1; ngay sau yêu cầu thứ 5 là `900`):

```json
{"code":"PASSWORD_RESET_DEVICE_LOCKED","message":"Trình duyệt này đã gửi quá nhiều yêu cầu đặt lại mật khẩu. Vui lòng thử lại sau.","fieldErrors":{}}
```

Thiếu `X-Device-Id` hoặc giá trị không phải UUID hợp lệ trả **400**, `Cache-Control: no-store`; yêu cầu không được đếm, không xếp hàng gửi mail:

```json
{"code":"PASSWORD_RESET_DEVICE_REQUIRED","message":"Vui lòng cung cấp X-Device-Id là UUID hợp lệ của trình duyệt.","fieldErrors":{}}
```

Thứ tự kiểm tra:

1. Body sai hoặc JSON hỏng: 400 `VALIDATION_ERROR`/`INVALID_JSON`, không đếm.
2. Thiếu hoặc sai `X-Device-Id`: 400 `PASSWORD_RESET_DEVICE_REQUIRED`, không đếm.
3. Trình duyệt đang bị chặn: 429 `PASSWORD_RESET_DEVICE_LOCKED`.
4. Hàng đợi đầy: 503 `PASSWORD_RESET_BUSY`; yêu cầu đã được đếm.
5. 202 như trên; yêu cầu được đếm.

CORS cho frontend khác origin gửi header `X-Device-Id` và cho script đọc `Retry-After` (cùng với `Content-Disposition` đã có).

Giới hạn chỉ chặn một trình duyệt: đổi trình duyệt, mở cửa sổ ẩn danh hoặc xóa dữ liệu trang web sẽ có UUID mới. Đây là hạn chế đã biết; chưa có giới hạn theo IP. Bộ đếm nằm trong PostgreSQL (bảng `password_reset_devices`, V39 trong lịch sử feedback; bridge V45 trong lịch sử S5) nên khởi động lại backend không xóa thời gian chặn.

Cửa sổ 15 phút là cửa sổ cố định bắt đầu ở yêu cầu đầu tiên được đếm, không phải cửa sổ trượt. Ở ranh giới hai cửa sổ, một trình duyệt vì vậy có thể được nhận tới 9 yêu cầu trong 15 phút liền nhau: yêu cầu mở cửa sổ cũ, 3 yêu cầu ngay trước khi cửa sổ cũ hết và 5 yêu cầu đầu cửa sổ mới, 8 yêu cầu sau cùng chỉ cách nhau khoảng một giây. Giới hạn chặt "tối đa 5 yêu cầu trong 15 phút bất kỳ" cần lưu thời điểm của 5 yêu cầu gần nhất; chưa làm.

Mỗi `X-Device-Id` mới tạo một dòng trong `password_reset_devices` và chưa có job dọn các dòng cũ. Script đổi UUID ở mỗi yêu cầu vừa không bị chặn vừa làm bảng lớn dần, nên job dọn dòng cũ (xóa dòng không được cập nhật quá 1 ngày và không còn bị chặn) là việc bắt buộc, phải phát hành trước hoặc cùng bản này khi lên production.

**Triển khai:** frontend và backend phải phát hành cùng lúc. Từ bản này backend bắt buộc `X-Device-Id`; frontend chưa gửi header sẽ nhận 400 ở mọi yêu cầu quên mật khẩu. Bản backend thống nhất giữ V39 gốc cho lịch sử feedback và bổ sung cùng SQL bằng V45 cho lịch sử S5. Chọn `APP_MIGRATION_LINEAGE` theo V26/V27 đã ghi trong database; database mới mặc định `s5`. Không đổi lineage sau V26. Các chức năng còn thiếu của mỗi lịch sử đã có bridge riêng theo đúng phụ thuộc; xem [bảng đối chiếu và cách nâng cấp](../database/migration-lineages.md). Trước khi triển khai lên database có dữ liệu, sao lưu và thử nâng cấp bản sao của chính database đó. Bản local chưa kiểm tra database VPS. Giữ validation bật, out-of-order và baseline tắt; sai lịch sử/checksum phải dừng để đối chiếu, không sửa history hoặc xóa database để bỏ qua lỗi.

## 2. Cập nhật mật khẩu

`POST /api/v1/auth/reset-password`:

```json
{"token":"<43 ký tự lấy từ liên kết email>","newPassword":"<mật khẩu mới>"}
```

Mật khẩu dùng quy tắc đang có ở bootstrap: ít nhất8 ký tự, có chữ và số, tối đa 72 byte UTF-8 để phù hợp BCrypt. Giữ nguyên khoảng trắng và hoa/thường của mật khẩu. Frontend xác nhận nhập lại mật khẩu trước khi gửi.

Trả **200**, `Cache-Control: no-store`:

```json
{"message":"Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới."}
```

Một transaction đổi BCrypt hash, xóa hai trường bộ đếm/khóa cũ trên tài khoản, vô hiệu hóa mọi reset token còn lại và thu hồi mọi phiên của đúng tài khoản đó. Thời gian chặn trình duyệt trong `login_devices` vẫn giữ nguyên; có thể đăng nhập từ trình duyệt khác hoặc chờ hết hạn. Các tài khoản khác không bị ảnh hưởng. Response không tự cấp access/refresh token: client quay về đăng nhập.

Token sai, đã dùng, hết hạn hoặc tài khoản bị vô hiệu hóa trả **400**:

```json
{"code":"RESET_TOKEN_INVALID","message":"Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu liên kết mới.","fieldErrors":{}}
```

Sai định dạng token hoặc mật khẩu trả 400 `VALIDATION_ERROR` trước khi dùng token. Link cấp lúc10:00:00 bị từ chối từ10:30:00. Yêu cầu mới không kéo dài hạn của link cũ. Các link cũ vẫn có thể dùng đến hạn, nhưng một lần đặt lại thành công vô hiệu hóa toàn bộ link của tài khoản.

Cả hai endpoint công khai, không cần Bearer. Nếu client còn gửi Bearer cũ, backend bỏ qua header đó tại đúng hai endpoint này; mật khẩu mới vẫn chỉ được lưu khi reset token trong body hợp lệ. Các API cần xác thực vẫn giữ kiểm Bearer.

## Nội dung email

Tiêu đề `Đặt lại mật khẩu — Hệ thống tuyển dụng nội bộ`, người gửi `MAIL_FROM`, `Reply-To` là email hỗ trợ `MAIL_SUPPORT_EMAIL`. Thư có hai phần cùng nội dung: `text/plain` UTF-8 trước, `text/html` UTF-8 sau (multipart/alternative, nằm trong multipart/related, ngoài cùng là multipart/mixed); trình đọc thư hiển thị phần HTML nếu đọc được, nếu không thì phần văn bản.

Phần văn bản giữ nguyên các dòng trước đây, sau đó là dòng phân cách chữ ký `-- ` (hai gạch và một dấu cách) và footer hỗ trợ:

```text
Bạn đã yêu cầu đặt lại mật khẩu.

Mở liên kết sau trong vòng 30 phút; liên kết chỉ dùng được một lần:
https://internal-hire.com/reset-password?token=<43 ký tự>

Nếu bạn không yêu cầu, hãy bỏ qua email này.

--
Cần hỗ trợ? Liên hệ Bộ phận Nhân sự qua email support@internal-hire.com.
Email này được gửi tự động từ Hệ thống tuyển dụng nội bộ.
© 2026 Hệ thống tuyển dụng nội bộ
```

Phần HTML có cùng các câu, liên kết là nút "Đặt lại mật khẩu" kèm URL đầy đủ bên dưới (cho trình đọc thư không mở được nút), footer có liên kết `mailto:` tới email hỗ trợ, và logo inline nếu đã đặt `MAIL_LOGO`. Mọi giá trị chèn vào HTML đều được escape. Token chỉ nằm trong liên kết; email không chứa mật khẩu hay hash. Tên công ty, nhóm hỗ trợ, hotline và logo cấu hình bằng biến môi trường, xem [Footer email](../deployment/domain-and-mail.md#5-footer-email).

## SMTP local và cấu hình

Mặc định gửi tới SMTP tại 127.0.0.1:1025. Không cần sửa `.env` cũ để chạy với mail catcher ở cổng này. Cấu hình mẫu bổ sung trong `.env.example`; không sao chép đè toàn bộ `.env` đang dùng.

Nếu có Docker, tại root dự án:

```powershell
docker compose -f devops/docker/compose.mail.yaml up -d
```

Mở [Mailpit local](http://localhost:8025) để đọc thư. Compose mail tách riêng PostgreSQL, chỉ bind loopback. Mailpit là hộp thư thử, không chuyển thư ra email thật. Cách cài Windows và các tùy chọn nằm trong [tài liệu Mailpit](https://mailpit.axllent.org/docs/install/).

| Biến .env | Ý nghĩa/mặc định |
|---|---|
| `MAIL_HOST`, `MAIL_PORT` | SMTP server, mặc định 127.0.0.1/1025 |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | Thông tin SMTP, local để trống |
| `MAIL_SMTP_AUTH` | Xác thực SMTP, local false |
| `MAIL_SMTP_STARTTLS` | Bật và bắt buộc STARTTLS (cổng 587), local false |
| `MAIL_SMTP_SSL` | Bật TLS ngay từ đầu (cổng 465), local false; không bật cùng STARTTLS |
| `MAIL_FROM` | Người gửi, mặc định support@internal-hire.com |
| `RESET_PASSWORD_PAGE_URL` | URL frontend cố định; mặc định http://localhost:5173/reset-password |
| `MAIL_COMPANY_NAME`, `MAIL_SUPPORT_TEAM`, `MAIL_SUPPORT_EMAIL`, `MAIL_SUPPORT_PHONE`, `MAIL_LOGO` | Footer, Reply-To và logo của email; mặc định Hệ thống tuyển dụng nội bộ / Bộ phận Nhân sự / support@internal-hire.com / không hotline / không logo ([Footer email](../deployment/domain-and-mail.md#5-footer-email)) |

Với SMTP thật dùng cấu hình được nhà cung cấp cấp, thường 587 + AUTH/STARTTLS hoặc 465 + SSL; giữ bí mật trong `.env`. Cấu hình cho tên miền dự án và hộp thư support@internal-hire.com (ServerPoint) xem [tên miền và email](../deployment/domain-and-mail.md). URL frontend phải HTTPS, chỉ cho HTTP với localhost. Không dùng URL do request gửi lên để tạo link. Timeout kết nối/đọc/ghi SMTP là5 giây. Spring Mail được cấu hình theo [tài liệu Spring Boot](https://docs.spring.io/spring-boot/reference/io/email.html).

Nếu SMTP lỗi, token mới bị rollback và API vẫn trả thông báo chung; backend chỉ log loại lỗi. Người dùng có thể yêu cầu lại. Hàng đợi nằm trong bộ nhớ, chưa có retry bền vững qua restart. SMTP và DB không commit chung: hiếm khi thư đã được SMTP nhận nhưng DB commit thất bại thì link không dùng được, cần yêu cầu lại.

## Nối với frontend

- `/auth/forgot-password` trả một trường `message`, tương thích kiểu ApiMessageResponse đang có.
- Gửi `X-Device-Id` (UUID trình duyệt trong `localStorage`) ở mọi yêu cầu quên mật khẩu. Khi nhận 429 `PASSWORD_RESET_DEVICE_LOCKED`, hiển thị thời gian chờ đếm ngược theo `Retry-After`; bỏ bộ đếm 5 lần tự giữ ở `localStorage`, vì server đã chặn và bộ đếm phía trình duyệt bị bỏ qua khi xóa dữ liệu. Không hiển thị câu khẳng định email không tồn tại.
- Sau 202, hiển thị thông báo chung và hướng dẫn xem email. Không chuyển sang form đặt mật khẩu chỉ từ email.
- Đọc `token` ở query URL email, gửi cùng `newPassword` tới API trên; bỏ giả lập thành công và bỏ log payload chứa mật khẩu/token.
- Đồng bộ validation frontend về8 ký tự/chữ/số/giới hạn72 byte; màn hình hiện kiểm6 ký tự.
- Sau200 xóa token phiên cũ phía client và về trang đăng nhập. Đặt `Referrer-Policy: no-referrer` trên trang reset; tránh analytics/log ghi URL chứa token.

Những thay đổi giao diện này thuộc frontend, chưa thực hiện trong các subtask BE106–110.
