# Quản trị tài khoản nội bộ

Jira TKNHTTDNB1-145–149, story17; dữ liệu phòng ban dùng schema194. Backend dùng `/api/v1`. Tạo/sửa yêu cầu `ADMIN` và `USER_ADMIN_WRITE_ALL`; đọc yêu cầu `USER_ADMIN_READ_ALL` (hiện Admin và HR_MANAGER). Quyền được kiểm trên server mỗi yêu cầu. Gán/thu hồi vai trò và khóa quản trị thuộc các task riêng.

## GET /accounts

Gửi Bearer của tài khoản có quyền đọc. Các tham số có thể kết hợp bằng AND:

| Tham số | Ý nghĩa |
|---|---|
| `q` | Tối đa255 ký tự; tìm chứa tên, email hoặc tên phòng ban, không phân biệt hoa/thường; không bỏ dấu tiếng Việt |
| `role` | Một vai trò nội bộ, ví dụ `INTERVIEWER` |
| `status` | Một trong bốn trạng thái bên dưới |
| `departmentId` | UUID phòng ban; chỉ phòng ban đó, không tự gồm các phòng con |
| `page` | Bắt đầu từ0, mặc định0 |
| `size` | Từ1–100, mặc định20 |

Ví dụ `/api/v1/accounts?q=nh%C3%A2n%20s%E1%BB%B1&role=INTERVIEWER&page=0&size=20`. Ký tự `%`, `_`, `!` trong từ khóa được tìm theo nghĩa đen; SQL bind tham số. Thứ tự cố định `createdAt` giảm dần rồi `id` tăng dần; tài khoản nhiều vai trò chỉ xuất hiện một lần. Count và danh sách đọc cùng snapshot PostgreSQL trong mỗi yêu cầu; các lần gọi riêng có thể phản ánh dữ liệu vừa đổi.

Trả **200**, `Cache-Control: no-store`:

```json
{
  "items": [{
    "id": "00000000-0000-0000-0000-000000000001",
    "email": "interviewer@example.com",
    "fullName": "Nguyễn Văn An",
    "phone": "0912345678",
    "displayTitle": "Chuyên viên",
    "departmentId": null,
    "departmentName": null,
    "roles": ["INTERVIEWER"],
    "status": "ACTIVE",
    "createdAt": "2026-10-05T00:00:00Z"
  }],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

Trạng thái là dữ liệu suy ra tại thời điểm đọc:

- `ACTIVE`: tài khoản enabled và không đang bị khóa đăng nhập tạm.
- `TEMPORARILY_LOCKED`: enabled nhưng `locked_until` còn trong tương lai do đăng nhập sai.
- `PENDING_ACTIVATION`: disabled và có token kích hoạt chưa tiêu thụ; token hết hạn vẫn thuộc trạng thái chờ kích hoạt.
- `DISABLED`: disabled và không có token kích hoạt chưa tiêu thụ. Đây là trạng thái có thể đọc từ dữ liệu, chưa phải API khóa/mở tài khoản quản trị.

Không trả mật khẩu, hash, token, thông tin phiên. Bộ lọc sai/UUID sai/page-size sai trả **400 `VALIDATION_ERROR`**; thiếu/phiên không hợp lệ **401**; thiếu quyền **403**. Không tìm thấy trả trang rỗng, `totalPages=0`. Trang vượt cuối cũng rỗng nhưng giữ tổng số phù hợp bộ lọc.

## GET /accounts/{id}

Cùng quyền đọc, trả **200** với một object như phần tử `items`. UUID hợp lệ nhưng không có tài khoản trả **404 `ACCOUNT_NOT_FOUND`**; UUID sai định dạng trả400.

## PUT /accounts/{id}

Admin gửi đầy đủ trạng thái mới của các trường được phép:

```json
{
  "fullName": "Nguyễn Văn An",
  "phone": "+84912345678",
  "displayTitle": "Chuyên viên tuyển dụng",
  "departmentId": null
}
```

`fullName` bắt buộc, trim/tối đa255; `phone` và `displayTitle` là tùy chọn (chuỗi trắng/null/bỏ trường sẽ xóa dữ liệu đó), chức danh tối đa120. Điện thoại theo quy tắc ở [hồ sơ cá nhân](profile.md); `+84` được lưu thành số bắt đầu bằng0. `departmentId=null` hoặc bỏ trường sẽ gỡ phòng ban, nên frontend cần gửi lại phòng ban hiện tại nếu muốn giữ nguyên. Đây là PUT thay thế các trường được phép, không phải PATCH.

Phòng ban mới phải tồn tại và `active=true`. Có thể giữ nguyên phòng ban cũ đã ngừng áp dụng khi chỉ sửa thông tin khác; gán mới vào phòng ngừng áp dụng hoặc UUID không tồn tại trả **400 `INVALID_DEPARTMENT`**. API quản lý danh mục phòng ban thuộc195, chưa có trong nhóm này;194 chỉ cung cấp schema. Không tự tạo phòng ban từ tên do người dùng nhập.

Email là định danh đăng nhập, giữ nguyên trong API này; đổi email cần luồng xác minh riêng. `email`, `roles`, `enabled`, mật khẩu, `id` và mọi trường lạ đều trả **400 `INVALID_JSON`** thay vì bị bỏ qua. API không thay mật khẩu, vai trò, phiên hoặc token kích hoạt. Thu hồi/gán vai trò và khóa tài khoản có task riêng.

Thành công **200** với `AccountView`, no-store. Dữ liệu không hợp lệ **400**, tài khoản đích không tồn tại **404**, thiếu quyền **403**. Server khóa tài khoản theo thứ tự UUID rồi kiểm lại phiên và quyền Admin; quyền bị thu hồi trong lúc chờ khóa sẽ bị từ chối, không ghi tài khoản đích. CORS cho phépPUT từ các origin đã cấu hình.

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
