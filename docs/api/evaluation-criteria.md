# API tiêu chí đánh giá theo chức danh

Phạm vi TKNHTTDNB1-215 "Chuẩn bị dữ liệu khung năng lực cho phiếu đánh giá", story TKNHTTDNB1-25 (S2-06). Kết quả mong đợi: cung cấp tiêu chí và trọng số theo chức danh để Sprint 6 sinh phiếu đánh giá phỏng vấn. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ của API này (`POSITION_*`, `COMPETENCY_*`) dùng `Cache-Control: no-store`.

Tiêu chí không được lưu riêng cho chức danh. Chức danh chỉ trỏ tới một khung năng lực (cột `positions.competency_framework_id`, gán bằng `PUT /positions/{id}/competency-framework`, xem [API chức danh](positions.md#khung-năng-lực-của-chức-danh-task-214)); API này đọc tiêu chí của chính khung đó. Vì vậy các chức danh dùng chung một khung nhận cùng một bộ tiêu chí (cùng `id`), và HR sửa khung ([API khung năng lực](competency-frameworks.md)) thì lần đọc kế tiếp thấy ngay.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /positions/{id}/evaluation-criteria`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ (kể cả INTERVIEWER, vì người phỏng vấn chấm theo các tiêu chí này)|

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu: thu hồi `ORGANIZATION_READ_ALL` có hiệu lực ngay ở yêu cầu kế tiếp, kể cả với cùng access token. API chỉ đọc: `POST`, `PUT`, `DELETE` trên URL này đều bị từ chối 403 với mọi vai trò; muốn đổi tiêu chí thì sửa khung năng lực.

## Đọc tiêu chí đánh giá của một chức danh

`GET /positions/{id}/evaluation-criteria`, trong đó `{id}` là UUID của chức danh. Không có body hay tham số. Trả **200**:

```json
{
  "position": {
    "id": "00000000-0000-0000-0000-000000000003",
    "code": "DEV_JUNIOR",
    "name": "Lập trình viên",
    "level": "Junior",
    "active": true
  },
  "framework": {
    "id": "00000000-0000-0000-0000-000000000010",
    "code": "DEV_CORE",
    "name": "Năng lực lập trình viên"
  },
  "criteria": [
    {
      "id": "00000000-0000-0000-0000-000000000011",
      "name": "Kỹ năng lập trình",
      "description": "Viết mã đúng và dễ đọc",
      "weight": 33.33,
      "sortOrder": 1
    },
    {
      "id": "00000000-0000-0000-0000-000000000012",
      "name": "Thiết kế hệ thống",
      "description": null,
      "weight": 33.33,
      "sortOrder": 2
    },
    {
      "id": "00000000-0000-0000-0000-000000000013",
      "name": "Làm việc nhóm",
      "description": null,
      "weight": 33.34,
      "sortOrder": 3
    }
  ]
}
```

UUID trong ví dụ chỉ minh họa.

| Trường | Ý nghĩa |
|---|---|
|position|Chức danh được hỏi: `id`, `code`, `name`, `level`, `active`. **Không bao giờ có `salaryMin`/`salaryMax`**, kể cả với HR_MANAGER: người phỏng vấn cũng đọc API này. Dải lương xem ở `GET /positions/{id}` theo quyền `SALARY_RANGES_READ_ALL`|
|framework|Khung năng lực chức danh đang dùng: `id`, `code`, `name`|
|criteria|Các tiêu chí của khung, sắp theo `sortOrder` tăng dần (1, 2, 3...). Mỗi tiêu chí có `id`, `name`, `description` (`null` nếu không có), `weight`, `sortOrder` giống phần tử `criteria` của `GET /competency-frameworks/{id}`|

Quy tắc:

- `weight` là phần trăm với đúng 2 chữ số thập phân như database lưu (`NUMERIC(5,2)`), ví dụ `33.33`, `100.00`. Chỉ khung hoàn chỉnh (`ACTIVE`) mới được gán cho chức danh, và khung `ACTIVE` luôn có tổng trọng số đúng 100 (task 213, 214), nên tổng `weight` trong `criteria` luôn là 100.00 và luôn có ít nhất một tiêu chí.
- `id` của tiêu chí giữ nguyên khi HR sửa khung mà gửi lại `id` đó (xem [PUT thay thế danh sách tiêu chí](competency-frameworks.md#put-thay-thế-danh-sách-tiêu-chí-như-thế-nào)). Phiếu đánh giá ở Sprint 6 nên tham chiếu tiêu chí theo `id`.
- **Quyết định tạm thời của task 215:** chức danh đã ngừng áp dụng (`active=false`) vẫn trả tiêu chí, với `position.active = false`. Lý do: các vòng phỏng vấn đang diễn ra của chức danh đó vẫn cần phiếu để chấm; việc có cho tạo phiếu mới cho chức danh ngừng áp dụng hay không do module phiếu đánh giá (Sprint 6) quyết định dựa trên trường này. Khác với dải lương chuẩn (task 206), nơi chức danh ngừng áp dụng trả `POSITION_INACTIVE`. Cần BA/PO xác nhận.
- Lần đọc dùng một snapshot nhất quán (`REPEATABLE_READ`, chỉ đọc), nên chức danh, khung và tiêu chí luôn khớp nhau. API không chờ khi HR đang sửa dở khung: nó trả bản đã commit gần nhất.

## Thứ tự kiểm tra và lỗi

1. Token và quyền: thiếu hoặc sai token trả 401; thiếu `ORGANIZATION_READ_ALL` trả 403. Service kiểm lại quyền này, không chỉ dựa vào `SecurityConfiguration`.
2. `{id}` phải là UUID (400 `VALIDATION_ERROR`).
3. Chức danh phải tồn tại (404 `POSITION_NOT_FOUND`).
4. Chức danh phải đã được gán khung (409 `POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED`).
5. Khung phải đang `ACTIVE` (409 `COMPETENCY_FRAMEWORK_NOT_ACTIVE`). Qua API không thể xảy ra (không gán được khung `DRAFT`, khung `ACTIVE` không quay lại `DRAFT`); bước này chặn trường hợp dữ liệu bị sửa trực tiếp bằng SQL, để phiếu đánh giá không bao giờ chấm theo trọng số có thể chưa đủ 100%.

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|`{id}` trên đường dẫn không phải UUID|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu `ORGANIZATION_READ_ALL`; hoặc gọi `POST`/`PUT`/`DELETE` trên URL này|
|404|POSITION_NOT_FOUND|Không tìm thấy chức danh|
|409|POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED|Chức danh chưa được gán khung năng lực; HR_MANAGER hoặc ADMIN gán bằng `PUT /positions/{id}/competency-framework`|
|409|COMPETENCY_FRAMEWORK_NOT_ACTIVE|Khung của chức danh không ở trạng thái `ACTIVE` (chỉ khi dữ liệu bị sửa ngoài API)|

Ví dụ chức danh chưa có khung:

```json
{
  "code": "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED",
  "message": "Chức danh chưa được gán khung năng lực nên chưa có tiêu chí đánh giá.",
  "fieldErrors": {}
}
```

Gợi ý cho frontend: gặp 409 `POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED` thì hiển thị "chức danh chưa có khung năng lực" (và nút gán khung nếu người dùng có `ORGANIZATION_WRITE_ALL`), không coi là lỗi hệ thống.

## Dịch vụ nội bộ cho phiếu đánh giá (Sprint 6)

Ngoài API, backend có `EvaluationCriteriaService.forPosition(positionId)` (gói `vn.ttcs.recruitment.competency`) để module phiếu đánh giá phỏng vấn gọi trực tiếp khi sinh phiếu.

| Phương thức Java | Kết quả |
|---|---|
|`forPosition(positionId)`|`EvaluationCriteria(position, framework, criteria)`, cùng nội dung với response API ở trên; `weight` là `BigDecimal` có đúng 2 chữ số thập phân|

Quy tắc:

- **Không kiểm quyền người gọi:** dịch vụ không nhận token và không phải API. Module gọi phải tự kiểm quyền của mình trước (ví dụ quyền `EVALUATIONS_*`). Kết quả không chứa dải lương nên trả lại cho người dùng không làm lộ lương.
- Lỗi giống bảng trên, dưới dạng `ApiException`: 404 `POSITION_NOT_FOUND`, 409 `POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED`, 409 `COMPETENCY_FRAMEWORK_NOT_ACTIVE`. Truyền `null` là lỗi lập trình (`NullPointerException`).
- **Đồng thời:** khi được gọi trong transaction ghi của module gọi, dịch vụ đọc dòng chức danh rồi dòng khung bằng `SELECT ... FOR SHARE` và giữ khóa chia sẻ đến khi transaction đó commit hoặc rollback. Trong thời gian này, HR đổi hoặc bỏ khung của chức danh (`PUT`/`DELETE /positions/{id}/competency-framework`) và sửa khung (`PUT /competency-frameworks/{id}`) phải chờ, nên tiêu chí module gọi vừa đọc (ví dụ lưu vào phiếu theo `id`) vẫn đúng lúc commit. Nhiều lần đọc cùng chức danh không chờ nhau. Ngược lại, nếu HR đang sửa dở khung, dịch vụ chờ HR commit rồi trả tiêu chí mới. Thứ tự khóa (chức danh trước, khung sau) giống API gán khung, nên hai bên không khóa chéo nhau (deadlock).
- Điều trên chỉ đúng khi transaction ghi của module gọi dùng mức cô lập mặc định READ COMMITTED. Với `REPEATABLE_READ` hoặc `SERIALIZABLE`, nếu HR đổi dòng chức danh/khung sau khi transaction của module gọi đã chụp snapshot, PostgreSQL từ chối `FOR SHARE` bằng lỗi serialization (SQLSTATE 40001); lỗi này chưa được xử lý nên sẽ thành 500 (giống `SalaryBandService`). Module gọi nên lấy tiêu chí sau khi đã khóa tài khoản và phiên của người gọi, cùng thứ tự với các service ghi hiện có.
- Trong transaction chỉ đọc (`readOnly`), PostgreSQL không cho `FOR SHARE`, nên dịch vụ đọc không khóa; transaction chỉ đọc không lưu gì dựa trên kết quả. Gọi ngoài transaction thì dịch vụ tự mở một transaction ngắn, nên chức danh, khung và tiêu chí vẫn được đọc cùng nhau.

## Database và phạm vi

Task 215 không thêm migration, mã quyền hay dòng cấp quyền: chỉ đọc bảng `positions` (V7, cột `competency_framework_id` của V8), `competency_frameworks` và `competency_criteria` (V8), dùng quyền ORGANIZATION của V3. Không cần sửa `.env`. Chưa có: bảng và API phiếu đánh giá (Sprint 6), API câu hỏi phỏng vấn theo tiêu chí (task 221–223; task 220 mới tạo bảng `interview_questions`, xem [tài liệu database](../database/README.md)).
