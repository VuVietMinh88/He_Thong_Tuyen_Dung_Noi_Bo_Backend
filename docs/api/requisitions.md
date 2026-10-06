# API yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-244 (tạo và lưu nháp) và TKNHTTDNB1-245 (xem và cập nhật bản nháp), story TKNHTTDNB1-29 (S2-10). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ `REQUISITION_*`, `INVALID_REQUISITION_*` dùng `Cache-Control: no-store`.

Hiện có `POST /requisitions`, `GET /requisitions`, `GET /requisitions/{id}` và `PUT /requisitions/{id}`. Kiểm tra chi tiết trường bắt buộc và lý do tuyển (246), giải trình khi dải lương đề xuất ngoài chuẩn (247), ngày cần người không ở quá khứ (248) và giới hạn phòng ban được ghi vào yêu cầu (249) là các task sau; story S2-10 chưa hoàn thành.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|
|`GET /requisitions`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|
|`GET /requisitions/{id}`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|Như dòng trên|
|`PUT /requisitions/{id}`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|Như dòng `POST`|

INTERVIEWER và tài khoản không có vai trò nhận 403 `FORBIDDEN` ở cả bốn API. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi (`POST`, `PUT`), backend khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền trước khi ghi, giống API chức danh. Quyền đọc và quyền ghi được kiểm riêng: `REQUISITIONS_READ_*` không cho phép tạo/sửa, `REQUISITIONS_WRITE_*` không cho phép gọi hai API `GET`.

## Phạm vi dữ liệu (task 245)

- Người có `ALL` (ADMIN, HR_MANAGER) xem và sửa **mọi** yêu cầu.
- Người chỉ có `SCOPED` chỉ xem và sửa yêu cầu thuộc **phòng ban mình phụ trách** (`departments.manager_user_id` là người gọi), tính cả mọi phòng ban con, cháu bên dưới trong cây. Ví dụ cây `IT > IT_DEV > IT_QA`: trưởng IT thấy yêu cầu của cả ba phòng; trưởng IT_DEV thấy IT_DEV và IT_QA nhưng không thấy IT.
- Người tạo yêu cầu không quyết định quyền xem: khi HR đổi người phụ trách phòng ban, bản nháp của phòng ban đó chuyển sang người phụ trách mới, người phụ trách cũ không xem/sửa được nữa (nhưng `createdBy` vẫn giữ người tạo).
- Trạng thái `active` của phòng ban không ảnh hưởng: phòng ban ngừng áp dụng vẫn do người phụ trách của nó xem.
- Theo cách hiểu này RECRUITER và APPROVER thường không phụ trách phòng ban nào nên nhận danh sách rỗng và 403 khi mở hay sửa một yêu cầu. Cách hiểu này cần BA/PO xác nhận (câu hỏi 2 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)); khi có luồng phân công recruiter/duyệt, phạm vi của hai vai trò này sẽ được mở rộng.
- Yêu cầu tồn tại nhưng ngoài phạm vi trả 403 `FORBIDDEN` theo quy ước chung của [thiết kế phân quyền](../architecture/authorization.md), không trả 404. UUID không tồn tại trả 404 `REQUISITION_NOT_FOUND` cho mọi người gọi; vì vậy người `SCOPED` phân biệt được "không tồn tại" và "không được phép", nhưng UUID là ngẫu nhiên nên không đoán được mã của phòng ban khác.

Task 245 chỉ giới hạn **yêu cầu đã có** mà người gọi được xem/sửa. Phòng ban **ghi vào body** chưa bị giới hạn: người có `SCOPED` vẫn tạo được nháp cho mọi phòng ban, và khi sửa vẫn chuyển được nháp của mình sang phòng ban khác (sau đó không còn thấy nháp đó nữa). Task 249 sẽ bắt `departmentId` trong body của `POST`/`PUT` cũng phải thuộc phạm vi trên.

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

Response của tạo (cũng là cấu trúc của chi tiết, mỗi item trong danh sách và response của sửa):

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

## Danh sách

`GET /requisitions?status=DRAFT&page=0&size=20` trả các yêu cầu người gọi được xem theo mục "Phạm vi dữ liệu", trả **200**.

| Tham số | Ý nghĩa |
|---|---|
|status|Không bắt buộc. Mã trạng thái đúng chữ hoa; hiện chỉ có `DRAFT`. Bỏ qua (hoặc để rỗng) để lấy mọi trạng thái|
|page|Từ 0, mặc định 0; `page × size` (số dòng bỏ qua) không được vượt 2.147.483.647|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`; mỗi item có đúng cấu trúc của response tạo ở trên. Sắp xếp yêu cầu tạo sau lên trước (`createdAt` giảm dần, rồi UUID) để phân trang ổn định; sửa bản nháp không đổi vị trí của nó. Trang ngoài phạm vi có `items` rỗng. Người `SCOPED` không phụ trách phòng ban nào nhận `items` rỗng với `totalElements` và `totalPages` bằng 0. Điều kiện phòng ban nằm trong câu truy vấn SQL, nên yêu cầu của phòng ban khác không được đọc ra khỏi database.

`status` sai (ví dụ `draft` chữ thường hoặc `SUBMITTED`), `page`/`size` không phải số nguyên trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ."; `page` âm, `size` nhỏ hơn 1 hoặc lớn hơn 100, hoặc `page × size` lớn hơn 2.147.483.647 (ví dụ `page=21474837&size=100`) trả 400 `VALIDATION_ERROR` "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.".

## Chi tiết

`GET /requisitions/{id}` trả **200** với cấu trúc như response tạo. UUID không tồn tại trả 404 `REQUISITION_NOT_FOUND`; yêu cầu ngoài phạm vi trả 403 `FORBIDDEN`; `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.

