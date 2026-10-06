# API danh mục tuyển dụng dùng chung

Phạm vi TKNHTTDNB1-228 và TKNHTTDNB1-229 (story TKNHTTDNB1-27). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi `RECRUITMENT_CATALOG_*` dùng `Cache-Control: no-store`.

Đọc cần `ORGANIZATION_READ_ALL`; ghi cần `ORGANIZATION_WRITE_ALL`. Ma trận hiện tại cấp quyền đọc cho cả sáu vai trò nội bộ (recruiter, người phỏng vấn... cần đọc để chọn giá trị), ghi cho ADMIN và HR_MANAGER. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu và kiểm lại phiên/quyền sau khi khóa tài khoản người gọi khi ghi.

## Loại danh mục

Bốn loại dùng chung một API, phân biệt bằng `{type}` trong URL. `{type}` là tên chính xác, viết hoa, phân biệt hoa/thường (giống tên role ở API vai trò tài khoản):

| `{type}` | Ý nghĩa | Ví dụ giá trị |
|---|---|---|
|`CANDIDATE_SOURCE`|Nguồn ứng viên|LinkedIn, Nhân viên giới thiệu|
|`REJECTION_REASON`|Lý do loại hồ sơ|Chưa phù hợp kỹ năng|
|`WORK_LOCATION`|Địa điểm làm việc|Hà Nội|
|`EMPLOYMENT_TYPE`|Hình thức làm việc|Toàn thời gian|

Loại khác (kể cả `candidate_source`, `candidate-sources`) trả **404** `RECRUITMENT_CATALOG_TYPE_NOT_FOUND`, thông báo liệt kê bốn loại hợp lệ. Database không seed sẵn giá trị nào; Trưởng phòng Nhân sự tự khai báo.

## Tạo và sửa

`POST /recruitment-catalogs/{type}/items` tạo giá trị trong loại danh mục, trả **201**. `PUT /recruitment-catalogs/{type}/items/{id}` thay thế mã, tên và trạng thái của giá trị có UUID tương ứng, trả **200**.

```json
{
  "code": "LINKEDIN",
  "name": "LinkedIn",
  "active": true
}
```

| Trường | Quy tắc |
|---|---|
|code|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự; duy nhất **trong cùng loại danh mục**, phân biệt hoa/thường (`OTHER` khác `other`). Cùng mã `OTHER` có thể có ở hai loại khác nhau|
|name|Tên hiển thị; bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự|
|active|Boolean bắt buộc; false nghĩa là ngừng dùng giá trị này|

Body không có `type` và `sortOrder`:

- Loại danh mục lấy từ URL. PUT không chuyển được giá trị sang loại khác; gọi PUT với `{type}` khác loại của giá trị trả 404 `RECRUITMENT_CATALOG_ITEM_NOT_FOUND`.
- `sortOrder` (thứ tự hiển thị) do server gán: giá trị mới được xếp **cuối** loại danh mục, bằng `sortOrder` lớn nhất hiện có trong loại (tính cả giá trị đã ngừng dùng) cộng 1, hoặc 0 nếu loại chưa có giá trị. PUT giữ nguyên `sortOrder`. Việc đổi thứ tự hiển thị thuộc task 230.

Gửi thêm trường ngoài hợp đồng như `id`, `type`, `sortOrder`, `createdAt` bị từ chối với HTTP 400 `INVALID_JSON`. PUT phải gửi đủ ba trường; giữ nguyên mã của chính giá trị đang sửa không bị coi là trùng.

Response của tạo/sửa và `GET /recruitment-catalogs/{type}/items/{id}`:

