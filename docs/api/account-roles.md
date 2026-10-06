# Gán và thu hồi vai trò tài khoản

Jira TKNHTTDNB1-154–158, story18. API danh mục vai trò153 thuộc phần việc riêng. Các đường dẫn dưới đây dùng tiền tố `/api/v1`.

## Yêu cầu chung

Gửi `Authorization: Bearer <accessToken>` của tài khoản có cả vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`. HR_MANAGER có quyền xem danh sách tài khoản nhưng không được thay vai trò. Server kiểm lại quyền hiện tại ngay trước khi ghi; không dựa vào menu frontend hoặc danh sách vai trò cũ trong token.

`id` là UUID của tài khoản cần thay đổi. `role` phân biệt hoa/thường, là một trong: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. `CANDIDATE` là vai trò bên ngoài, không gán vào tài khoản nội bộ.

## PUT /accounts/{id}/roles/{role}

Thêm **một** vai trò, giữ mọi vai trò đang có. Không cần JSON body. Ví dụ thêm quyền phỏng vấn cho trưởng bộ phận:

```http
PUT /api/v1/accounts/00000000-0000-0000-0000-000000000001/roles/INTERVIEWER
Authorization: Bearer <accessToken>
```

Nếu tài khoản đã có vai trò đó, vẫn trả200; không tạo bản ghi trùng.

## DELETE /accounts/{id}/roles/{role}

Chỉ bỏ vai trò nêu trên đường dẫn; giữ các vai trò khác. Không cần body. Vai trò đã vắng mặt vẫn trả200. **Không được tự thu hồi ADMIN của chính mình**: trả409 kể cả khi còn các vai trò khác.

## Kết quả và lỗi

Cả hai thao tác thành công trả **200**, `Cache-Control: no-store`:

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "roles": ["HIRING_MANAGER", "INTERVIEWER"]
}
```

Danh sách role sắp xếp theo tên. Response không chứa mật khẩu, token, hash hoặc phiên. Có thể gán/thu hồi role của tài khoản đang chờ kích hoạt hoặc disabled; việc này không kích hoạt/mở khóa tài khoản, không đổi thông tin hồ sơ/mật khẩu và không cấp hay thu hồi phiên.

| HTTP | Mã | Ý nghĩa |
|---|---|---|
|400|VALIDATION_ERROR|UUID hoặc tên role không hợp lệ|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token/phiên hoặc tài khoản thực hiện không hoạt động|
|403|FORBIDDEN|Không còn ADMIN hoặc thiếu quyền quản trị|
|404|ACCOUNT_NOT_FOUND|UUID hợp lệ nhưng không có tài khoản đích|
|409|SELF_ADMIN_REVOCATION|Admin tự thu hồi ADMIN|

Ví dụ409:

```json
{
  "code": "SELF_ADMIN_REVOCATION",
  "message": "Bạn không thể thu hồi vai trò ADMIN của chính mình.",
  "fieldErrors": {}
}
```

Frontend nên dựa vào HTTP/mã lỗi thay vì so khớp nội dung tiếng Việt. CORS cho phépPUT/DELETE từ các origin đã cấu hình.

## Hiệu lực quyền

Sau khi request thay đổi thành công, lần gọi API kế tiếp dùng **cùng access token** sẽ được kiểm bằng vai trò/quyền mới trong DB. Không cần đăng nhập lại, chờ JWT hết hạn hoặc cấp JWT mới. Nếu người đó giữ nhiều vai trò, quyền là hợp các quyền của những vai trò còn lại. Frontend cần gọi lại `/auth/permissions` để cập nhật menu và xử lý403 của API.

Cho phép thu hồi vai trò cuối của người khác. Khi danh sách rỗng, người đó vẫn có thể đăng nhập/refresh nhưng không gọi được API cần quyền, kể cả `/auth/me`, `/auth/permissions`, `/auth/logout` hiện dùng SELF_* theo vai trò. Admin có thể gán lại vai trò. Đây không phải thao tác khóa tài khoản; khóa quản trị thuộc162–166.

## Dữ liệu và chạy local

Dùng `user_roles` từV1 cùng danh mục/quyềnV3, không có migration mới, không cần đổi `.env`. Khóa actor/target theoUUID rồi khóa phiên để kiểm lại quyền trước khi thay đổi; transaction bảo vệ các thao tác đồng thời. PK(user_id,role) giữ mỗi cặp duy nhất. Backend phải được khởi động lại bằng bản code mới để có hai endpoint; database hiện có được giữ nguyên.