## Cập nhật bản nháp

`PUT /requisitions/{id}` lưu lại bản nháp với nội dung mới, trả **200** cùng yêu cầu sau khi sửa. Body có đúng các trường và quy tắc của `POST` ở trên. `PUT` **thay toàn bộ** nội dung: trường không gửi, gửi `null` hoặc văn bản rỗng/chỉ có khoảng trắng trở thành `null`, nên giao diện phải gửi lại mọi trường đang có trên form. `id`, `status`, `createdBy`, `createdAt` không sửa được (gửi lên là `INVALID_JSON`); `createdBy` và `createdAt` giữ nguyên kể cả khi người sửa không phải người tạo, `updatedAt` là thời điểm sửa (UTC, micro giây).

Chỉ yêu cầu ở trạng thái `DRAFT` được sửa; trạng thái khác trả 409 `REQUISITION_NOT_DRAFT`. Hiện V13 chỉ cho phép `DRAFT` nên lỗi này chưa xảy ra được; nó có hiệu lực khi luồng duyệt thêm trạng thái mới.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (thiếu quyền ghi thì 403 trước khi đọc body); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Từng trường riêng lẻ, giống bước 2 của tạo.
3. Khóa tài khoản rồi phiên, kiểm lại quyền (403 nếu vừa mất quyền, 401 `SESSION_INVALID` nếu phiên/tài khoản/token không còn hợp lệ).
4. Khóa dòng yêu cầu (`SELECT ... FOR UPDATE`): không có thì 404 `REQUISITION_NOT_FOUND`.
5. Phạm vi: người `SCOPED` không phụ trách phòng ban hiện tại của yêu cầu thì 403 `FORBIDDEN`. Bước này chạy sau khi đã khóa dòng, nên nếu HR đổi người phụ trách phòng ban trong lúc yêu cầu đang chờ khóa, kết quả dùng người phụ trách mới.
6. Còn là `DRAFT`, nếu không thì 409 `REQUISITION_NOT_DRAFT`.
7. So hai mức lương đề xuất (`REQUISITION_SALARY_RANGE_INVALID`), rồi chức danh và phòng ban mới tồn tại (`INVALID_REQUISITION_POSITION`, `INVALID_REQUISITION_DEPARTMENT`), giống tạo.

Hai người sửa cùng một bản nháp cùng lúc được xếp hàng nhờ khóa dòng: người đến sau chờ người trước commit rồi ghi đè toàn bộ (người lưu sau cùng thắng). Chưa có kiểm tra phiên bản (optimistic locking), nên giao diện nên tải lại chi tiết trước khi sửa. Mọi lỗi đều không đổi dòng nào.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Body: thiếu `positionId`/`departmentId`/`headcount`/`reason`, số lượng nhỏ hơn 1, lương âm hoặc vượt 1.000.000.000.000, văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`. Tham số: `{id}` không phải UUID, `status`/`page`/`size` sai định dạng, `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|INVALID_JSON|JSON sai; UUID, ngày hoặc lý do tuyển không đúng định dạng (ví dụ `replacement` chữ thường, `2026-02-30`); lương hoặc `headcount` không phải số nguyên JSON (ví dụ `1.5`, `"2"`); có trường ngoài hợp đồng|
|400|REQUISITION_SALARY_RANGE_INVALID|`proposedSalaryMin` lớn hơn `proposedSalaryMax`; `fieldErrors.proposedSalaryMax` có lời nhắn cho form|
|400|INVALID_REQUISITION_POSITION|Không có chức danh với `positionId`; `fieldErrors.positionId`|
|400|INVALID_REQUISITION_DEPARTMENT|Không có phòng ban với `departmentId`; `fieldErrors.departmentId`|
|401|UNAUTHORIZED hoặc SESSION_INVALID|Thiếu, sai, hết hạn token; phiên bị thu hồi; người gọi bị khóa, kể cả khi điều này xảy ra lúc yêu cầu ghi đang chờ khóa|
|403|FORBIDDEN|Thiếu quyền của thao tác (bảng đầu trang); hoặc người `SCOPED` xem/sửa yêu cầu của phòng ban mình không phụ trách|
|404|REQUISITION_NOT_FOUND|`GET`/`PUT` với UUID không có yêu cầu nào|
|409|REQUISITION_NOT_DRAFT|`PUT` một yêu cầu không còn ở trạng thái `DRAFT` (chưa xảy ra được, xem trên)|

Ví dụ lỗi dải lương đề xuất ngược:

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

Dùng bảng `recruitment_requisitions` của V13 (task 243) và quyền `REQUISITIONS_*` có sẵn từ V3; không thêm migration, không đổi quyền, không cần sửa `.env`. Danh sách lọc theo chỉ mục `recruitment_requisitions_department_id_idx` và `..._status_idx` của V13. Các phòng ban người gọi phụ trách được tìm bằng một truy vấn đệ quy (`WITH RECURSIVE`) trên `departments.parent_id`; truy vấn dùng `UNION` nên vẫn dừng nếu dữ liệu sửa tay tạo vòng lặp cha–con. Các CHECK và khóa ngoại của V13 vẫn là lớp chặn cuối; API kiểm trước để trả lỗi tiếng Việt thay vì 500. Không có API xóa chức danh/phòng ban, nên chức danh/phòng ban đã kiểm không biến mất trước khi lưu; nếu bị xóa bằng SQL tay đúng lúc đó, khóa ngoại sẽ chặn và yêu cầu thất bại với 500.
