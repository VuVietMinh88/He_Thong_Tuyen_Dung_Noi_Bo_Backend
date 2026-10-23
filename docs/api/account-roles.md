# Gán và thu hồi vai trò tài khoản

Jira TKNHTTDNB1-154–158, story18. API danh mục vai trò153 thuộc phần việc riêng. Các đường dẫn dưới đây dùng tiền tố `/api/v1`.

## Yêu cầu chung

Gửi `Authorization: Bearer <accessToken>` của tài khoản có cả vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`. HR_MANAGER có quyền xem danh sách tài khoản nhưng không được thay vai trò. Server kiểm lại quyền hiện tại ngay trước khi ghi; không dựa vào menu frontend hoặc danh sách vai trò cũ trong token.

`id` là UUID của tài khoản cần thay đổi. `role` phân biệt hoa/thường, là một trong: `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. `CANDIDATE` là vai trò bên ngoài, không gán vào tài khoản nội bộ.

## PUT /accounts/{id}/roles

Thay **toàn bộ** vai trò của tài khoản bằng danh sách gửi lên, trong một transaction: vai trò không có trong danh sách bị thu hồi, vai trò mới được gán. Trang "Sửa tài khoản" dùng API này để lưu phần phân quyền bằng một request, thay cho một lệnh PUT/DELETE cho mỗi vai trò, nên không còn trường hợp lưu được một nửa. Ví dụ đổi một recruiter thành trưởng bộ phận kiêm người phỏng vấn:

```http
PUT /api/v1/accounts/00000000-0000-0000-0000-000000000001/roles
Authorization: Bearer <accessToken>
Content-Type: application/json

{"roles": ["HIRING_MANAGER", "INTERVIEWER"], "expectedRoles": ["RECRUITER"]}
```

- `roles` là bộ vai trò đầy đủ sau khi lưu, bắt buộc có ít nhất một mã; mã lặp lại được gộp, thứ tự không quan trọng. Thiếu trường, `null`, mảng rỗng hoặc có phần tử `null` trả **400 `VALIDATION_ERROR`** với `fieldErrors.roles` = "Tài khoản phải có ít nhất một vai trò.".
- `expectedRoles` là bộ vai trò trang đã tải và đang hiển thị (trường `roles` của `GET /accounts/{id}`), bắt buộc; có thể là mảng rỗng khi tài khoản chưa có vai trò nào. Mã lặp lại được gộp, thứ tự không quan trọng. Thiếu trường, `null` hoặc có phần tử `null` trả **400 `VALIDATION_ERROR`** với `fieldErrors.expectedRoles` = "Thiếu danh sách vai trò hiện tại của tài khoản đang xem.".
- Mỗi mã trong hai danh sách phải là một trong sáu mã ở trên, đúng hoa/thường. `CANDIDATE`, mã không tồn tại, chữ thường như `admin`, số (kể cả `0` hay `"0"`) hoặc giá trị không phải chuỗi trả **400 `INVALID_JSON`**. Body chỉ có hai trường `roles` và `expectedRoles`; trường khác, kể cả `userId` của response, cũng trả **400 `INVALID_JSON`** thay vì bị bỏ qua.
- **Không ghi đè thay đổi của người khác**: sau khi khóa tài khoản, server so `expectedRoles` với vai trò hiện có. Nếu khác (ví dụ một admin khác vừa gán hoặc thu hồi vai trò sau khi trang được tải), server trả **409 `ACCOUNT_ROLES_CHANGED`** và không đổi vai trò nào. Frontend tải lại tài khoản, cho người dùng xem bộ vai trò mới rồi lưu lại. Nếu không có bước này, lần lưu từ trang cũ sẽ âm thầm hoàn tác thay đổi kia, kể cả việc thu hồi ADMIN.
- Gửi lại đúng bộ vai trò đang có trả 200 và không ghi gì vào `user_roles`. Khi đổi bộ vai trò, vai trò được giữ không bị xóa rồi thêm lại; chỉ vai trò bị bỏ và vai trò mới thay đổi.
- **Không được bỏ ADMIN của chính mình**: nếu `id` là tài khoản đang gọi và `roles` không có `ADMIN`, server trả **409 `SELF_ADMIN_REVOCATION`** và không đổi vai trò nào, kể cả các vai trò được thêm trong cùng request. Admin vẫn bỏ được ADMIN của một admin khác. Khi `expectedRoles` đã cũ, `ACCOUNT_ROLES_CHANGED` được trả trước.

