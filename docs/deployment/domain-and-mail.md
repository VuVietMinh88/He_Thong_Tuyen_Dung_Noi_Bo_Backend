# Tên miền internal-hire.com và email support@internal-hire.com

Dự án dùng tên miền `internal-hire.com`. Email gửi đi của hệ thống (mời kích hoạt tài khoản, đặt lại mật khẩu) dùng hộp thư `support@internal-hire.com` đặt tại ServerPoint.

| Thành phần | Địa chỉ |
|---|---|
| Frontend | `https://internal-hire.com` (và `https://www.internal-hire.com`) |
| API Backend | `https://api.internal-hire.com`, các API ở `https://api.internal-hire.com/api/v1` |
| Người gửi email | `support@internal-hire.com` |

Máy dev vẫn chạy như cũ: Backend `http://localhost:8080`, Frontend `http://localhost:5173`, email đi vào mail catcher ở `127.0.0.1:1025`. Từ nay địa chỉ người gửi mặc định là `support@internal-hire.com` thay cho `no-reply@ttcs.test`. Mail catcher local không gửi thư ra ngoài nên đổi địa chỉ này không làm thư thật bị gửi đi.

## 1. Cấu hình Backend trên máy chủ

Đặt các giá trị dưới đây trong `.env` riêng của máy chủ (cạnh `pom.xml`, Git đã bỏ qua file này). Khối mẫu nằm ở cuối `.env.example`, đang để dạng chú thích.

| Biến | Giá trị | Ghi chú |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `https://internal-hire.com,https://www.internal-hire.com` | Chỉ các origin Frontend được gọi API từ trình duyệt |
| `RESET_PASSWORD_PAGE_URL` | `https://internal-hire.com/reset-password` | Link trong email đặt lại mật khẩu |
| `ACCOUNT_ACTIVATION_PAGE_URL` | `https://internal-hire.com/activate-account` | Link trong email mời kích hoạt |
| `MAIL_HOST` | `mail.internal-hire.com` | **Đối chiếu trong trang quản trị ServerPoint** (mục cấu hình mail client của hộp thư) |
| `MAIL_PORT` | `587` | Hoặc `465`, xem bảng bên dưới |
| `MAIL_SMTP_AUTH` | `true` | ServerPoint yêu cầu đăng nhập khi gửi |
| `MAIL_SMTP_STARTTLS` / `MAIL_SMTP_SSL` | `true` / `false` | Với cổng 587 |
| `MAIL_USERNAME` | `support@internal-hire.com` | Thường là địa chỉ email đầy đủ |
| `MAIL_PASSWORD` | mật khẩu hộp thư | **Chỉ ghi trong `.env` của máy chủ**, không gửi qua chat, không commit |
| `MAIL_FROM` | `support@internal-hire.com` | Phải là hộp thư mà tài khoản SMTP được phép gửi thay |

Chọn một trong hai cách kết nối SMTP, theo thông số ServerPoint cung cấp cho hộp thư:

| Cổng | `MAIL_SMTP_STARTTLS` | `MAIL_SMTP_SSL` | Kiểu mã hóa |
|---|---|---|---|
| 587 | `true` | `false` | Kết nối thường rồi nâng cấp lên TLS (STARTTLS) |
| 465 | `false` | `true` | Mã hóa TLS ngay từ đầu (SSL/TLS) |

Không bật cả hai cùng lúc. Tên máy chủ trong `MAIL_HOST` phải khớp chứng chỉ TLS của ServerPoint. Nếu trang quản trị đưa một tên máy chủ khác (ví dụ tên server của nhà cung cấp) thì dùng đúng tên đó, đừng tắt kiểm tra chứng chỉ.

Backend kiểm tra hai URL trang Frontend khi khởi động: phải là HTTPS cố định (chỉ `localhost` được dùng HTTP), không chứa thông tin đăng nhập, query hay fragment. Sai định dạng thì ứng dụng dừng ngay lúc chạy, để không gửi link hỏng cho người dùng.

Backend lắng nghe ở `127.0.0.1:8080` (`server.address` trong `application.properties`). Để phục vụ `https://api.internal-hire.com`, đặt một reverse proxy có HTTPS (ví dụ Nginx hoặc Caddy) trên cùng máy, chuyển tiếp tới `http://127.0.0.1:8080`.

## 2. Cấu hình Frontend

Frontend nằm ở repo riêng. Khi build bản chạy thật, đặt `VITE_API_BASE_URL=https://api.internal-hire.com/api/v1` rồi build lại. Biến `VITE_*` được nhúng công khai vào trình duyệt; không đặt bí mật nào ở đây. Việc này thuộc repo Frontend, không nằm trong repo Backend.

## 3. DNS cho tên miền

Khai báo tại nơi quản lý DNS của `internal-hire.com`. Giá trị cụ thể (địa chỉ IP, bản ghi MX, chuỗi SPF/DKIM) lấy từ ServerPoint và từ nơi đặt máy chủ ứng dụng; tài liệu này không tự đặt ra các giá trị đó.

| Bản ghi | Tên | Mục đích |
|---|---|---|
| A/AAAA hoặc CNAME | `internal-hire.com`, `www` | Trỏ tới nơi chạy Frontend |
| A/AAAA hoặc CNAME | `api` | Trỏ tới máy chạy reverse proxy của Backend |
| MX | `internal-hire.com` | Theo ServerPoint, để hộp thư `support@` nhận thư |
| TXT (SPF) | `internal-hire.com` | Cho phép máy chủ mail ServerPoint gửi thay tên miền |
| TXT (DKIM) | theo ServerPoint | Ký thư; bật DKIM trong trang quản trị ServerPoint |
| TXT (DMARC) | `_dmarc` | Ví dụ bắt đầu bằng `v=DMARC1; p=none; rua=mailto:support@internal-hire.com`, sau khi ổn định có thể chuyển `quarantine` |

Thiếu SPF/DKIM, email mời và đặt lại mật khẩu dễ vào mục Spam hoặc bị từ chối.

## 4. Kiểm tra sau khi cấu hình

1. Khởi động Backend với `.env` mới; xem log không có lỗi cấu hình URL hay SMTP.
2. Trên Frontend thật, dùng "Quên mật khẩu" với một tài khoản có email thật mà bạn kiểm soát. API luôn trả thông báo chung, nên phải xem hộp thư để biết thư đã đến.
3. Mở thư: người gửi là `support@internal-hire.com`, link bắt đầu bằng `https://internal-hire.com/reset-password?token=`. Xem tiêu đề thư (Show original) để chắc SPF và DKIM đều `PASS`.
4. Nếu không nhận được thư: kiểm tra mục Spam, log Backend (chỉ ghi loại lỗi SMTP, không ghi mật khẩu), host/cổng/kiểu mã hóa và mật khẩu trong `.env`.

## 5. Bảo mật

- Không commit `.env`, không gửi mật khẩu hộp thư qua chat hay đặt trong tài liệu. Nếu lỡ để lộ, đổi mật khẩu trong ServerPoint ngay.
- Máy dev và test tự động không dùng SMTP thật; test dùng máy chủ SMTP giả trên `127.0.0.1`.
- Cấu hình Spring Mail theo [tài liệu Spring Boot về email](https://docs.spring.io/spring-boot/reference/io/email.html); các thuộc tính `mail.smtp.*` theo [Jakarta Mail](https://jakarta.ee/specifications/mail/).
