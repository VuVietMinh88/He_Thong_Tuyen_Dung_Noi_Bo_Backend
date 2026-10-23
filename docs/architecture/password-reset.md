# Thiết kế đặt lại mật khẩu

Luồng phục vụ story TKNHTTDNB1-12 và subtasks106–110. Tách trong package `vn.ttcs.recruitment.auth.passwordreset`, tái sử dụng Account, BCrypt, Clock và AuthSessionRepository.

| Subtask | Thành phần | Trách nhiệm |
|---|---|---|
| 106 | PasswordResetController, ForgotPasswordRequest, PasswordResetDispatcher | Nhận email, validation, response chung, hàng đợi giới hạn |
| 107 | ResetTokenGenerator | SecureRandom32 byte, Base64URL dài 43 ký tự; SHA-256 để tra DB |
| 108 | PasswordResetToken, Repository, migrationV2 | user_id, hash, created_at/expires_at/used_at, hết hạn30 phút và dùng một lần |
| 109 | PasswordResetMailSender, PasswordResetService.sendResetEmail, cấu hình SMTP | Email UTF-8 có URL frontend cố định, timeout, rollback khi SMTP lỗi |
| 110 | ResetPasswordRequest, PasswordResetService.resetPassword | BCrypt mới, thu hồi link/phiên trong một transaction |
| Góp ý 7 | BrowserDeviceId, PasswordResetThrottle, PasswordResetDevice, PasswordResetDeviceRepository, migration V39 feedback / bridge V45 S5 | Mỗi trình duyệt (`X-Device-Id`) 5 yêu cầu trong một cửa sổ 15 phút tính từ yêu cầu đầu tiên, sau đó 429 kèm `Retry-After` |

```text
POST forgot-password → validation → X-Device-Id → giới hạn theo trình duyệt (khóa dòng, đếm, commit)
    → queue → 202 chung
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

Giới hạn theo trình duyệt chạy trong transaction riêng của `PasswordResetThrottle.admit`, trước khi xếp hàng: `INSERT ... ON CONFLICT (id) DO NOTHING` tạo dòng của trình duyệt mới, rồi `SELECT ... FOR UPDATE` khóa dòng đó. Các yêu cầu song song của cùng một trình duyệt vì vậy được đếm lần lượt: mười yêu cầu cùng lúc cho đúng năm 202 và năm 429. Transaction commit trước khi email được xếp hàng và không khóa tài khoản nào, nên không tạo vòng khóa với worker gửi mail hay login. Yêu cầu bị chặn chỉ đọc dòng, không ghi gì.

Login khóa account trước tạo phiên; reset cùng khóa và thu hồi phiên sau khi đổi mật khẩu. Refresh/logout khóa session và chỉ đọc account nên không tạo vòng khóa ghi ngược. Refresh chạy trước reset có thể trả 200, nhưng token/phiên đó bị thu hồi khi reset commit. Các thao tác trong reset được rollback cùng nhau khi transaction thất bại.

## Chính sách và giới hạn

- Jira quy định30 phút/1lần/thông báo chung. Cooldown60 giây, hàng đợi 100, một worker và thu hồi mọi phiên là lựa chọn triển khai được ghi rõ, không gán thành AC mới của Jira.
- Chỉ gửi mail cho account enabled; account tạm khóa vì nhập sai vẫn nhận được link. Chỉ xóa khóa sau khi token hợp lệ được dùng.
- Worker chạy trong tiến trình backend, không lưu job/secret vào queue DB. Job chưa chạy có thể mất khi tiến trình dừng đột ngột. Chưa có outbox/retry bảo đảm gửi email.
- Bản ghi đã dùng/hết hạn được giữ để kiểm tra lịch sử và cooldown; chưa có job dọn tự động.
- Góp ý 7 (quên mật khẩu quá 5 lần thì chặn): mỗi trình duyệt được 5 yêu cầu hợp lệ trong cửa sổ 15 phút tính từ yêu cầu đầu tiên được đếm (cửa sổ cố định, không trượt: ở ranh giới hai cửa sổ có thể nhận tới 9 yêu cầu trong 15 phút liền nhau, xem API); yêu cầu thứ 5 chặn trình duyệt tới đúng 15 phút sau nó, yêu cầu trong lúc bị chặn nhận 429 `PASSWORD_RESET_DEVICE_LOCKED` kèm `Retry-After` và không kéo dài thời gian chặn. Thời gian lấy từ bean `Clock`. Server đếm mọi yêu cầu hợp lệ, không xem email có tài khoản hay không, nên response giống nhau cho email có thật và email lạ (giữ yêu cầu "cùng thông báo" của TKNHTTDNB1-12); "nhập sai" vì vậy được hiểu là mọi lần gửi. Body sai bị validation từ chối trước nên không được đếm; thiếu hoặc sai `X-Device-Id` trả 400 `PASSWORD_RESET_DEVICE_REQUIRED`, cũng không đếm.
- Bảng `password_reset_devices` (V39 của lịch sử feedback; bridge V45 của lịch sử S5) chỉ lưu UUID trình duyệt, số yêu cầu, đầu cửa sổ, thời điểm hết chặn và thời điểm cập nhật; không lưu email, tài khoản hay IP. Bộ đếm này tách khỏi khóa đăng nhập sai. Đổi trình duyệt hoặc xóa dữ liệu trang web là có UUID mới, nên đây chỉ là lớp chặn theo trình duyệt; giới hạn theo IP để làm sau. Mỗi UUID mới tạo một dòng và chưa có job dọn, nên job dọn dòng cũ (index `password_reset_devices_updated_at_idx` đã có) là việc bắt buộc, phải phát hành trước hoặc cùng bản này khi lên production.
- Frontend và backend phải phát hành cùng lúc: backend bắt buộc `X-Device-Id`, frontend phải gửi header này và đọc `Retry-After` (CORS cho phép header và expose `Retry-After`).
- SMTP acceptance không bảo đảm thư đã vào inbox; thông báo202 không tiết lộ trạng thái gửi hay tài khoản.
- MigrationV2 chỉ thêm bảng/index, không sửa bảng tài khoản/phiên hoặc dữ liệuV1. Khi nâng cấp DB có dữ liệu, sao lưu trước rồi cho Flyway chạyV2. Hoàn tác ứng dụng có thể để bảngV2 chưa sử dụng; không sửa/xóa migration đã áp dụng.
- Bản backend thống nhất chọn đúng lịch sử bằng `APP_MIGRATION_LINEAGE=s5|feedback`, căn cứ V26/V27 đã áp dụng; database mới mặc định S5. V39 feedback được giữ nguyên, S5 nhận cùng chức năng bằng V45. Các bridge bổ sung chức năng còn thiếu theo đúng phụ thuộc và migration tương lai dùng chung từ V55; không đổi lineage sau V26, không sửa history/checksum hoặc bật out-of-order. Xem [bảng đối chiếu và quy trình thử nâng cấp trên bản sao](../database/migration-lineages.md).

Xem [hợp đồng API và hướng dẫn SMTP](../api/password-reset.md). Test tự động dùng PostgreSQL tạm, SMTP giả lập loopback, không chạm DB/.env/mailbox thật.