Quyền, thứ tự khóa và việc kiểm lại quyền sau khi khóa giống hai API bên dưới; hai API đó chỉ đổi một vai trò nên không cần `expectedRoles`. API này luôn để lại ít nhất một vai trò; `DELETE /accounts/{id}/roles/{role}` vẫn gỡ được vai trò cuối của người khác như trước (mục Hiệu lực quyền). `PUT /accounts/{id}` vẫn không nhận trường `roles`, nên frontend lưu thông tin cơ bản trước rồi mới gọi API này khi bộ vai trò thay đổi.

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

Cả ba thao tác thành công trả **200**, `Cache-Control: no-store`:

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "roles": ["HIRING_MANAGER", "INTERVIEWER"]
}
```

Danh sách role sắp xếp theo tên. Response không chứa mật khẩu, token, hash hoặc phiên. Có thể gán/thu hồi role của tài khoản đang chờ kích hoạt hoặc disabled; việc này không kích hoạt/mở khóa tài khoản, không đổi thông tin hồ sơ/mật khẩu và không cấp hay thu hồi phiên.

| HTTP | Mã | Ý nghĩa |
|---|---|---|
|400|VALIDATION_ERROR|UUID hoặc tên role trên đường dẫn không hợp lệ; với `PUT /accounts/{id}/roles`: thiếu `roles`, mảng rỗng hoặc có phần tử `null`, hoặc thiếu `expectedRoles`, `null` hoặc có phần tử `null`|
|400|INVALID_JSON|Chỉ `PUT /accounts/{id}/roles`: body không phải JSON hợp lệ, mã vai trò không hợp lệ hoặc có trường lạ|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token/phiên hoặc tài khoản thực hiện không hoạt động|
|403|FORBIDDEN|Không còn ADMIN hoặc thiếu quyền quản trị|
|404|ACCOUNT_NOT_FOUND|UUID hợp lệ nhưng không có tài khoản đích|
|409|ACCOUNT_ROLES_CHANGED|Chỉ `PUT /accounts/{id}/roles`: vai trò hiện có khác `expectedRoles`, tức trang đã cũ; tải lại rồi lưu lại|
|409|SELF_ADMIN_REVOCATION|Admin tự thu hồi ADMIN, hoặc gửi cho chính mình bộ vai trò không có ADMIN|

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

Cho phép thu hồi vai trò cuối của người khác bằng `DELETE`; API thay cả bộ không nhận danh sách rỗng. Khi danh sách rỗng và tài khoản còn được truy cập, người đó vẫn có thể đăng nhập/refresh nhưng không gọi được API cần quyền, kể cả `/auth/me`, `/auth/permissions`, `/auth/logout` hiện dùng SELF_* theo vai trò. Admin có thể gán lại vai trò. Để chặn cả đăng nhập và thu hồi phiên, dùng [API khóa tài khoản](account-locking.md).

## Dữ liệu và chạy local

Dùng `user_roles` từV1 cùng danh mục/quyềnV3, không có migration mới, không cần đổi `.env`. Khóa actor/target theoUUID rồi khóa phiên để kiểm lại quyền trước khi thay đổi; transaction bảo vệ các thao tác đồng thời. API thay cả bộ so `expectedRoles` sau khi đã khóa tài khoản đích, nên một lần lưu đang chờ khóa không ghi đè vai trò mà người khác vừa commit. PK(user_id,role) giữ mỗi cặp duy nhất. Backend phải được khởi động lại bằng bản code mới để có các endpoint này; database hiện có được giữ nguyên.
