# API ảnh đại diện

Phạm vi TKNHTTDNB1-188 (story TKNHTTDNB1-22: nhân sự nội bộ tải ảnh đại diện để đồng nghiệp nhận ra trên lịch phỏng vấn chung). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và mọi lỗi của các API này dùng `Cache-Control: no-store`, trừ 401 `SESSION_INVALID` khi service kiểm lại phiên (xem bảng lỗi).

| API | Quyền server yêu cầu | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /profile/avatar`|`SELF_PROFILE_READ`|6 vai trò nội bộ|
|`PUT /profile/avatar`|`SELF_PROFILE_WRITE`|6 vai trò nội bộ|
|`DELETE /profile/avatar`|`SELF_PROFILE_WRITE`|6 vai trò nội bộ|
|`GET /accounts/{id}/avatar`|`SELF_PROFILE_READ`|6 vai trò nội bộ|

Chủ ảnh luôn lấy từ Access Token, giống [API hồ sơ cá nhân](profile.md): không có API tải lên hoặc xóa ảnh của người khác. Xem ảnh của đồng nghiệp chỉ cần `SELF_PROFILE_READ` vì mục đích là nhận ra nhau; API này chỉ trả ảnh, không trả email, vai trò hay thông tin tài khoản khác (khác `GET /accounts/{id}`, vốn cần `USER_ADMIN_READ_ALL`).

## PUT /api/v1/profile/avatar — tải lên hoặc thay ảnh

Body `multipart/form-data`, tệp nằm trong trường `file`. Trên trình duyệt dùng `FormData` và **không** tự đặt header `Content-Type`; trình duyệt sẽ tự thêm `boundary`.

- Chỉ nhận JPG hoặc PNG, nhận diện bằng các byte đầu tệp. Tên tệp và `Content-Type` của phần tệp bị bỏ qua, nên tệp GIF/WebP/văn bản đổi đuôi thành `.png` vẫn bị từ chối.
- Tối đa 2MB, tức 2.097.152 byte; đúng 2MB vẫn được nhận.
- Mỗi cạnh tối đa 4096 điểm ảnh. Kích thước được đọc từ phần đầu tệp trước khi giải mã toàn bộ, nên tệp khai báo kích thước khổng lồ bị từ chối sớm. Tệp hỏng hoặc bị cắt cụt cũng bị từ chối.
- Server cắt hình vuông lớn nhất ở giữa ảnh, tạo ảnh **256×256** và ảnh thu nhỏ **64×64** (hằng số `AVATAR_SIZE`, `THUMBNAIL_SIZE` trong `AvatarImageProcessor`). Ảnh nhỏ hơn 256 điểm ảnh được phóng to.
- Cả hai ảnh được lưu dạng PNG, kể cả khi tải lên JPG. Ảnh được ghi lại từ điểm ảnh nên không giữ EXIF, GPS hay ghi chú trong tệp gốc. Hướng xoay EXIF của ảnh chụp điện thoại **không** được áp dụng.
- Mỗi tài khoản có tối đa một ảnh; tải lên lần nữa sẽ thay ảnh cũ. Khi yêu cầu bị lỗi, ảnh cũ giữ nguyên.

Thành công trả **200**:

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "contentType": "image/png",
  "sizeBytes": 48213,
  "updatedAt": "2026-10-07T08:00:00Z",
  "imageUrl": "/api/v1/accounts/00000000-0000-0000-0000-000000000001/avatar",
  "thumbnailUrl": "/api/v1/accounts/00000000-0000-0000-0000-000000000001/avatar?size=thumbnail"
}
```

`sizeBytes` là dung lượng ảnh 256×256 đã lưu, không phải dung lượng tệp gốc. `updatedAt` là thời điểm lưu (UTC, độ chính xác micro giây như PostgreSQL). Hai URL là đường dẫn tương đối với địa chỉ backend và dùng được cho cả chính chủ lẫn đồng nghiệp.

Server kiểm tra và xử lý ảnh trước khi mở transaction, để khóa database chỉ giữ trong lúc ghi ngắn. Khi ghi, server khóa tài khoản người gọi rồi phiên đăng nhập, kiểm lại tài khoản còn được truy cập, phiên còn hiệu lực, token chưa hết hạn và quyền `SELF_PROFILE_WRITE`. Nếu trong lúc chờ khóa, tài khoản bị Admin khóa hoặc phiên bị thu hồi thì trả 401 `SESSION_INVALID`; nếu quyền bị gỡ thì trả 403 `FORBIDDEN`; ảnh không đổi. Hai lần tải lên đồng thời của cùng một người được xếp hàng: cả hai thành công, ảnh còn lại là của lần ghi sau, và ảnh lớn với ảnh nhỏ luôn thuộc cùng một lần tải.

## DELETE /api/v1/profile/avatar — xóa ảnh

