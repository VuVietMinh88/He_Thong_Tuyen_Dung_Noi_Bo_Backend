# API yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-244 (tạo và lưu nháp), story TKNHTTDNB1-29 (S2-10). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ `REQUISITION_*`, `INVALID_REQUISITION_*` dùng `Cache-Control: no-store`.

Hiện chỉ có `POST /requisitions`. Lấy và sửa bản nháp (task 245), kiểm tra chi tiết trường bắt buộc và lý do tuyển (246), giải trình khi dải lương đề xuất ngoài chuẩn (247), ngày cần người không ở quá khứ (248) và giới hạn phòng ban của người tạo (249) là các task sau; story S2-10 chưa hoàn thành.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|

INTERVIEWER và tài khoản không có vai trò nhận 403 `FORBIDDEN`. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu, khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền trước khi ghi, giống API chức danh. Quyền đọc (`REQUISITIONS_READ_*`) không cho phép tạo.

**Phạm vi `SCOPED` chưa được áp dụng ở task 244.** Người có `REQUISITIONS_WRITE_SCOPED` hiện tạo được bản nháp cho **mọi** phòng ban. Task 249 sẽ giới hạn: người chỉ có `SCOPED` chỉ tạo/sửa yêu cầu của phòng ban mình phụ trách (`departments.manager_user_id` là người gọi), tính cả các phòng ban con trong cây; người có `ALL` vẫn thao tác mọi phòng ban. Theo cách hiểu này RECRUITER và APPROVER thường không phụ trách phòng ban nào nên sẽ không tạo được; cần BA/PO xác nhận (câu hỏi 2 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)). Khi đó yêu cầu ngoài phạm vi sẽ trả 403 `FORBIDDEN` theo quy ước chung.

## Tạo bản nháp

`POST /requisitions` tạo một yêu cầu tuyển dụng ở trạng thái `DRAFT`, người tạo là người gọi (lấy từ token), trả **201**. Mỗi lần gọi tạo một bản nháp mới, kể cả khi nội dung giống hệt bản đã có; chưa có kiểm tra trùng.

```json
{
  "positionId": "00000000-0000-0000-0000-000000000003",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "headcount": 2,
  "reason": "NEW_HEADCOUNT",
  "proposedSalaryMin": 15000000,
  "proposedSalaryMax": 25000000,
  "salaryJustification": null,
  "neededBy": "2026-12-31",
  "jobDescription": "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java."
}
```

| Trường | Quy tắc |
|---|---|
|positionId|Bắt buộc, UUID của chức danh có trong bảng `positions`|
|departmentId|Bắt buộc, UUID của phòng ban có trong bảng `departments`|
|headcount|Bắt buộc, số nguyên JSON lớn hơn 0 (tối đa 2.147.483.647, giới hạn của cột `INTEGER`)|
|reason|Bắt buộc: `REPLACEMENT` (tuyển thay thế) hoặc `NEW_HEADCOUNT` (tăng mới), đúng chữ hoa|
|proposedSalaryMin|Không bắt buộc. Lương đề xuất tối thiểu, số nguyên đồng VND từ 0 đến 1.000.000.000.000|
|proposedSalaryMax|Không bắt buộc. Lương đề xuất tối đa, cùng quy tắc; khi có cả hai mức thì không nhỏ hơn `proposedSalaryMin` (được phép bằng)|
|salaryJustification|Không bắt buộc, tối đa 2.000 ký tự, không chứa ký tự NUL|
|neededBy|Không bắt buộc. Ngày cần người dạng `yyyy-MM-dd`, không có giờ|
|jobDescription|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|
|candidateRequirements|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|

Bản nháp được lưu dù chưa viết xong: chỉ bốn trường đầu là bắt buộc. Trường không gửi, gửi `null`, hoặc văn bản rỗng/chỉ có khoảng trắng được lưu là `null` (V13 lưu phần chưa viết là `NULL`). Văn bản có nội dung được giữ nguyên như người dùng nhập, kể cả xuống dòng, thụt đầu dòng và khoảng trắng đầu/cuối. Có thể nhập một đầu của dải lương đề xuất. Lương phải là số nguyên JSON: `1.5`, `1e3` hoặc chuỗi `"15000000"` bị từ chối với `INVALID_JSON`, giống [API chức danh](positions.md). `headcount` cũng vậy: `1.5`, `0.9`, `2.0`, `1e1`, chuỗi `"2"` hoặc `true` bị từ chối với `INVALID_JSON`, không bị làm tròn thành `1`/`0` hay tự đổi thành số; số vượt 2.147.483.647 cũng là `INVALID_JSON`.

Ký tự NUL (mã 0, trong JSON viết là `\u0000`, đôi khi dính vào khi dán từ tệp khác) không lưu được vào cột `TEXT` của PostgreSQL, nên `salaryJustification`, `jobDescription`, `candidateRequirements` chứa ký tự này bị từ chối với `VALIDATION_ERROR` (ví dụ `fieldErrors.jobDescription` là "Mô tả công việc chứa ký tự không hợp lệ."). Mọi ký tự khác, kể cả tab và xuống dòng kiểu Windows `\r\n`, được giữ nguyên.

