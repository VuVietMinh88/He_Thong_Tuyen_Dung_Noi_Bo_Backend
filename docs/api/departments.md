# API phòng ban và sơ đồ tổ chức

Phạm vi TKNHTTDNB1-195–198. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công dùng `Cache-Control: no-store`.

Đọc cần `ORGANIZATION_READ_ALL`; ghi cần `ORGANIZATION_WRITE_ALL`. Ma trận hiện tại cấp quyền đọc cho cả sáu vai trò nội bộ, ghi cho ADMIN và HR_MANAGER. Backend kiểm quyền hiện tại trong database và kiểm lại phiên/quyền khi thao tác ghi phải chờ khóa (chi tiết ở mục "Quyền quản lý" bên dưới).

## Tạo và sửa

`POST /departments` tạo phòng ban, trả **201**. `PUT /departments/{id}` cập nhật phòng ban có UUID tương ứng, trả **200**.

```json
{
  "code": "HR",
  "name": "Phòng Nhân sự",
  "parentId": null,
  "managerUserId": "00000000-0000-0000-0000-000000000001",
  "active": true
}
```

| Trường | Quy tắc |
|---|---|
|code|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự; duy nhất và phân biệt hoa/thường theo schema V5|
|name|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự|
|parentId|UUID phòng ban cha; null hoặc bỏ trường nghĩa là phòng ban gốc|
|managerUserId|UUID người phụ trách, bắt buộc|
|active|Boolean bắt buộc; false nghĩa là ngừng áp dụng|

PUT thay thế toàn bộ các trường trên. Frontend phải gửi lại parentId hiện tại nếu muốn giữ phòng ban cha. Trường ngoài hợp đồng như id, createdAt, children hoặc roles bị từ chối với HTTP 400.

Response của tạo/sửa và `GET /departments/{id}`:

```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "code": "HR",
  "name": "Phòng Nhân sự",
  "parentId": null,
  "managerUserId": "00000000-0000-0000-0000-000000000001",
  "managerFullName": "Nguyễn Văn A",
  "active": true,
  "createdAt": "2026-10-06T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa; managerUserId phải là tài khoản có thật. Đổi người phụ trách không tự đổi phòng ban hoặc vai trò của người đó.

Khi tạo, đổi người phụ trách hoặc bật lại một phòng ban, người được chọn phải tồn tại, đã bật tài khoản và không bị Admin khóa. Không bắt buộc một vai trò riêng cho người phụ trách. Có thể giữ người phụ trách hiện tại đã bị khóa khi sửa tên/mã/phòng cha hoặc ngừng áp dụng; bật lại phải chọn người đủ điều kiện.

Parent phải tồn tại, không được là chính phòng ban hoặc hậu duệ của nó. Phòng cha có thể đã ngừng áp dụng. Hai người đổi quan hệ cha đồng thời vẫn phải giữ cây không có chu trình.

## Danh sách

`GET /departments?q=nhân%20sự&active=true&page=0&size=20`

| Tham số | Ý nghĩa |
|---|---|
|q|Tìm một phần code/name, không phân biệt hoa/thường, tối đa 255 ký tự; %, _ được hiểu là ký tự thật|
|active|true/false; bỏ qua để lấy cả hai trạng thái|
|page|Từ 0, mặc định 0|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`. Mỗi item có cấu trúc chi tiết ở trên. Sắp xếp theo code rồi UUID để phân trang ổn định; trang ngoài phạm vi có items rỗng. Không có lọc tự động theo phòng ban của người gọi vì quyền hiện tại là READ_ALL.

## Cây tổ chức

`GET /departments/tree` trả mảng các node gốc. Mỗi node có toàn bộ trường của chi tiết và thêm `children` là mảng các phòng con; node lá có children rỗng. Thứ tự node cùng cấp theo code rồi UUID. Chưa có dữ liệu trả `[]`.

Cây chứa cả node active và inactive, không phân trang hoặc lọc để tránh làm đứt quan hệ cha–con. Dữ liệu cây có chu trình do sửa SQL ngoài API sẽ trả HTTP 409 `DEPARTMENT_TREE_INVALID`; backend không bỏ qua âm thầm các node lỗi.

Ngừng áp dụng một phòng không xóa phòng, không tự ngừng phòng con hoặc gỡ thành viên. API quản trị tài khoản hiện có từ chối gán mới vào phòng inactive nhưng cho giữ liên kết cũ. [API yêu cầu tuyển dụng](requisitions.md) (task 246) không cho tạo hoặc lưu lại bản nháp với phòng inactive (`REQUISITION_DEPARTMENT_INACTIVE`); nháp đã có vẫn xem được. Trong lúc một yêu cầu tuyển dụng đang được lưu, PUT sửa phòng đó (kể cả ngừng áp dụng) phải chờ yêu cầu lưu xong.

