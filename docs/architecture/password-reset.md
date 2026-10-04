# Thiết kế đặt lại mật khẩu

Luồng phục vụ story TKNHTTDNB1-12 và subtasks106–110. Tách trong package `vn.ttcs.recruitment.auth.passwordreset`, tái sử dụng Account, BCrypt, Clock và AuthSessionRepository.

| Subtask | Thành phần | Trách nhiệm |
|---|---|---|
| 106 | PasswordResetController, ForgotPasswordRequest, PasswordResetDispatcher | Nhận email, validation, response chung, hàng đợi giới hạn |
| 107 | ResetTokenGenerator | SecureRandom32 byte, Base64URL dài 43 ký tự; SHA-256 để tra DB |
| 108 | PasswordResetToken, Repository, migrationV2 | user_id, hash, created_at/expires_at/used_at, hết hạn30 phút và dùng một lần |
| 109 | PasswordResetMailSender, PasswordResetService.sendResetEmail, cấu hình SMTP | Email UTF-8 có URL frontend cố định, timeout, rollback khi SMTP lỗi |
| 110 | ResetPasswordRequest, PasswordResetService.resetPassword | BCrypt mới, thu hồi link/phiên trong một transaction |

```text
POST forgot-password → validation → queue → 202 chung
    Worker: khóa tài khoản → kiểm enabled/cooldown → tạo token + lưu hash
            → gửi SMTP → commit

POST reset-password → validation → tra hash → khóa tài khoản
    → UPDATE token WHERE used_at IS NULL AND expires_at > now
    → BCrypt mới + xóa khóa đăng nhập
    → vô hiệu hóa tất cả reset token + thu hồi phiên → commit → 200
```

Định dạng token opaque được chọn để không đưa thông tin tài khoản vào URL và dễ thu hồi trong DB. Reset token khác access/refresh token; không được dùng thay nhau. Hướng chọn token ngẫu nhiên, lưu an toàn, một lần dùng, thông báo chung và thu hồi phiên phù hợp [OWASP Forgot Password](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

## Đồng thời và tính toàn vẹn

Request gửi mail và login cùng khóa account. Hai yêu cầu email cùng tài khoản không vượt cooldown60 giây. Một request mới không thu hồi link cũ để tránh người khác liên tục yêu cầu khiến link trong inbox mất tác dụng.

Reset đọc user_id từ token, sau đó khóa account trước khi consume bằng UPDATE có điều kiện. Nếu hai reset cùng token hoặc hai token của một tài khoản chạy song song, chỉ một lần thành công. Không dùng trạng thái token đã đọc trước lúc lấy khóa để quyết định consume.

Login khóa account trước tạo phiên; reset cùng khóa và thu hồi phiên sau khi đổi mật khẩu. Refresh/logout khóa session và chỉ đọc account nên không tạo vòng khóa ghi ngược. Refresh chạy trước reset có thể trả 200, nhưng token/phiên đó bị thu hồi khi reset commit. Các thao tác trong reset được rollback cùng nhau khi transaction thất bại.

## Chính sách và giới hạn

- Jira quy định30 phút/1lần/thông báo chung. Cooldown60 giây, hàng đợi 100, một worker và thu hồi mọi phiên là lựa chọn triển khai được ghi rõ, không gán thành AC mới của Jira.
- Chỉ gửi mail cho account enabled; account tạm khóa vì nhập sai vẫn nhận được link. Chỉ xóa khóa sau khi token hợp lệ được dùng.
- Worker chạy trong tiến trình backend, không lưu job/secret vào queue DB. Job chưa chạy có thể mất khi tiến trình dừng đột ngột. Chưa có outbox/retry bảo đảm gửi email.
- Bản ghi đã dùng/hết hạn được giữ để kiểm tra lịch sử và cooldown; chưa có job dọn tự động.
- SMTP acceptance không bảo đảm thư đã vào inbox; thông báo202 không tiết lộ trạng thái gửi hay tài khoản.
- MigrationV2 chỉ thêm bảng/index, không sửa bảng tài khoản/phiên hoặc dữ liệuV1. Khi nâng cấp DB có dữ liệu, sao lưu trước rồi cho Flyway chạyV2. Hoàn tác ứng dụng có thể để bảngV2 chưa sử dụng; không sửa/xóa migration đã áp dụng.

Xem [hợp đồng API và hướng dẫn SMTP](../api/password-reset.md). Test tự động dùng PostgreSQL tạm, SMTP giả lập loopback, không chạm DB/.env/mailbox thật.