Trường ngoài hợp đồng, kể cả `id`, `status`, `createdBy`, `createdAt`, bị từ chối với 400 `INVALID_JSON`: trạng thái và người tạo do server quyết định.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (thiếu quyền thì 403 trước khi đọc body).
2. Từng trường riêng lẻ: thiếu trường bắt buộc, số lượng không lớn hơn 0, lương âm hoặc vượt trần, văn bản quá dài hoặc chứa ký tự NUL. Mọi trường sai được trả cùng lúc trong `fieldErrors` với mã `VALIDATION_ERROR`.
3. Khóa tài khoản và phiên, kiểm lại quyền (403 nếu vừa mất quyền, 401 `SESSION_INVALID` nếu phiên/tài khoản/token không còn hợp lệ).
4. So hai mức lương đề xuất: tối thiểu lớn hơn tối đa trả `REQUISITION_SALARY_RANGE_INVALID`.
5. Chức danh tồn tại, rồi phòng ban tồn tại.

Task 244 **chưa** kiểm: chức danh/phòng ban còn đang áp dụng (`active`), dải lương đề xuất so với dải chuẩn của chức danh và giải trình, ngày cần người ở quá khứ, phòng ban thuộc phạm vi người tạo. Các quy tắc này thuộc task 246–249.

Response của tạo:

```json
{
  "id": "00000000-0000-0000-0000-000000000010",
  "positionId": "00000000-0000-0000-0000-000000000003",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "headcount": 2,
  "reason": "NEW_HEADCOUNT",
  "proposedSalaryMin": 15000000,
  "proposedSalaryMax": 25000000,
  "salaryJustification": null,
  "neededBy": "2026-12-31",
  "jobDescription": "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java.",
  "status": "DRAFT",
  "createdBy": "00000000-0000-0000-0000-000000000001",
  "createdAt": "2026-10-07T08:00:00.123456Z",
  "updatedAt": "2026-10-07T08:00:00.123456Z"
}
```

UUID trong ví dụ chỉ minh họa. Trường chưa nhập có giá trị `null` (khóa vẫn có trong JSON). `createdAt`/`updatedAt` là UTC, độ chính xác micro giây như PostgreSQL lưu; lúc tạo hai giá trị bằng nhau. `proposedSalaryMin`/`proposedSalaryMax` là mức người tạo đề xuất, không phải dải lương chuẩn của chức danh; response không chứa dải chuẩn, nên người không có `SALARY_RANGES_READ_ALL` không thấy được dải chuẩn qua API này.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu `positionId`/`departmentId`/`headcount`/`reason`, số lượng nhỏ hơn 1, lương âm hoặc vượt 1.000.000.000.000, văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`|
|400|INVALID_JSON|JSON sai; UUID, ngày hoặc lý do tuyển không đúng định dạng (ví dụ `replacement` chữ thường, `2026-02-30`); lương hoặc `headcount` không phải số nguyên JSON (ví dụ `1.5`, `"2"`); có trường ngoài hợp đồng|
|400|REQUISITION_SALARY_RANGE_INVALID|`proposedSalaryMin` lớn hơn `proposedSalaryMax`; `fieldErrors.proposedSalaryMax` có lời nhắn cho form|
|400|INVALID_REQUISITION_POSITION|Không có chức danh với `positionId`; `fieldErrors.positionId`|
|400|INVALID_REQUISITION_DEPARTMENT|Không có phòng ban với `departmentId`; `fieldErrors.departmentId`|
|401|UNAUTHORIZED hoặc SESSION_INVALID|Thiếu, sai, hết hạn token; phiên bị thu hồi; người gọi bị khóa, kể cả khi điều này xảy ra lúc yêu cầu đang chờ khóa|
|403|FORBIDDEN|Thiếu cả `REQUISITIONS_WRITE_ALL` lẫn `REQUISITIONS_WRITE_SCOPED`|

Mọi lỗi đều không lưu dòng nào. Ví dụ lỗi dải lương đề xuất ngược:

```json
{
  "code": "REQUISITION_SALARY_RANGE_INVALID",
  "message": "Lương đề xuất tối thiểu không được lớn hơn lương đề xuất tối đa.",
  "fieldErrors": {
    "proposedSalaryMax": "Lương đề xuất tối đa phải lớn hơn hoặc bằng lương đề xuất tối thiểu."
  }
}
```

## Database và phạm vi

Dùng bảng `recruitment_requisitions` của V13 (task 243) và quyền `REQUISITIONS_*` có sẵn từ V3; không thêm migration, không đổi quyền, không cần sửa `.env`. Các CHECK và khóa ngoại của V13 vẫn là lớp chặn cuối; API kiểm trước để trả lỗi tiếng Việt thay vì 500. Không có API xóa chức danh/phòng ban, nên chức danh/phòng ban đã kiểm không biến mất trước khi lưu; nếu bị xóa bằng SQL tay đúng lúc đó, khóa ngoại sẽ chặn và yêu cầu thất bại với 500.