## Xóa (task 197)

`DELETE /departments/{id}` xóa hẳn một phòng ban **không còn được dùng**, ví dụ phòng tạo nhầm. Cần `ORGANIZATION_WRITE_ALL` như tạo/sửa (ADMIN, HR_MANAGER); người phụ trách phòng ban không vì thế mà được xóa. Không có body. Thành công trả **204**, body rỗng, `Cache-Control: no-store`.

Phòng ban còn được dùng thì **không xóa được, chỉ ngừng áp dụng** bằng PUT `active=false` (giữ lại lịch sử). Backend kiểm theo thứ tự sau và trả 409 cho lý do đầu tiên gặp phải:

| Thứ tự | Điều kiện | HTTP | Mã | Cách xử lý gợi ý |
|---|---|---|---|---|
|1|Phòng ban có yêu cầu tuyển dụng **chưa đóng** của chính nó|409|DEPARTMENT_HAS_OPEN_REQUISITIONS|Ngừng áp dụng phòng ban thay vì xóa|
|2|Còn phòng ban con (`parentId` trỏ tới phòng này)|409|DEPARTMENT_HAS_CHILDREN|Chuyển hoặc xóa phòng con trước, hoặc ngừng áp dụng|
|3|Còn tài khoản thuộc phòng ban (kể cả tài khoản chưa kích hoạt hoặc bị khóa)|409|DEPARTMENT_HAS_MEMBERS|Chuyển tài khoản sang phòng khác qua `PUT /accounts/{id}`, hoặc ngừng áp dụng|
|4|Một dòng dữ liệu khác vẫn tham chiếu phòng ban (khóa ngoại)|409|DEPARTMENT_IN_USE|Ngừng áp dụng phòng ban thay vì xóa|

- "Chưa đóng": V13 chỉ có trạng thái `DRAFT` và bản nháp được coi là chưa đóng, nên **hiện nay mọi yêu cầu tuyển dụng của phòng ban đều chặn việc xóa**. Khi luồng duyệt thêm trạng thái mới, trạng thái nào chưa đóng/chưa hủy phải được thêm vào danh sách `OPEN_REQUISITION_STATUSES` của `DepartmentRepository`. Yêu cầu đã đóng vẫn tham chiếu phòng ban (khóa ngoại V13 `ON DELETE RESTRICT` giữ lịch sử), nên vẫn chặn xóa nhưng với mã 4 `DEPARTMENT_IN_USE`.
- Chỉ tính yêu cầu tuyển dụng của chính phòng ban: yêu cầu của phòng con chặn xóa phòng con, còn phòng cha bị chặn vì còn phòng con (mã 2).
- Phòng ban đã ngừng áp dụng vẫn bị kiểm như trên; ngừng áp dụng không làm phòng "xóa được".
- Mã 4 là lớp chặn cuối khi các bước 1–3 không biết tới dòng tham chiếu (ví dụ yêu cầu đã đóng sau này hoặc bảng mới), để không trả 500.
- Lỗi 409 có `fieldErrors` rỗng; frontend nên dựa vào `code`, có thể hiện `message` tiếng Việt.

Thứ tự lỗi đầy đủ: 401 (token/phiên), 403 (thiếu quyền, kể cả với UUID không tồn tại), 404 `DEPARTMENT_NOT_FOUND`, rồi 409 như bảng trên. UUID sai định dạng trả 400 `VALIDATION_ERROR`. Gọi lại DELETE cho phòng đã xóa trả 404.

**Đồng thời:** service khóa tài khoản người gọi, phiên, advisory lock của cây (giống tạo/sửa), kiểm lại quyền, rồi khóa dòng phòng ban (`SELECT ... FOR UPDATE`) và kiểm lại quyền thêm một lần (task 198) trước khi kiểm các điều kiện trên. Lưu yêu cầu tuyển dụng và gán tài khoản vào phòng ban đọc dòng này bằng `FOR SHARE`, nên:

- yêu cầu tuyển dụng hoặc việc gán tài khoản đang lưu dở: DELETE chờ; nếu bên kia commit thì DELETE thấy dữ liệu mới và trả 409 tương ứng, nếu bên kia hủy thì DELETE xóa bình thường;
- DELETE đang chạy: việc lưu yêu cầu tuyển dụng/gán tài khoản vào phòng đó chờ, rồi nhận lỗi phòng ban không tồn tại (`INVALID_REQUISITION_DEPARTMENT`, `INVALID_DEPARTMENT`);
- PUT đổi phòng cha sang phòng đang bị xóa chờ advisory lock của cây, rồi nhận `INVALID_DEPARTMENT_PARENT`.

