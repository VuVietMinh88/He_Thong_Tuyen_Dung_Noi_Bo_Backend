# API danh mục chức danh

Phạm vi TKNHTTDNB1-203 (API) và TKNHTTDNB1-204 (kiểm tra dữ liệu, giới hạn dải lương), story TKNHTTDNB1-24. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi `POSITION_*` dùng `Cache-Control: no-store`.

Đọc cần `ORGANIZATION_READ_ALL`; ghi cần `ORGANIZATION_WRITE_ALL`. Ma trận hiện tại cấp quyền đọc cho cả sáu vai trò nội bộ, ghi cho ADMIN và HR_MANAGER. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu và kiểm lại phiên/quyền sau khi khóa tài khoản người gọi khi ghi.

**Tạm thời:** mọi người có `ORGANIZATION_READ_ALL` đều thấy `salaryMin`/`salaryMax`. Tiêu chí "chỉ Trưởng phòng Nhân sự xem được dải lương" thuộc task 205, chưa có trong task này.

## Tạo và sửa

`POST /positions` tạo chức danh, trả **201**. `PUT /positions/{id}` thay thế toàn bộ trường của chức danh có UUID tương ứng, trả **200**.

```json
{
  "code": "DEV_JUNIOR",
  "name": "Lập trình viên",
  "level": "Junior",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "active": true
}
```

| Trường | Quy tắc |
|---|---|
|code|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự; duy nhất và phân biệt hoa/thường (`HR` khác `hr`), giống mã phòng ban|
|name|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự|
|level|Cấp bậc dạng chữ tự do, ví dụ `Junior`; bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự|
|salaryMin|Lương tối thiểu, số nguyên đồng VND, bắt buộc, từ 0 đến 1.000.000.000.000|
|salaryMax|Lương tối đa, số nguyên đồng VND, bắt buộc, từ 0 đến 1.000.000.000.000, không nhỏ hơn `salaryMin` (được phép bằng)|
|active|Boolean bắt buộc; false nghĩa là ngừng áp dụng|

Code/name/level được bỏ khoảng trắng đầu/cuối (kể cả tab, xuống dòng) trước khi kiểm tra độ dài, nên `"  DEV  "` được lưu là `DEV` và khoảng trắng thừa không bị tính vào giới hạn.

Lương là số nguyên đồng (database `BIGINT`, Java `long`), có thể vượt 2.147.483.647. Phải gửi số nguyên JSON như `15000000`; số có phần thập phân (`1.9`, kể cả `1.0`), dạng mũ (`1e3`), chuỗi (`"15000000"`) hoặc số vượt giới hạn `long` bị từ chối với `INVALID_JSON`, không bị cắt phần lẻ hay tự đổi kiểu. Mỗi mức lương phải nằm trong 0 đến 1.000.000.000.000 đồng (1.000 tỷ): trần này cao hơn mọi mức lương thực tế, chỉ để chặn lỗi gõ thừa số 0 và giữ các phép tính offer về sau xa giới hạn `long`. Trần chỉ được kiểm tra ở API; V7 không có CHECK cho trần nên dữ liệu ghi thẳng bằng SQL không bị chặn.

Thứ tự kiểm tra: trước hết từng trường riêng lẻ (bắt buộc, độ dài, lương không âm, không vượt trần), mọi trường sai được trả cùng lúc trong `fieldErrors` với mã `VALIDATION_ERROR`. Chỉ khi từng trường đều hợp lệ, backend mới so hai mức lương: `salaryMin` lớn hơn `salaryMax` trả `POSITION_SALARY_RANGE_INVALID` kèm lỗi ở trường `salaryMax`. `salaryMin` bằng `salaryMax` là dải lương cố định, hợp lệ. Sau đó mới kiểm tra chức danh tồn tại (PUT) và mã trùng. Ràng buộc CHECK của V7 (`0 <= salary_min <= salary_max`) vẫn là lớp chặn cuối trong database.

PUT phải gửi đủ sáu trường; nên GET chi tiết trước rồi gửi lại các giá trị muốn giữ. Giữ nguyên mã của chính chức danh đang sửa không bị coi là trùng. Trường ngoài hợp đồng như `id`, `createdAt` bị từ chối với HTTP 400 `INVALID_JSON`.

Response của tạo/sửa và `GET /positions/{id}`:

```json
{
  "id": "00000000-0000-0000-0000-000000000003",
  "code": "DEV_JUNIOR",
  "name": "Lập trình viên",
  "level": "Junior",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "active": true,
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa. `createdAt` giữ nguyên khi sửa; `updatedAt` là thời điểm ghi gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu).

## Danh sách

`GET /positions?q=dev&active=true&page=0&size=20`

| Tham số | Ý nghĩa |
|---|---|
|q|Tìm một phần code/name, không phân biệt hoa/thường, tối đa 255 ký tự; %, _ và ! được hiểu là ký tự thật; không tìm theo level|
|active|true/false; bỏ qua để lấy cả hai trạng thái|
|page|Từ 0, mặc định 0|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`; mỗi item có cấu trúc chi tiết ở trên. Sắp xếp theo code rồi UUID để phân trang ổn định; trang ngoài phạm vi có items rỗng.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, lương âm hoặc vượt 1.000.000.000.000, UUID, page/size, active hoặc q quá dài; lỗi theo trường nằm trong `fieldErrors`|
|400|INVALID_JSON|JSON sai, lương không phải số nguyên JSON trong giới hạn `long`, hoặc có trường ngoài hợp đồng|
|400|POSITION_SALARY_RANGE_INVALID|`salaryMin` lớn hơn `salaryMax`; `fieldErrors.salaryMax` có lời nhắn để form hiển thị; dữ liệu không thay đổi|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu quyền tổ chức tương ứng|
|404|POSITION_NOT_FOUND|Không tìm thấy chức danh đích|
|409|POSITION_CODE_EXISTS|Mã đã được chức danh khác dùng, kể cả khi hai yêu cầu ghi cùng mã đồng thời|

Ví dụ lỗi dải lương ngược:

```json
{
  "code": "POSITION_SALARY_RANGE_INVALID",
  "message": "Lương tối thiểu không được lớn hơn lương tối đa.",
  "fieldErrors": {
    "salaryMax": "Lương tối đa phải lớn hơn hoặc bằng lương tối thiểu."
  }
}
```

Người không có quyền ghi luôn nhận 403, kể cả khi body sai, vì quyền được kiểm tra trước dữ liệu.

## Database và phạm vi

Dùng bảng `positions` của V7 và quyền ORGANIZATION của V3; task 203 và 204 không thêm migration hoặc thay `.env`. Không có DELETE: muốn ngừng dùng thì PUT `active=false`. Chưa có liên kết chức danh với phòng ban, yêu cầu tuyển dụng hay offer; kiểm tra hạn mức offer theo dải lương sẽ làm ở các task sau.
