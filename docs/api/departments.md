# API phòng ban và sơ đồ tổ chức

Phạm vi TKNHTTDNB1-195–196. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công dùng `Cache-Control: no-store`.

Đọc cần `ORGANIZATION_READ_ALL`; ghi cần `ORGANIZATION_WRITE_ALL`. Ma trận hiện tại cấp quyền đọc cho cả sáu vai trò nội bộ, ghi cho ADMIN và HR_MANAGER. Backend kiểm quyền hiện tại trong database và kiểm lại phiên/quyền khi thao tác ghi phải chờ khóa.

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

Ngừng áp dụng một phòng không xóa phòng, không tự ngừng phòng con hoặc gỡ thành viên. API quản trị tài khoản hiện có từ chối gán mới vào phòng inactive nhưng cho giữ liên kết cũ.

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

## Database và phạm vi

Dùng bảng departments của V5 và quyền ORGANIZATION của V3, không thêm migration hoặc thay .env. Không có DELETE trong nhóm này. Task 197 cần kiểm yêu cầu tuyển dụng mở, còn phụ thuộc module yêu cầu tuyển dụng; chưa nghiệm thu toàn bộ story 23.

Service kiểm chu trình và phối hợp khóa trong PostgreSQL cho các API ghi. V5 chỉ có CHECK chống tự làm cha và các FK, không có ràng buộc chống mọi chu trình khi sửa SQL thủ công. Không tự cascade trạng thái hoặc chuyển giao người phụ trách.