# API định biên và ngân sách nhân sự

Phạm vi TKNHTTDNB1-272 (lưu định biên và ngân sách theo phòng ban, năm) và TKNHTTDNB1-273 (API quản lý và tra cứu định biên), story TKNHTTDNB1-33 (S3-04). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ của các API này dùng `Cache-Control: no-store`.

Tiêu chí của story:

- Khai báo chỉ tiêu headcount và ngân sách lương theo phòng ban, theo năm (272, 273).
- Yêu cầu tuyển dụng mới hiển thị số headcount còn lại của phòng ban (273: `GET /headcount-plans/remaining`).
- Vượt chỉ tiêu là cảnh báo chặn (task 274: lưu yêu cầu tuyển dụng vượt định biên trả 409, xem [API yêu cầu tuyển dụng](requisitions.md#kiểm-tra-định-biên-task-274)), cần Trưởng phòng Nhân sự xác nhận ghi đè kèm lý do (task 275).

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /headcount-plans`|`HEADCOUNT_PLANS_READ_ALL`|HR_MANAGER|
|`GET /headcount-plans/{id}`|`HEADCOUNT_PLANS_READ_ALL`|HR_MANAGER|
|`POST /headcount-plans`|`HEADCOUNT_PLANS_WRITE_ALL`|HR_MANAGER|
|`PUT /headcount-plans/{id}`|`HEADCOUNT_PLANS_WRITE_ALL`|HR_MANAGER|
|`GET /headcount-plans/remaining`|`HEADCOUNT_PLANS_READ_ALL` **hoặc** `REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|HR_MANAGER (có ngân sách); ADMIN (mọi phòng ban, không có ngân sách); HIRING_MANAGER, RECRUITER, APPROVER (phòng ban mình phụ trách, không có ngân sách)|

ADMIN không có `HEADCOUNT_PLANS_*` (giống dải lương, câu hỏi 13 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)) nên nhận 403 ở bốn API đầu. Khi ghi, backend khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền trước khi ghi, giống các API ghi khác. Quyền đọc và quyền ghi được kiểm riêng.

## Cách tính số đã dùng

Số đã dùng **không** lưu trong bảng định biên mà được cộng từ các yêu cầu tuyển dụng mỗi lần đọc, nên luôn khớp với yêu cầu thật:

- Chỉ tính yêu cầu của **đúng** phòng ban đó. Phòng ban con có chỉ tiêu riêng, không cộng vào phòng ban cha.
- Chỉ tính yêu cầu còn giữ chỗ. Hiện chỉ có trạng thái `DRAFT`, và bản nháp giữ chỗ ngay khi được lưu, vì task 274 kiểm chỉ tiêu ở lúc tạo/sửa yêu cầu. Khi có luồng duyệt và đóng yêu cầu, trạng thái bị từ chối hay hủy sẽ trả lại chỗ.
- Năm của yêu cầu là năm của **ngày cần người** (`neededBy`). Bản nháp chưa có ngày thì tính vào năm tạo, theo múi giờ nghiệp vụ (mặc định giờ Việt Nam): nháp tạo lúc 00:30 ngày 1/1/2027 giờ Việt Nam tính vào 2027 dù giờ UTC vẫn là 2026.
- `headcountUsed` = tổng `headcount`.
- `salaryBudgetUsed` = tổng của số người × lương đề xuất tối đa × 12 tháng. Thiếu mức tối đa thì lấy mức tối thiểu; chưa đề xuất lương thì yêu cầu đó tính 0 đồng vào ngân sách nhưng vẫn tính số người. Ngân sách lương là số tiền **cả năm**, còn lương đề xuất là lương tháng.
- Phòng ban chưa có định biên cho một năm thì năm đó **không bị giới hạn**.

Các quy tắc trên là giả định của backend, chờ BA/PO xác nhận (câu hỏi 13 trong ma trận quyền).

## Khai báo định biên

`POST /headcount-plans` tạo định biên cho một phòng ban trong một năm, trả **201**.

```json
{
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "year": 2026,
  "headcountLimit": 5,
  "salaryBudget": 5000000000
}
```

| Trường | Quy tắc |
|---|---|
|departmentId|Bắt buộc. UUID phòng ban có trong `departments` và đang áp dụng|
|year|Bắt buộc. Số nguyên JSON từ 2000 đến 2100|
|headcountLimit|Bắt buộc. Số nguyên JSON từ 0 đến 99.999. `0` nghĩa là năm đó không tuyển thêm|
|salaryBudget|Không bắt buộc. Ngân sách lương cả năm, số nguyên đồng VND từ 0 đến 1.000.000.000.000.000 (1 triệu tỷ). Không gửi hoặc `null` nghĩa là không giới hạn ngân sách|

Số phải là số nguyên JSON: `2026.5`, `5.0`, `1e3` hoặc chuỗi `"5"` bị từ chối với 400 `INVALID_JSON`, không bị làm tròn. Gửi thêm trường do server quyết định (`id`, `headcountUsed`, `updatedBy`...) cũng là `INVALID_JSON`.

Lỗi theo thứ tự kiểm:

| HTTP | `code` | Khi nào |
|---|---|---|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Thiếu token, token hết hạn, phiên đã đăng xuất hoặc tài khoản bị khóa|
|403|`FORBIDDEN`|Thiếu `HEADCOUNT_PLANS_WRITE_ALL`|
|400|`INVALID_JSON`|Body không phải JSON hợp lệ, số không phải số nguyên JSON, hoặc có trường không được phép|
|400|`VALIDATION_ERROR`|Thiếu trường bắt buộc hoặc ngoài giới hạn; `fieldErrors` nêu từng trường|
|400|`INVALID_HEADCOUNT_PLAN_DEPARTMENT`|Phòng ban không tồn tại; `fieldErrors.departmentId`|
|400|`HEADCOUNT_PLAN_DEPARTMENT_INACTIVE`|Phòng ban đã ngừng áp dụng; `fieldErrors.departmentId`|
|409|`HEADCOUNT_PLAN_EXISTS`|Phòng ban đã có định biên cho năm đó. Hãy sửa định biên đang có|

Hai HR cùng tạo định biên cho một phòng ban, một năm cùng lúc: một người nhận 201, người kia nhận 409 (ràng buộc `headcount_plans_department_year_key` của V14 là lớp chặn cuối). Phòng ban được khóa `SELECT ... FOR NO KEY UPDATE`: lần tạo định biên chờ các lần lưu yêu cầu tuyển dụng đang chạy của phòng ban đó (chúng giữ phòng ban `FOR SHARE`) commit xong rồi mới thêm định biên, và lần lưu bắt đầu sau phải chờ định biên được tạo xong rồi mới đếm. Nhờ vậy không có nháp nào "lọt" qua lúc định biên vừa được tạo. Việc xóa phòng ban (task 197) cũng chờ lần tạo này xong.

## Sửa định biên

`PUT /headcount-plans/{id}` thay **cả hai** số, trả **200**:

```json
{ "headcountLimit": 4, "salaryBudget": 4000000000 }
```

Cùng quy tắc với `headcountLimit` và `salaryBudget` ở trên. Không gửi `salaryBudget` hoặc gửi `null` là bỏ giới hạn ngân sách. Phòng ban và năm **không đổi**: gửi `departmentId` hoặc `year` trả 400 `INVALID_JSON`; định biên cho phòng ban hoặc năm khác là một định biên khác (`POST`). UUID không tồn tại trả 404 `HEADCOUNT_PLAN_NOT_FOUND`. `updatedAt` và `updatedBy` đổi theo người sửa, `createdAt` giữ nguyên.

Được phép hạ chỉ tiêu xuống dưới số đã dùng: các yêu cầu đã lưu giữ nguyên, `headcountRemaining` và `salaryBudgetRemaining` thành số âm. Từ task 274, chỉ yêu cầu mới hoặc yêu cầu tăng thêm mới bị chặn.

Backend khóa dòng định biên `FOR UPDATE` trước khi sửa, nên hai lần sửa cùng một định biên chạy lần lượt.

## Xem định biên

`GET /headcount-plans/{id}` và mỗi phần tử của danh sách:

```json
{
  "id": "6f0c1f9e-8f3e-4b67-9a39-2c1d3f4e5a6b",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "departmentCode": "IT",
  "departmentName": "Phòng Công nghệ thông tin",
  "year": 2026,
  "headcountLimit": 5,
  "headcountUsed": 2,
  "headcountRemaining": 3,
  "salaryBudget": 5000000000,
  "salaryBudgetUsed": 600000000,
  "salaryBudgetRemaining": 4400000000,
  "createdAt": "2026-10-10T03:00:00.123456Z",
  "updatedAt": "2026-10-10T03:00:00.123456Z",
  "updatedBy": "00000000-0000-0000-0000-000000000001"
}
```

`salaryBudget` và `salaryBudgetRemaining` là `null` khi năm đó không giới hạn ngân sách. Số còn lại âm nghĩa là đã vượt (HR đã xác nhận ghi đè, hoặc đã hạ chỉ tiêu sau khi yêu cầu được lưu). `POST` và `PUT` trả cùng dạng này.

`GET /headcount-plans?year=2026&departmentId=...&page=0&size=20` trả `{items, page, size, totalElements, totalPages}`. `year` và `departmentId` là bộ lọc tùy chọn. Danh sách xếp năm mới nhất trước, rồi theo mã phòng ban. `page` từ 0, `size` từ 1 đến 100; trang, số lượng hoặc năm ngoài khoảng 2000–2100 trả 400 `VALIDATION_ERROR`. Số đã dùng của mọi phần tử được đọc trong cùng một snapshot (`REPEATABLE READ`).

## Số còn lại cho biểu mẫu yêu cầu tuyển dụng

`GET /headcount-plans/remaining?departmentId=...&year=2026` trả phần còn lại của một phòng ban trong một năm, dùng để hiển thị trên form tạo/sửa yêu cầu. `year` không bắt buộc, mặc định là năm hiện tại theo múi giờ nghiệp vụ; nên gửi năm của ngày cần người đang chọn trên form, vì yêu cầu được tính vào năm đó. Khi **sửa** một nháp: nháp chưa có ngày cần người được tính vào năm tạo nháp (năm của `createdAt` theo giờ Việt Nam), và `headcountUsed` đã gồm cả số người của chính nháp đó nếu nó cùng phòng ban, cùng năm; giao diện nên cộng lại số người của nháp khi hiển thị "còn lại cho nháp này".

Response cho HR_MANAGER:

```json
{
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "year": 2026,
  "planned": true,
  "headcountLimit": 5,
  "headcountUsed": 2,
  "headcountRemaining": 3,
  "salaryBudgetLimited": true,
  "salaryBudget": 5000000000,
  "salaryBudgetUsed": 600000000,
  "salaryBudgetRemaining": 4400000000
}
```

- Người gọi không có `HEADCOUNT_PLANS_READ_ALL` (Trưởng bộ phận, ADMIN...) **không nhận** bốn khóa `salaryBudgetLimited`, `salaryBudget`, `salaryBudgetUsed`, `salaryBudgetRemaining`, giống cách ẩn dải lương của chức danh. Giao diện phải xử lý khóa vắng mặt, không hiển thị `0`.
- `planned = false`: HR chưa khai báo định biên cho năm đó, không có giới hạn; `headcountLimit` và `headcountRemaining` là `null`, `headcountUsed` vẫn có.
- Với HR, `salaryBudgetLimited = false` nghĩa là năm đó không giới hạn ngân sách (chưa có định biên hoặc định biên không có ngân sách): `salaryBudget`, `salaryBudgetRemaining` vắng mặt, `salaryBudgetUsed` vẫn có.
- `headcountRemaining` âm nghĩa là phòng ban đã vượt chỉ tiêu.

Thứ tự kiểm: quyền (401/403), tham số (`departmentId` thiếu hoặc không phải UUID, `year` ngoài 2000–2100: 400 `VALIDATION_ERROR`), phòng ban tồn tại (404 `DEPARTMENT_NOT_FOUND`), rồi phạm vi. Người chỉ có `REQUISITIONS_READ_SCOPED` chỉ hỏi được phòng ban mình phụ trách, kể cả phòng ban con, giống phạm vi xem yêu cầu tuyển dụng; phòng ban khác trả 403 `FORBIDDEN`. Người có `REQUISITIONS_READ_ALL` hoặc `HEADCOUNT_PLANS_READ_ALL` hỏi được mọi phòng ban.

## Thử nhanh bằng PowerShell

Đăng nhập bằng tài khoản HR_MANAGER rồi:

```powershell
$h = @{ Authorization = "Bearer $token" }
$body = @{ departmentId = $departmentId; year = 2026; headcountLimit = 5; salaryBudget = 5000000000 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/headcount-plans -Headers $h -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/headcount-plans/remaining?departmentId=$departmentId&year=2026" -Headers $h
```

Kết quả mong đợi: lần đầu nhận định biên với `headcountRemaining = 5` (nếu phòng ban chưa có yêu cầu nào trong 2026), gọi `POST` lần hai nhận 409 `HEADCOUNT_PLAN_EXISTS`.
