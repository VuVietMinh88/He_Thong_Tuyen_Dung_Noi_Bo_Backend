# Hồ sơ cá nhân — TKNHTTDNB1-179–181

Hai API dưới `/api/v1/profile` lấy chủ tài khoản từ Access Token, không nhận ID người cần sửa. Dùng `Authorization: Bearer <accessToken>`. `GET /auth/me` cũ giữ nguyên để tương thích frontend đăng nhập.

## GET /api/v1/profile

Yêu cầu `SELF_PROFILE_READ`, trả **200**, `Cache-Control: no-store`:

```json
{
  "id": "00000000-0000-0000-0000-000000000001",
  "email": "interviewer@example.com",
  "fullName": "Nguyễn Văn An",
  "phone": "0912345678",
  "displayTitle": "Chuyên viên tuyển dụng",
  "departmentId": null,
  "departmentName": null,
  "roles": ["INTERVIEWER"],
  "hasAvatar": true,
  "avatarUpdatedAt": "2026-10-07T08:00:00Z"
}
```

`hasAvatar` và `avatarUpdatedAt` được thêm cùng [API ảnh đại diện](avatars.md) (TKNHTTDNB1-188); chưa có ảnh thì `hasAvatar` là false và `avatarUpdatedAt` là null. Ảnh không nằm trong JSON này mà đọc qua `GET /profile/avatar`. `PUT /profile` cũng trả hai trường này nhưng không nhận chúng trong body.

Email/phòng ban/vai trò chỉ để hiển thị. Tài khoản cũ có phone/displayTitle/departmentId lànull cho đến khi cập nhật. Không có password/hash/token trong response. Thêm `?userId=...` không đổi chủ hồ sơ; server vẫn dùng JWT. Không có route sửa `/profile/{id}`.

## PUT /api/v1/profile

Yêu cầu `SELF_PROFILE_WRITE`, được V5 cấp cho sáu vai trò nội bộ. JSON chỉ có:

```json
{
  "fullName": "Nguyễn Văn An",
  "phone": "+84912345678",
  "displayTitle": "Chuyên viên tuyển dụng"
}
```

