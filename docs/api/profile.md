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
- Chức danh tùy chọn, trim và tối đa120 ký tự.
- PUT thay thế ba trường: bỏ phone/displayTitle hoặc gửinull/chuỗi trắng sẽ xóa chúng. Frontend gửi đầy đủ ba trường khi lưu form.

Thành công **200** trả hồ sơ đã cập nhật, no-store. Email, phòng ban, vai trò, mật khẩu và các phiên đăng nhập được giữ nguyên. Field ngoài ba trường trên (`email`, `departmentId`, `roles`, `userId`, `enabled`...) trả **400 `INVALID_JSON`**; dữ liệu sai trả **400 `VALIDATION_ERROR`**. Không nhận ID từ body để sửa tài khoản khác.

Thiếu/hết hạn Access Token, phiên hết hạn/thu hồi hoặc tài khoản disabled trả **401**. Grant bị thu hồi trả **403** dù JWT còn hạn. Khi ghi, khóa tài khoản trước rồi phiên, kiểm lại chủ phiên/hiệu lực/quyền trước cập nhật. CORS PUT dùng danh sách origin hiện có.

## Dữ liệu và phạm vi

V5 bổ sung trường nullable và quyền mới, giữ mật khẩu/phiên/vai trò cũ. Backend khởi động sẽ áp dụng migration chưa chạy; sao lưu DB trước nâng cấp. Trong kiểm thử chỉ dùng PostgreSQL tạm, không sửa private.env hoặc database làm việc. Phòng ban được Admin gán qua [API quản trị tài khoản](accounts.md); CRUD/cây phòng ban thuộc195–198, chưa thuộc API hồ sơ.
