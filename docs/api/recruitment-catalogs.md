# API danh mục tuyển dụng dùng chung

Phạm vi TKNHTTDNB1-228, TKNHTTDNB1-229, TKNHTTDNB1-230 và TKNHTTDNB1-231 (story TKNHTTDNB1-27). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi `RECRUITMENT_CATALOG_*` dùng `Cache-Control: no-store`.

Đọc cần `ORGANIZATION_READ_ALL`; ghi cần `ORGANIZATION_WRITE_ALL`. Ma trận hiện tại cấp quyền đọc cho cả sáu vai trò nội bộ (recruiter, người phỏng vấn... cần đọc để chọn giá trị), ghi cho ADMIN và HR_MANAGER. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu; cách kiểm lại quyền khi ghi ở mục [Quyền quản lý danh mục](#quyền-quản-lý-danh-mục).

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
- `sortOrder` (thứ tự hiển thị) do server gán: giá trị mới được xếp **cuối** loại danh mục, bằng `sortOrder` lớn nhất hiện có trong loại (tính cả giá trị đã ngừng dùng) cộng 1, hoặc 0 nếu loại chưa có giá trị. PUT giữ nguyên `sortOrder`. Muốn đổi thứ tự hiển thị thì dùng [API sắp xếp](#sắp-xếp-thứ-tự-hiển-thị).

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

Response là **mảng JSON** chứa toàn bộ giá trị của loại đó, mỗi phần tử có cấu trúc như chi tiết ở trên; loại chưa có giá trị trả `[]`. Danh mục ngắn nên không phân trang. Thứ tự: `sortOrder` tăng dần; cùng `sortOrder` thì theo `name` (so sánh theo collation của cơ sở dữ liệu); cùng cả tên thì theo `code` (mã không trùng trong một loại nên thứ tự luôn cố định). Hai yêu cầu tạo cùng lúc trong một loại có thể nhận cùng `sortOrder` (V10 cho phép); khi đó danh sách xếp chúng theo tên. Sau một lần [sắp xếp](#sắp-xếp-thứ-tự-hiển-thị) mọi `sortOrder` đều khác nhau nên danh sách theo đúng thứ tự đã lưu.

## Sắp xếp thứ tự hiển thị

`PUT /recruitment-catalogs/{type}/order` lưu thứ tự mới cho **toàn bộ** giá trị của một loại danh mục, trả **200**.

```json
{
  "itemIds": [
    "00000000-0000-0000-0000-000000000012",
    "00000000-0000-0000-0000-000000000010",
    "00000000-0000-0000-0000-000000000011"
  ]
}
```

| Trường | Quy tắc |
|---|---|
|itemIds|Mảng UUID bắt buộc, giá trị đứng đầu mảng hiển thị đầu tiên. Phải gồm **đúng mọi** giá trị hiện có của loại trong URL, kể cả giá trị đã ngừng dùng (`active=false`), mỗi giá trị đúng một lần|

- Server gán `sortOrder` theo vị trí trong mảng: 0, 1, 2... Khoảng trống (do xóa) và các số trùng nhau (do hai yêu cầu tạo cùng lúc) được đánh số lại liền nhau. Giá trị tạo sau đó vẫn xếp cuối (`sortOrder` lớn nhất cộng 1).
- Chỉ giá trị có `sortOrder` thay đổi mới đổi `updatedAt`; gửi lại đúng thứ tự đang lưu không đổi dòng nào. `code`, `name`, `active`, `createdAt` giữ nguyên.
- Response là mảng JSON chứa mọi giá trị của loại theo thứ tự vừa lưu, cùng cấu trúc với danh sách. Sau đó `GET .../items` trả đúng thứ tự này (bộ lọc `active` giữ nguyên thứ tự). Loại chưa có giá trị nhận `{"itemIds": []}` và trả `[]`.
- Thiếu giá trị, thừa UUID không tồn tại, UUID thuộc loại danh mục khác, mảng rỗng khi loại đã có giá trị, hoặc một UUID lặp lại: **400** `RECRUITMENT_CATALOG_ORDER_MISMATCH`, không lưu gì. Thường gặp khi người khác vừa thêm/xóa giá trị; giao diện nên tải lại danh sách rồi cho sắp xếp lại.
- Thiếu `itemIds` hoặc có phần tử `null`: 400 `VALIDATION_ERROR` (`fieldErrors.itemIds` hoặc `fieldErrors.itemIds[0]`...). UUID sai định dạng, `itemIds` không phải mảng hoặc có trường khác như `sortOrder`: 400 `INVALID_JSON`.

Mọi thay đổi của một lần sắp xếp nằm trong một transaction: lưu hết hoặc không lưu gì. Backend khóa mọi giá trị của loại danh mục (theo thứ tự UUID) trước khi so danh sách, nên hai yêu cầu sắp xếp cùng loại tại cùng thời điểm chạy lần lượt: yêu cầu sau chờ yêu cầu trước commit rồi ghi đè bằng toàn bộ thứ tự của nó, không bao giờ trộn hai thứ tự. Nếu một giá trị bị xóa trong lúc yêu cầu sắp xếp đang chờ, yêu cầu đó nhận 400 vì danh sách đã cũ. Sửa (PUT) và xóa một giá trị cũng khóa dòng đó, nên không ghi đè lẫn nhau với việc sắp xếp.

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

## Quyền quản lý danh mục

Tạo, sửa, xóa và sắp xếp giá trị cần mã quyền `ORGANIZATION_WRITE_ALL`; theo V3 chỉ ADMIN và HR_MANAGER có mã này. Backend kiểm theo **mã quyền** trong database, không theo tên vai trò. Quyền được kiểm ở hai lớp:

1. `SecurityConfiguration`: POST `/items`, PUT `/items/{id}`, DELETE `/items/{id}` và PUT `/order` cần `PERM_ORGANIZATION_WRITE_ALL`. Thiếu quyền thì trả 403 `FORBIDDEN` trước khi vào service.
2. `RecruitmentCatalogService` kiểm lại bên trong transaction ghi:
   - Đầu tiên khóa tài khoản người gọi rồi khóa phiên, sau đó kiểm tài khoản còn được truy cập, access token và phiên chưa hết hạn, quyền vẫn còn trong database. Bước này chạy trước khi đụng tới bất kỳ giá trị danh mục nào, nên người đã mất quyền không khóa được giá trị nào.
   - Sửa và xóa khóa thêm giá trị sẽ đổi; sắp xếp khóa mọi giá trị của loại. Nếu một yêu cầu ghi khác đang giữ giá trị đó thì yêu cầu này phải chờ. Lấy được khóa xong, service kiểm lại hạn token, phiên và quyền rồi mới xử lý tiếp, kể cả việc trả 404 khi giá trị không còn. Tạo mới không khóa giá trị có sẵn nên chỉ có lần kiểm ở bước trên.

| Tình huống | Kết quả |
|---|---|
|RECRUITER, HIRING_MANAGER, INTERVIEWER hoặc APPROVER (mặc định không có `ORGANIZATION_WRITE_ALL`) gọi API ghi|403 `FORBIDDEN`, database không đổi; vẫn đọc được danh mục|
|Quyền bị gỡ khỏi vai trò (xóa dòng `role_permissions`) trong lúc yêu cầu ghi đang chờ khóa|403 `FORBIDDEN`, không lưu gì|
|Access token hết hạn trong lúc yêu cầu ghi đang chờ khóa|401 `SESSION_INVALID`, không lưu gì|
|Admin gỡ vai trò của người gọi bằng `DELETE /accounts/{id}/roles/{role}` trong lúc yêu cầu ghi của người đó đang chạy|API vai trò cũng khóa tài khoản đó nên phải chờ yêu cầu ghi xong. Yêu cầu ghi đang chạy hoàn tất theo quyền đã kiểm; từ yêu cầu kế tiếp (cùng token) người đó nhận 403|
|Cấp thêm `ORGANIZATION_WRITE_ALL` cho vai trò khác (thêm dòng `role_permissions`)|Người có vai trò đó ghi được ngay từ yêu cầu kế tiếp, không cần đăng nhập lại|

Giới hạn hiện tại (giống service phòng ban và chức danh): sau khi lệnh ghi cuối cùng đã gửi xuống, PostgreSQL có thể còn phải chờ một transaction khác đang ghi cùng mã (ràng buộc mã duy nhất) hoặc đang lưu tham chiếu tới giá trị bị xóa (khóa ngoại). Sau lần chờ này service không kiểm lại quyền lần nữa.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, UUID hoặc tham số `active` không phải true/false|
|400|INVALID_JSON|JSON sai hoặc có trường ngoài hợp đồng (kể cả `type`, `sortOrder`); UUID trong `itemIds` sai định dạng|
|400|RECRUITMENT_CATALOG_ORDER_MISMATCH|`itemIds` của PUT `/order` không đúng bằng các giá trị hiện có của loại danh mục (thiếu, thừa, khác loại hoặc lặp)|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token (kể cả hết hạn trong lúc yêu cầu ghi chờ khóa: `SESSION_INVALID`); phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu quyền tổ chức tương ứng, kể cả khi quyền bị gỡ trong lúc yêu cầu ghi chờ khóa|
|404|RECRUITMENT_CATALOG_TYPE_NOT_FOUND|`{type}` không phải một trong bốn loại ở trên|
|404|RECRUITMENT_CATALOG_ITEM_NOT_FOUND|Không có giá trị với UUID này trong loại danh mục của URL|
|409|RECRUITMENT_CATALOG_CODE_EXISTS|Mã đã được giá trị khác trong cùng loại dùng, kể cả khi hai yêu cầu ghi cùng mã đồng thời|
|409|RECRUITMENT_CATALOG_ITEM_IN_USE|DELETE giá trị còn được dữ liệu khác tham chiếu|

Body được kiểm trước loại danh mục: POST/PUT (kể cả PUT `/order`) với `{type}` sai và body thiếu trường trả 400, không phải 404.

## Database và phạm vi

Dùng bảng `recruitment_catalog_items` của V10 và quyền ORGANIZATION của V3; task 228, 229, 230 và 231 không thêm migration hoặc thay `.env`. Task 231 không đổi URL, mã quyền hay hợp đồng request/response, chỉ thêm bước kiểm lại quyền sau khi chờ khóa giá trị. Sắp xếp chỉ ghi cột `sort_order` và `updated_at`.
