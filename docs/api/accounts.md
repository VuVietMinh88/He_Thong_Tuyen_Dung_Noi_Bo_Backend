# Tạo và kích hoạt tài khoản nội bộ

Jira TKNHTTDNB1-145 và149, tiêu chí tạo tài khoản của story17. Backend dùng `/api/v1`; chỉ `ADMIN` có quyền `USER_ADMIN_WRITE_ALL` được tạo tài khoản. Quyền kiểm lại trên server ở mỗi yêu cầu. API sửa, tìm kiếm, phân trang và quản lý vai trò riêng thuộc các task khác.

## POST /accounts

Gửi `Authorization: Bearer <accessToken>` của Admin và JSON:

```json
{
  "email": "interviewer@example.com",
  "fullName": "Nguyễn Văn An",
  "roles": ["INTERVIEWER", "HIRING_MANAGER"]
}
```

Email được trim/chuyển chữ thường, tối đa254 ký tự; họ tên không trống/tối đa255 ký tự; ít nhất một trong sáu vai trò nội bộ. Không nhận mật khẩu từ Admin. Server tạo mật khẩu tạm ngẫu nhiên24 ký tự bằng SecureRandom và lưu BCrypt. Candidate không phải tài khoản nội bộ.

Thành công **201**, `Cache-Control: no-store`:

```json
{
  "id": "00000000-0000-0000-0000-000000000001",
  "email": "interviewer@example.com",
  "fullName": "Nguyễn Văn An",
  "roles": ["INTERVIEWER", "HIRING_MANAGER"],
  "status": "PENDING_ACTIVATION"
}
```

Tài khoản chưa được đăng nhập cho đến khi kích hoạt. SMTP gửi email UTF8 gồm liên kết kích hoạt và mật khẩu tạm. Response không trả mật khẩu, token hoặc hash. Email trùng, kể cả khác chữ hoa/khoảng trắng hay hai Admin tạo đồng thời, trả **409 `EMAIL_ALREADY_EXISTS`** với thông báo tiếng Việt và không ghi đè tài khoản/gửi email lần hai. DB unique constraint xử lý trường hợp cạnh tranh sau precheck.

Các lỗi: **400** dữ liệu sai/role không hợp lệ; **401** thiếu/hết phiên; **403** thiếu quyền Admin; **503 `ACCOUNT_EMAIL_UNAVAILABLE`** gửi email thất bại. Khi503, transaction rollback tài khoản/vai trò/token để có thể thử tạo lại.

## POST /auth/activate-account

Trang frontend lấy `token` từ liên kết rồi gửi POST khi người dùng xác nhận:

```json
{"token": "<token-trong-email>"}
```

API này không cần Bearer. Thành công **200** với thông báo kích hoạt, không cấp phiên. Sau đó đăng nhập bằng email/mật khẩu tạm, dùng API đổi mật khẩu hiện có. GET liên kết không tự kích hoạt để tránh trình quét email tiêu thụ token. Trang frontend `/activate-account` chưa được triển khai trong nhóm BE này.

Token32byte ngẫu nhiên, DB chỉ lưu SHA256; dùng đúng một lần. Lỗi **400 `ACTIVATION_TOKEN_INVALID`** khi không tồn tại, đã dùng hoặc hết hạn. Token reset/refresh không dùng để kích hoạt; token kích hoạt không dùng để reset mật khẩu. Hai yêu cầu kích hoạt đồng thời chỉ một yêu cầu thành công.

## SMTP và thời hạn

Dùng cấu hình SMTP hiện có trong [đặt lại mật khẩu](password-reset.md), mặc định mail catcher `127.0.0.1:1025`, `MAIL_FROM` dùng chung. Các cấu hình mới có mặc định nên không cần thay private `.env`:

```properties
ACCOUNT_ACTIVATION_PAGE_URL=http://localhost:5173/activate-account
ACCOUNT_ACTIVATION_TTL=24h
```

URL cố định do server cấu hình, HTTPS ngoại trừ localhost; không có credentials/query/fragment. Không lấy Host header từ request.24h là chính sách khởi tạo, Jira chưa quy định; cấu hình cho phép1h–7d. Tại đúng thời điểm hết hạn token bị từ chối. Chưa có API gửi lại email kích hoạt; hết hạn cần xử lý quản trị ở bước tiếp theo.

V4 thêm `account_activation_tokens`, giữ nguyênV1/V2/V3 và dữ liệu tài khoản/phiên hiện tại. Sao lưu DB trước khi nâng cấp. SMTP và commitSQL không phải một transaction phân tán: nếu SMTP đã nhận nhưng DB commit sau đó thất bại thì email có thể chứa liên kết không dùng được. SMTP gửi đồng bộ, timeout hiện có5s; chưa có hàng đợi bền vững/retry tự động. Khi bổ sung API khóa tài khoản, cần vô hiệu hóa cả liên kết kích hoạt còn hiệu lực để khóa không bị đảo ngược bởi kích hoạt.