Trả **204** không có body. Gọi khi chưa có ảnh vẫn trả 204, nên bấm lặp lại không gây lỗi. Khóa và kiểm tra lại giống PUT. Chỉ ảnh của người gọi bị xóa.

## Xem ảnh

`GET /api/v1/profile/avatar?size=full` xem ảnh của chính mình. `GET /api/v1/accounts/{id}/avatar?size=thumbnail` xem ảnh của tài khoản có UUID `{id}`.

| Tham số `size` | Ảnh trả về |
|---|---|
|`full` (mặc định khi bỏ qua)|256×256|
|`thumbnail`|64×64|

Giá trị khác, kể cả viết hoa như `FULL`, trả 400 `VALIDATION_ERROR`. Thành công trả **200**, body là chính byte ảnh với `Content-Type: image/png` và `X-Content-Type-Options: nosniff`. Chưa có ảnh hoặc không có tài khoản với UUID đó đều trả 404 `AVATAR_NOT_FOUND` (không phân biệt hai trường hợp). Ảnh của tài khoản đang bị khóa hoặc chưa kích hoạt vẫn được trả cho đồng nghiệp để lịch cũ còn hiển thị; xóa tài khoản sẽ xóa ảnh.

Thẻ `<img src="...">` không gửi được header Bearer. Frontend cần `fetch` kèm `Authorization`, đọc `response.blob()`, tạo `URL.createObjectURL(blob)` cho thẻ ảnh và `URL.revokeObjectURL` khi không dùng nữa. Vì response là `no-store`, nên giữ blob URL trong bộ nhớ của trang và chỉ tải lại khi `avatarUpdatedAt` của hồ sơ thay đổi.

## Hồ sơ cá nhân

`GET /profile` và `PUT /profile` có thêm `hasAvatar` (boolean) và `avatarUpdatedAt` (thời điểm UTC, `null` khi chưa có ảnh). Các trường cũ giữ nguyên nên frontend cũ không bị ảnh hưởng.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|AVATAR_FILE_REQUIRED|Không có trường `file`, ví dụ đặt tên trường khác hoặc gửi JSON thay vì multipart|
|400|AVATAR_INVALID|Tệp 0 byte, tệp hỏng hoặc bị cắt cụt|
|400|AVATAR_DIMENSIONS_TOO_LARGE|Chiều rộng hoặc chiều cao lớn hơn 4096 điểm ảnh|
|400|INVALID_MULTIPART|Body multipart hỏng, server không tách được trường và tệp|
|400|VALIDATION_ERROR|`size` không phải `full`/`thumbnail`; `{id}` không phải UUID|
|401|UNAUTHORIZED|Bộ lọc Bearer từ chối: thiếu, sai hoặc hết hạn token; phiên đã bị thu hồi; tài khoản bị khóa hoặc chưa kích hoạt. Có `WWW-Authenticate: Bearer` và `Cache-Control: no-store`|
|401|SESSION_INVALID|Request đã qua bộ lọc nhưng service kiểm lại thấy tài khoản vừa bị khóa, phiên vừa bị thu hồi hoặc token vừa hết hạn, thường gặp khi PUT/DELETE phải chờ khóa. Lỗi này đi qua `ApiExceptionHandler` nên **không** có `Cache-Control: no-store` và `WWW-Authenticate`, giống các API khác ([ma trận quyền](../architecture/role-permission-matrix.md), mục 6)|
|403|FORBIDDEN|Thiếu `SELF_PROFILE_READ` (xem) hoặc `SELF_PROFILE_WRITE` (tải lên, xóa)|
|404|AVATAR_NOT_FOUND|Chưa có ảnh hoặc không có tài khoản với UUID đó|
|413|AVATAR_TOO_LARGE|Tệp lớn hơn 2MB nhưng không vượt giới hạn multipart của server|
|413|FILE_TOO_LARGE|Tệp lớn hơn `spring.servlet.multipart.max-file-size` (hiện 5MB), bị chặn trước khi tới API ảnh|
|415|AVATAR_TYPE_UNSUPPORTED|Không phải JPG/PNG theo byte đầu tệp|

Frontend nên kiểm tra `file.size <= 2 * 1024 * 1024` và loại tệp trước khi gửi. Với tệp rất lớn, server có thể đóng kết nối trước khi gửi JSON 413, khi đó `fetch` chỉ báo lỗi mạng.

## Database và phạm vi

Migration V12 tạo bảng `user_avatars`: khóa chính `user_id` tham chiếu `user_accounts` với `ON DELETE CASCADE`, `content_type` (`image/png` hoặc `image/jpeg`), `image` và `thumbnail` kiểu `BYTEA`, `size_bytes` bằng dung lượng `image`, `updated_at`. Không đổi bảng hoặc quyền cũ; dùng `SELF_PROFILE_READ`/`SELF_PROFILE_WRITE` sẵn có. Chưa có hiển thị ảnh trên lịch phỏng vấn; module lịch phỏng vấn sẽ dùng `GET /accounts/{id}/avatar` khi được xây dựng.
