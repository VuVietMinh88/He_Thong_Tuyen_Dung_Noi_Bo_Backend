# Checklist tiếp nhận nhân viên — TKNHTTDNB1-520–523

Backend cung cấp mẫu theo phòng ban và checklist riêng cho hồ sơ đã tuyển dụng. Đây là công việc quản lý tiếp nhận; API không tự cấp tài khoản, mua thiết bị hoặc gửi email.

## Quyền

Chỉ ADMIN và HR_MANAGER dùng các API này. Mẫu cần ORGANIZATION_READ_ALL; tạo/sửa cần thêm ORGANIZATION_WRITE_ALL. Checklist cần OFFERS_READ_ALL và CANDIDATES_READ_ALL; tạo/sửa cần thêm OFFERS_WRITE_ALL. Các quyền được đọc lại từ database sau khi chờ khóa. Người phụ trách một mục không tự có quyền xem hồ sơ ứng viên.

Tất cả API dùng Bearer access token, trả Cache-Control: no-store. Request JSON có field không được hỗ trợ sẽ bị từ chối.

Để điền bộ chọn trên frontend:

- Phòng ban: `GET /api/v1/departments?active=true&page=0&size=20`.
- Người phụ trách: `GET /api/v1/accounts?status=ACTIVE&page=0&size=20`; thêm `q` để tìm kiếm. Hiển thị `fullName`/`email` và gửi `id` của tài khoản được chọn vào `assigneeId`.

API danh sách tài khoản cần `USER_ADMIN_READ_ALL` (HR_MANAGER và ADMIN có sẵn theo quyền mặc định). Nếu quyền này bị thu hồi, bộ chọn tài khoản có thể trả 403 dù người dùng vẫn có quyền onboarding. Người phụ trách có thể ở phòng ban khác, ví dụ IT chuẩn bị tài khoản cho nhân viên mới của phòng Kinh doanh; không bắt buộc lọc theo phòng ban của checklist.

## Mẫu theo phòng ban

- GET /api/v1/onboarding/templates?departmentId={uuid}&page=0&size=20: danh sách; departmentId tùy chọn, size 1–100.
- GET /api/v1/onboarding/templates/{id}: chi tiết.
- POST /api/v1/onboarding/templates: tạo mới (201).
- PUT /api/v1/onboarding/templates/{id}: cập nhật toàn bộ mẫu (200).

Ví dụ tạo mẫu:

```json
{
  "departmentId": "UUID_PHONG_BAN",
  "name": "Tiếp nhận phòng Công nghệ",
  "active": true,
  "items": [
    {"title": "Chuẩn bị tài khoản", "assigneeId": "UUID_NGUOI_PHU_TRACH", "dueOffsetDays": -1},
    {"title": "Bàn giao máy tính", "assigneeId": "UUID_NGUOI_PHU_TRACH", "dueOffsetDays": 0},
    {"title": "Bố trí chỗ ngồi", "assigneeId": "UUID_NGUOI_PHU_TRACH", "dueOffsetDays": 0},
    {"title": "Hoàn thiện hồ sơ", "assigneeId": "UUID_NGUOI_PHU_TRACH", "dueOffsetDays": 3}
  ]
}
```

Thay chuỗi UUID minh họa bằng ID thực. Khi sửa, bỏ departmentId và thêm expectedVersion từ response gần nhất. Phòng ban của mẫu không đổi; active=false ngừng dùng mẫu cho checklist mới. Mẫu có 1–100 mục, tên tối đa 120 ký tự, tiêu đề mục tối đa 200. Người phụ trách phải là tài khoản đang hoạt động. dueOffsetDays là số nguyên từ -30 đến 365 tính từ ngày bắt đầu.

Response mẫu: id, departmentId, name, active, version, items (id/title/assigneeId/dueOffsetDays/position), createdAt, updatedAt. position bắt đầu từ 0. Danh sách bọc trong items/page/size/totalElements/totalPages.

## Khởi tạo checklist

POST /api/v1/applications/{applicationId}/onboarding:

```json
{
  "templateId": "UUID_MAU",
  "expectedTemplateVersion": 0,
  "startDate": "2026-10-20"
}
```