```json
{
  "id": "00000000-0000-0000-0000-000000000010",
  "type": "CANDIDATE_SOURCE",
  "code": "LINKEDIN",
  "name": "LinkedIn",
  "sortOrder": 0,
  "active": true,
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa. `createdAt` giữ nguyên khi sửa; `updatedAt` là thời điểm ghi gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu).

## Danh sách

`GET /recruitment-catalogs/{type}/items?active=true`

| Tham số | Ý nghĩa |
|---|---|
|active|true/false; bỏ qua để lấy cả hai trạng thái. Màn hình chọn giá trị (ví dụ chọn nguồn ứng viên) nên dùng `active=true`|

Response là **mảng JSON** chứa toàn bộ giá trị của loại đó, mỗi phần tử có cấu trúc như chi tiết ở trên; loại chưa có giá trị trả `[]`. Danh mục ngắn nên không phân trang. Thứ tự: `sortOrder` tăng dần, cùng `sortOrder` thì theo `code`, rồi UUID. Hai yêu cầu tạo cùng lúc trong một loại có thể nhận cùng `sortOrder` (V10 cho phép); khi đó danh sách xếp chúng theo `code`.

## Xóa

`DELETE /recruitment-catalogs/{type}/items/{id}` xóa hẳn giá trị có UUID tương ứng trong loại danh mục của URL. Không có body.

- Thành công: **204**, body rỗng, `Cache-Control: no-store`. Giá trị biến mất khỏi danh sách và `GET` chi tiết trả 404; các giá trị còn lại giữ nguyên `sortOrder` (không tự dồn số). Mã của giá trị đã xóa được dùng lại cho giá trị mới.
- Giá trị **đang được tham chiếu** (một dòng dữ liệu khác, ví dụ hồ sơ ứng viên lưu nguồn ứng viên này, còn trỏ tới nó): **409** `RECRUITMENT_CATALOG_ITEM_IN_USE`, không xóa gì. Kể cả giá trị đã ngừng dùng (`active=false`) vẫn bị chặn nếu còn tham chiếu.

```json
{
  "code": "RECRUITMENT_CATALOG_ITEM_IN_USE",
  "message": "Giá trị danh mục đang được dữ liệu khác sử dụng nên không thể xóa. Hãy chuyển giá trị sang ngừng sử dụng (active = false)."
}
```

Muốn bỏ một giá trị đang được dùng thì PUT với `active=false`: dữ liệu cũ giữ nguyên tên gọi, màn hình chọn giá trị mới (`active=true`) không còn hiện giá trị đó. Xóa chỉ dành cho giá trị nhập nhầm hoặc chưa từng được dùng.

Backend không đếm tham chiếu trước, mà để PostgreSQL kiểm khóa ngoại khi xóa rồi đổi lỗi khóa ngoại thành 409. Cách này đúng với mọi bảng tham chiếu tới danh mục, kể cả bảng thêm sau, và đúng cả khi một yêu cầu khác vừa lưu tham chiếu tới giá trị đó cùng lúc: yêu cầu xóa chờ yêu cầu kia kết thúc, rồi trả 409 nếu tham chiếu đã được lưu hoặc 204 nếu yêu cầu kia bị hủy. Hiện chưa có bảng nào tham chiếu tới danh mục nên mọi giá trị đều xóa được; quy tắc cho bảng tham chiếu sau này ở [tài liệu database](../database/README.md).

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, UUID hoặc tham số `active` không phải true/false|
|400|INVALID_JSON|JSON sai hoặc có trường ngoài hợp đồng (kể cả `type`, `sortOrder`)|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu quyền tổ chức tương ứng|
|404|RECRUITMENT_CATALOG_TYPE_NOT_FOUND|`{type}` không phải một trong bốn loại ở trên|
|404|RECRUITMENT_CATALOG_ITEM_NOT_FOUND|Không có giá trị với UUID này trong loại danh mục của URL|
|409|RECRUITMENT_CATALOG_CODE_EXISTS|Mã đã được giá trị khác trong cùng loại dùng, kể cả khi hai yêu cầu ghi cùng mã đồng thời|
|409|RECRUITMENT_CATALOG_ITEM_IN_USE|DELETE giá trị còn được dữ liệu khác tham chiếu|

Body được kiểm trước loại danh mục: POST/PUT với `{type}` sai và body thiếu trường trả 400, không phải 404.

## Database và phạm vi

Dùng bảng `recruitment_catalog_items` của V10 và quyền ORGANIZATION của V3; task 228 và 229 không thêm migration hoặc thay `.env`. Đổi thứ tự hiển thị thuộc task 230.