- Họ tên bắt buộc, trim và tối đa255 ký tự.
- Điện thoại tùy chọn; chấp nhận di động10 số bắt đầu03/05/07/08/09 hoặc cố định11 số bắt đầu02, và dạng quốc tế tương ứng `+84`. Trim khoảng trắng đầu/cuối, lưu dạng0; không chấp nhận khoảng trắng/dấu gạch ở giữa. Đây là kiểm tra định dạng, không xác minh số đang được cấp hay quyền sở hữu. Quy tắc tham khảo thông báo của Bộ TT&TT qua [ITU về số di động](https://www.ituob.org/issues/1150-en/) và [mã vùng cố định](https://www.ituob.org/issues/1114-en/).
- Điện thoại không được trùng với tài khoản khác, ở bất kỳ trạng thái nào (đang hoạt động, chờ kích hoạt, bị khóa, vô hiệu hóa). So sánh sau khi chuẩn hóa, nên `+84912345678` trùng với `0912345678`. Giữ nguyên số của chính mình luôn hợp lệ; bỏ trống/xóa số không bao giờ trùng, vì nhiều tài khoản có thể cùng không có số điện thoại.
- Chức danh tùy chọn, trim và tối đa120 ký tự.
- PUT thay thế ba trường: bỏ phone/displayTitle hoặc gửinull/chuỗi trắng sẽ xóa chúng. Frontend gửi đầy đủ ba trường khi lưu form.

Thành công **200** trả hồ sơ đã cập nhật, no-store. Email, phòng ban, vai trò, mật khẩu và các phiên đăng nhập được giữ nguyên. Field ngoài ba trường trên (`email`, `departmentId`, `roles`, `userId`, `enabled`...) trả **400 `INVALID_JSON`**; dữ liệu sai trả **400 `VALIDATION_ERROR`**. Không nhận ID từ body để sửa tài khoản khác.

Số điện thoại đã thuộc tài khoản khác trả **409 `PHONE_ALREADY_EXISTS`**, `Cache-Control: no-store`, và không lưu trường nào:

```json
{
  "code": "PHONE_ALREADY_EXISTS",
  "message": "Số điện thoại đã được sử dụng cho một tài khoản khác.",
  "fieldErrors": {
    "phone": "Số điện thoại này đã được dùng cho một tài khoản khác. Vui lòng nhập số khác."
  }
}
```

Frontend hiện `fieldErrors.phone` ngay dưới ô số điện thoại. Message không nêu tên, email hay ID của tài khoản đang dùng số đó. Thứ tự kiểm tra: 401/403 của Spring Security, 400 `VALIDATION_ERROR`, server kiểm lại phiên (401) và quyền (403), rồi mới tới 409. Hai người lưu cùng một số cùng lúc thì đúng một người thành công, người còn lại nhận 409 này (không phải 500): ràng buộc unique của V38 quyết định khi cả hai cùng qua bước kiểm tra trước.

Lần lưu bị bước kiểm tra trước từ chối không ghi gì vào database và không đưa số điện thoại vào log. Chỉ khi hai lần lưu thật sự tranh nhau, PostgreSQL báo vi phạm `user_accounts_phone_key` và Hibernate ghi lỗi đó vào log ứng dụng ở mức WARN (logger `org.hibernate.orm.jdbc.error`), kèm dòng `Detail: Key (phone)=(...)` có số điện thoại, giống khi hai lần tạo cùng một email tranh nhau. Response vẫn là 409 ở trên, không chứa số.

Người dùng nhận 409 cho số của chính mình thì liên hệ Admin. `q` của [danh sách tài khoản](accounts.md) chưa tìm theo số điện thoại; Admin thấy số của từng tài khoản ở trường `phone` của danh sách.

Thiếu/hết hạn Access Token, phiên hết hạn/thu hồi hoặc tài khoản disabled trả **401**. Grant bị thu hồi trả **403** dù JWT còn hạn. Khi ghi, khóa tài khoản trước rồi phiên, kiểm lại chủ phiên/hiệu lực/quyền trước cập nhật. CORS PUT dùng danh sách origin hiện có.

## Dữ liệu và phạm vi

V5 bổ sung trường nullable và quyền mới, giữ mật khẩu/phiên/vai trò cũ. Backend khởi động sẽ áp dụng migration chưa chạy; sao lưu DB trước nâng cấp. Trong kiểm thử chỉ dùng PostgreSQL tạm, không sửa private.env hoặc database làm việc. Phòng ban được Admin gán qua [API quản trị tài khoản](accounts.md); CRUD/cây phòng ban thuộc195–198, chưa thuộc API hồ sơ.

V38 (`V38__add_unique_account_phone.sql`) thêm ràng buộc `user_accounts_phone_key UNIQUE (phone)`; `NULL` vẫn được lặp lại. Mọi đường ghi số điện thoại (`PUT /profile`, `PUT /accounts/{id}`, [nhập nhân sự từ Excel](account-import.md)) đều kiểm tra trước rồi dựa vào ràng buộc này khi hai lần lưu tranh nhau. Nếu database đã có số dùng cho nhiều tài khoản, V38 dừng backend lúc khởi động với lỗi `V38: <số lượng> so dien thoai dang dung cho nhieu tai khoan...`; lỗi chỉ báo số lượng, không ghi số điện thoại nào vào log, và không thay đổi dữ liệu nào. **Trước khi deploy**, chạy câu kiểm tra sau trên database thật (chỉ đọc). Câu này trả các số đang dùng chung và số tài khoản của mỗi số:

```sql
SELECT phone, count(*) FROM user_accounts WHERE phone IS NOT NULL GROUP BY phone HAVING count(*) > 1;
```

Nếu có dòng trả về, câu sau (cũng chỉ đọc) liệt kê các tài khoản cần quyết định, kèm `id` để sửa, vì API tìm kiếm tài khoản không tìm theo số điện thoại:

```sql
SELECT id, email, full_name, phone, enabled, admin_locked_at, created_at
FROM user_accounts
WHERE phone IN (SELECT phone FROM user_accounts WHERE phone IS NOT NULL GROUP BY phone HAVING count(*) > 1)
ORDER BY phone, created_at;
```

Admin quyết định tài khoản nào giữ số và sửa/xóa số ở các tài khoản còn lại, ví dụ qua `PUT /accounts/{id}` với `phone` khác hoặc `null`. API này thay thế mọi trường được phép, nên body phải gửi lại `fullName`, `displayTitle` và `departmentId` hiện tại của tài khoản (đọc bằng `GET /accounts/{id}`); bỏ trường nào thì trường đó bị xóa. Làm cho tới khi câu đầu tiên không còn dòng nào, rồi mới khởi động bản mới. Migration không tự chọn tài khoản giữ số. Thứ tự triển khai V38 so với các migration khác xem [tài liệu database](../database/README.md).