Hồ sơ phải ở HIRED. Mẫu đang hoạt động và thuộc phòng ban của yêu cầu tuyển dụng. Yêu cầu đã đóng vẫn có thể tiếp nhận người đã tuyển. Mỗi hồ sơ có một checklist; gọi tạo lần nữa trả 409.

Backend sao chép tên mẫu, phiên bản và từng công việc. Hạn bằng startDate cộng dueOffsetDays. Những lần sửa mẫu sau không thay đổi checklist đã tạo.

GET cùng đường dẫn trả checklist với:

- id, applicationId, departmentId, templateId, templateVersion, templateName, startDate;
- status (PENDING/COMPLETED), version, totalItems, completedItems, progressPercent;
- completedAt/completedBy khi hoàn tất; createdAt/updatedAt;
- items gồm id/title/assigneeId/dueDate/status/position/completedAt/completedBy.

## Cập nhật công việc

PATCH /api/v1/applications/{applicationId}/onboarding/items/{itemId}:

```json
{
  "expectedVersion": 0,
  "title": "Bàn giao máy tính",
  "assigneeId": "UUID_NGUOI_PHU_TRACH",
  "dueDate": "2026-10-20",
  "status": "DONE"
}
```

expectedVersion là version của cả checklist. Gửi đủ title/assigneeId/dueDate/status; response 200 trả toàn bộ checklist mới. Hạn trong khoảng startDate-30 đến startDate+365; ngày thuộc năm 2000–2100.

Trạng thái mục: PENDING, IN_PROGRESS, DONE. Khi checklist còn mở có thể sửa người phụ trách/hạn hoặc đưa mục DONE về trạng thái đang làm; metadata hoàn tất mục được xóa khi mở lại. Tiến độ làm tròn xuống: 1/3=33%, 2/3=66%, 3/3=100%.

Khi mục cuối cùng hoàn thành, backend ghi đồng thời:

1. Checklist COMPLETED cùng người/thời điểm xác nhận.
2. Hồ sơ HIRED → ONBOARDED.
3. Sự kiện lịch sử do nhân viên thực hiện.

Checklist hoàn tất không sửa/mở lại. API chuyển giai đoạn thông thường không thể bỏ qua checklist để chuyển ONBOARDED. Public tracking vẫn hiển thị trạng thái tổng quát COMPLETED; bảng pipeline nội bộ có cột ONBOARDED.

## Xử lý lỗi trên frontend

| HTTP | Ý nghĩa |
|---|---|
|400|Thiếu/sai field, sai kiểu, ngày/độ dài/số mục vượt giới hạn.|
|401|Token hoặc phiên không còn hợp lệ; đăng nhập lại.|
|403|Thiếu vai trò hoặc quyền; không cho cập nhật.|
|404|Không tìm thấy mẫu/checklist/mục/hồ sơ.|
|409|Version cũ, đã có checklist, checklist đã hoàn tất, mẫu/phòng ban/người phụ trách không hợp lệ; tải lại trước khi sửa.|
|503 ONBOARDING_BUSY|Đang tranh chấp khóa/timeout; tải lại rồi thử lại.|

Mã 409 thường gặp: ONBOARDING_VERSION_CHANGED, ONBOARDING_ALREADY_EXISTS, ONBOARDING_ALREADY_COMPLETED, ONBOARDING_TEMPLATE_INACTIVE, ONBOARDING_DEPARTMENT_MISMATCH, ONBOARDING_ASSIGNEE_INACTIVE, ONBOARDING_APPLICATION_INELIGIBLE. Không tự retry một bản cập nhật cũ với version mới vì có thể ghi đè công việc người khác.

## Database và vận hành

V64 thêm 4 bảng onboarding và mở rộng stage ONBOARDED trên cả hai lineage Flyway. Không đổi migration đã áp dụng. Database bảo vệ snapshot, số lượng mục, metadata hoàn tất và sự nhất quán checklist/hồ sơ tại cuối transaction. Mã API serialize cập nhật bằng khóa và expectedVersion.

Chạy thử trên database local/tạm theo cấu hình dự án. Không tự đảo V64 trên dữ liệu có ONBOARDED; khôi phục từ backup hoặc dùng migration tiến tới sau khi đánh giá dữ liệu. Bản triển khai này không thay đổi database thật.