Xóa không đổi tài khoản người phụ trách, phòng cha hay dữ liệu nào khác.

## Quyền quản lý (task 198)

| Người gọi (theo seed V3) | Đọc (`GET`) | Tạo, sửa, xóa (`POST`, `PUT`, `DELETE`) |
|---|---|---|
|ADMIN, HR_MANAGER|200|Được phép|
|RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER|200|403 `FORBIDDEN`, không ghi gì|
|Tài khoản không còn vai trò nào|403|403 `FORBIDDEN`, không ghi gì|

- Backend kiểm theo **mã quyền** `ORGANIZATION_WRITE_ALL` của các vai trò người gọi, đọc lại từ `role_permissions` ở mỗi yêu cầu, không theo tên vai trò. Cấp/gỡ quyền bằng migration mới, hoặc gán/gỡ vai trò qua [API vai trò tài khoản](account-roles.md), có hiệu lực ngay ở yêu cầu kế tiếp với cùng access token, không cần đăng nhập lại.
- Là người phụ trách (`managerUserId`) của một phòng ban không cho thêm quyền sửa hay xóa phòng ban đó.
- Lớp 1: `SecurityConfiguration` kiểm quyền ở URL trước khi đọc body, nên người thiếu quyền nhận 403 kể cả khi body sai hoặc UUID không tồn tại (không lộ 400/404).
- Lớp 2: `DepartmentService` khóa tài khoản người gọi (và người phụ trách trong body), phiên, advisory lock của cây, rồi kiểm lại token, phiên và quyền.
- Lớp 3 (task 198): `PUT` và `DELETE` còn khóa dòng phòng ban (`SELECT ... FOR UPDATE`). Bước này có thể phải chờ, vì việc gán tài khoản vào phòng hoặc lưu yêu cầu tuyển dụng của phòng giữ dòng `FOR SHARE` tới khi commit. Sau khi chờ, service kiểm lại lần nữa: quyền bị gỡ trong lúc chờ trả 403 `FORBIDDEN`, access token hết hạn trong lúc chờ trả 401 `SESSION_INVALID`, và không có gì được ghi. Lần kiểm này đứng trước 404, nên người đã mất quyền không biết phòng ban có tồn tại hay không. `POST` không khóa phòng ban có sẵn nên không có lớp 3.
- Admin gỡ vai trò qua API đúng lúc người đó đang ghi phòng ban: API vai trò khóa cùng dòng tài khoản nên chờ thao tác ghi xong. Thao tác đang chạy hoàn tất (lúc kiểm, người đó còn quyền); yêu cầu kế tiếp nhận 403.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, UUID, page/size hoặc bộ lọc|
|400|INVALID_JSON|JSON sai hoặc có trường ngoài hợp đồng|
|400|INVALID_DEPARTMENT_PARENT|Phòng ban cha không tồn tại|
|400|INVALID_DEPARTMENT_MANAGER|Người phụ trách không tồn tại/không đủ điều kiện cho thao tác|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu quyền tổ chức tương ứng|
|404|DEPARTMENT_NOT_FOUND|Không tìm thấy phòng ban đích|
|409|DEPARTMENT_CODE_EXISTS|Mã đã dùng|
|409|DEPARTMENT_CYCLE|Quan hệ cha mới tạo chu trình|
|409|DEPARTMENT_TREE_INVALID|Cây đã lưu có dữ liệu không hợp lệ|
|409|DEPARTMENT_HAS_OPEN_REQUISITIONS|DELETE: phòng ban có yêu cầu tuyển dụng chưa đóng (task 197)|
|409|DEPARTMENT_HAS_CHILDREN|DELETE: phòng ban còn phòng ban con|
|409|DEPARTMENT_HAS_MEMBERS|DELETE: phòng ban còn tài khoản thuộc phòng ban|
|409|DEPARTMENT_IN_USE|DELETE: dữ liệu khác vẫn tham chiếu phòng ban (khóa ngoại)|

## Database và phạm vi

Dùng bảng departments của V5 và quyền ORGANIZATION của V3, không thêm migration hoặc thay .env. Task 197 thêm DELETE, đọc thêm bảng `recruitment_requisitions` của V13 và `user_accounts.department_id` của V5; cũng không thêm migration hay quyền mới. Task 198 chỉ thêm lần kiểm quyền sau khi khóa dòng phòng ban; không thêm endpoint, migration hay quyền. Chưa nghiệm thu toàn bộ story 23 qua giao diện.

Service kiểm chu trình và phối hợp khóa trong PostgreSQL cho các API ghi. V5 chỉ có CHECK chống tự làm cha và các FK, không có ràng buộc chống mọi chu trình khi sửa SQL thủ công. Không tự cascade trạng thái hoặc chuyển giao người phụ trách.