# API cấu hình luồng phê duyệt

Các API dưới `/api/v1/approval-flows` dùng `Authorization: Bearer <accessToken>`.
Người gọi cần vai trò ADMIN hoặc HR_MANAGER và quyền hiện hành `REQUISITIONS_READ_ALL`
khi đọc/xem thử, `REQUISITIONS_WRITE_ALL` khi ghi. Phiên, trạng thái tài khoản và quyền
được kiểm tra lại khi thao tác ghi phải chờ khóa. Response thành công có `Cache-Control: no-store`.

| Method/path | Kết quả |
|---|---|
| GET `/approval-flows?departmentId=<UUID>&page=0&size=20` | 200, danh sách phiên bản hiện hành; bộ lọc phòng ban tùy chọn |
| GET `/approval-flows/{id}` | 200, phiên bản hiện hành kèm các cấp duyệt |
| GET `/approval-flows/{id}?version=1` | 200, phiên bản lịch sử bất biến |
| POST `/approval-flows` | 201, tạo cấu hình phiên bản 1; có header Location |
| PUT `/approval-flows/{id}` | 200, tạo và áp dụng phiên bản kế tiếp |
| POST `/approval-flows/preview` | 200, xem các cấp khớp của phiên bản hiện hành, không ghi dữ liệu |

Mỗi phòng ban có một cấu hình và một phiên bản hiện hành. Phạm vi áp dụng là đúng
phòng ban được chọn, không tự kế thừa cấu hình phòng ban cha. Không có API xóa.
Danh sách có `{items,page,size,totalElements,totalPages}`, mặc định 20 dòng, tối đa 100;
items gồm id, departmentId, version, name, createdBy, createdAt. Chi tiết thêm `steps`.

## Tạo và cập nhật

```json
{
  "departmentId": "<UUID phòng ban đang áp dụng>",
  "name": "Duyệt tuyển dụng phòng kỹ thuật",
  "steps": [
    {"position": 1, "salaryThreshold": null, "approverRole": "HIRING_MANAGER"},
    {"position": 2, "salaryThreshold": 20000000, "approverRole": "HR_MANAGER"},
    {"position": 3, "salaryThreshold": 50000000, "approverUserId": "<UUID người duyệt>"}
  ]
}
```

Ví dụ UUID là chỗ điền giá trị thật. PUT gửi lại toàn bộ cấu hình và thêm
`"expectedVersion": 1` (phiên bản đã đọc). Trường này phải bỏ qua/null khi POST.
Không đổi departmentId bằng PUT. Hai người cùng sửa một phiên bản: một lần lưu thành công,
lần còn lại nhận 409 `APPROVAL_VERSION_CONFLICT`; tải lại trước khi thử lưu.

- Tên được trim, bắt buộc, tối đa 120 ký tự. Có từ 1 đến 20 cấp duyệt.
- position đúng thứ tự trong mảng, liên tiếp từ 1. Cấp đầu có salaryThreshold=null để luôn có luồng khớp.
- Các cấp cơ bản (ngưỡng null) đứng trước các cấp bổ sung. Ngưỡng bổ sung tăng hoặc bằng nhau.
- Ngưỡng là số nguyên VND dương, tối đa 15 chữ số; áp dụng khi lương **lớn hơn** ngưỡng.
- Mỗi cấp chọn đúng một approverUserId hoặc approverRole; không lặp lại cùng người/cùng vai trò.
- Người duyệt phải tồn tại, được phép truy cập và có quyền ghi REQUISITIONS (ALL hoặc SCOPED).
  Vai trò phải là vai trò nội bộ có quyền này; CANDIDATE và INTERVIEWER mặc định không hợp lệ.
- Vai trò trong cấu hình là nhóm người duyệt, chưa giải quyết thành một tài khoản cụ thể.
  Tài khoản cụ thể có thể cùng vai trò với cấp khác; hệ thống chưa tự suy đoán quan hệ này.
- Trường JSON ngoài hợp đồng bị từ chối. Không nhận createdBy/createdAt/version từ client.

Lưu thành công trả `{id,departmentId,version,name,createdBy,createdAt,steps}`.
Toàn bộ thay đổi nằm trong một transaction; cấu hình sai không tạo phiên bản dở dang.
Phiên bản mới trở thành hiện hành ngay, phiên bản cũ vẫn đọc được. PostgreSQL chặn sửa/xóa
phiên bản đã công bố hoặc thêm/sửa/xóa cấp của phiên bản đó.

## Xem thử

```json
{"departmentId":"<UUID phòng ban>","proposedSalary":20000001}
```

proposedSalary là số nguyên VND không âm tối đa 15 chữ số. Client dùng cận trên của dải lương
đề xuất theo quy ước kế hoạch. Với cấu hình ví dụ, mức 20.000.000 chọn cấp 1;
20.000.001 chọn cấp 1 và 2; 50.000.001 chọn cả ba. Response giữ ID/phiên bản, chỉ trả các steps khớp.
Phòng ban ngừng áp dụng không được lưu cấu hình mới hoặc xem thử; vẫn đọc được lịch sử.

Việc gắn chuỗi duyệt vào yêu cầu tuyển dụng và lưu bản chụp cho yêu cầu đang chạy nằm ngoài
phạm vi API này. API xem thử không tạo yêu cầu, không chọn người từ vai trò và không tạo bản
chụp phiên xử lý.

## Lỗi và database

| HTTP | code | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Cấu hình, bộ lọc, phòng ban/người/vai trò duyệt không hợp lệ; lỗi cấu hình có fieldErrors|
|400|INVALID_JSON|JSON hoặc trường ngoài hợp đồng không hợp lệ|
|401|UNAUTHORIZED / SESSION_INVALID|Token/phiên không hợp lệ hoặc tài khoản không được truy cập|
|403|FORBIDDEN|Thiếu vai trò quản trị hoặc quyền hiện hành|
|404|APPROVAL_FLOW_NOT_FOUND|Không có cấu hình hoặc phiên bản được yêu cầu|
|409|APPROVAL_FLOW_EXISTS|Phòng ban đã có cấu hình|
|409|APPROVAL_VERSION_CONFLICT|Phiên bản đang sửa đã cũ|

V7 thêm approval_flows, approval_flow_versions và approval_flow_steps; giữ nguyên dữ liệu cũ.
Khóa ghi theo tài khoản (UUID tăng dần), phiên người gọi, rồi phòng ban; các lần lưu cùng phòng ban
được tuần tự hóa. Khóa ngoại từ phiên bản giữ người tạo và đối tượng duyệt để lịch sử còn tham chiếu.
Kiểm thử sử dụng PostgreSQL tạm riêng; áp dụng migration lên database làm việc khi triển khai ứng dụng.
