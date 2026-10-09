# API yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-244 (tạo và lưu nháp), TKNHTTDNB1-245 (xem và cập nhật bản nháp) và TKNHTTDNB1-246 (kiểm tra trường bắt buộc và lý do tuyển), story TKNHTTDNB1-29 (S2-10). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ `REQUISITION_*`, `INVALID_REQUISITION_*` dùng `Cache-Control: no-store`.

Hiện có `POST /requisitions`, `GET /requisitions`, `GET /requisitions/{id}` và `PUT /requisitions/{id}`. Task 246 đã thêm kiểm tra trường bắt buộc, lý do tuyển, giới hạn số lượng và chức danh/phòng ban đang áp dụng (mục "Kiểm tra trường bắt buộc và lý do tuyển"). Giải trình khi dải lương đề xuất ngoài chuẩn (247), ngày cần người không ở quá khứ (248) và giới hạn phòng ban được ghi vào yêu cầu (249) là các task sau; story S2-10 chưa hoàn thành.

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
- Trạng thái `active` của phòng ban không ảnh hưởng phạm vi: phòng ban ngừng áp dụng vẫn do người phụ trách của nó xem. Riêng việc **lưu** nháp vào phòng ban ngừng áp dụng bị chặn từ task 246 (xem mục kiểm tra bên dưới).
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
|positionId|Bắt buộc, UUID của chức danh có trong bảng `positions` và đang áp dụng (`active = true`)|
|departmentId|Bắt buộc, UUID của phòng ban có trong bảng `departments` và đang áp dụng (`active = true`)|
|headcount|Bắt buộc, số nguyên JSON từ 1 đến 999|
|reason|Bắt buộc, chuỗi đúng một trong hai mã `REPLACEMENT` (tuyển thay thế) hoặc `NEW_HEADCOUNT` (tăng mới): đúng chữ hoa, không có khoảng trắng đầu/cuối|
|proposedSalaryMin|Không bắt buộc. Lương đề xuất tối thiểu, số nguyên đồng VND từ 0 đến 1.000.000.000.000|
|proposedSalaryMax|Không bắt buộc. Lương đề xuất tối đa, cùng quy tắc; khi có cả hai mức thì không nhỏ hơn `proposedSalaryMin` (được phép bằng)|
|salaryJustification|Không bắt buộc, tối đa 2.000 ký tự, không chứa ký tự NUL|
|neededBy|Không bắt buộc. Ngày cần người dạng `yyyy-MM-dd`, không có giờ|
|jobDescription|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|
|candidateRequirements|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|

Bản nháp được lưu dù chưa viết xong: chỉ bốn trường đầu là bắt buộc. Trường không gửi, gửi `null`, hoặc văn bản rỗng/chỉ có khoảng trắng được lưu là `null` (V13 lưu phần chưa viết là `NULL`). Văn bản có nội dung được giữ nguyên như người dùng nhập, kể cả xuống dòng, thụt đầu dòng và khoảng trắng đầu/cuối. Có thể nhập một đầu của dải lương đề xuất. Lương phải là số nguyên JSON: `1.5`, `1e3` hoặc chuỗi `"15000000"` bị từ chối với `INVALID_JSON`, giống [API chức danh](positions.md). `headcount` cũng vậy: `1.5`, `0.9`, `2.0`, `1e1`, chuỗi `"2"` hoặc `true` bị từ chối với `INVALID_JSON`, không bị làm tròn thành `1`/`0` hay tự đổi thành số; số vượt 2.147.483.647 (giới hạn của cột `INTEGER`) cũng là `INVALID_JSON`. Số nguyên từ 1.000 đến 2.147.483.647 là `VALIDATION_ERROR` "Số lượng cần tuyển tối đa 999 người." (task 246).

Ký tự NUL (mã 0, trong JSON viết là `\u0000`, đôi khi dính vào khi dán từ tệp khác) không lưu được vào cột `TEXT` của PostgreSQL, nên `salaryJustification`, `jobDescription`, `candidateRequirements` chứa ký tự này bị từ chối với `VALIDATION_ERROR` (ví dụ `fieldErrors.jobDescription` là "Mô tả công việc chứa ký tự không hợp lệ."). Mọi ký tự khác, kể cả tab và xuống dòng kiểu Windows `\r\n`, được giữ nguyên.

Trường ngoài hợp đồng, kể cả `id`, `status`, `createdBy`, `createdAt`, bị từ chối với 400 `INVALID_JSON`: trạng thái và người tạo do server quyết định.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (thiếu quyền thì 403 trước khi đọc body).
2. Từng trường riêng lẻ: thiếu trường bắt buộc, số lượng ngoài 1–999, lý do tuyển không phải một trong hai mã, lương âm hoặc vượt trần, văn bản quá dài hoặc chứa ký tự NUL. Mọi trường sai được trả cùng lúc trong `fieldErrors` với mã `VALIDATION_ERROR`.
3. Khóa tài khoản và phiên, kiểm lại quyền (403 nếu vừa mất quyền, 401 `SESSION_INVALID` nếu phiên/tài khoản/token không còn hợp lệ).
4. So hai mức lương đề xuất: tối thiểu lớn hơn tối đa trả `REQUISITION_SALARY_RANGE_INVALID`.
5. Chức danh tồn tại (`INVALID_REQUISITION_POSITION`) và đang áp dụng (`REQUISITION_POSITION_INACTIVE`), rồi phòng ban tồn tại (`INVALID_REQUISITION_DEPARTMENT`) và đang áp dụng (`REQUISITION_DEPARTMENT_INACTIVE`). Mỗi lần chỉ trả lỗi đầu tiên gặp.

Còn **chưa** kiểm: dải lương đề xuất so với dải chuẩn của chức danh và giải trình (247), ngày cần người ở quá khứ (248), phòng ban thuộc phạm vi người tạo (249).

## Kiểm tra trường bắt buộc và lý do tuyển (task 246)

Kết quả Jira: xác thực chức danh, phòng ban, số lượng và lý do tuyển là thay thế hoặc tăng mới. Các quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp).

| Trường | Sai thế nào | HTTP / mã | Lời nhắn (`fieldErrors.<trường>`) |
|---|---|---|---|
|positionId|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Chức danh không được để trống.|
|positionId|Không có chức danh này|400 `INVALID_REQUISITION_POSITION`|Chức danh không tồn tại.|
|positionId|Chức danh `active = false`|400 `REQUISITION_POSITION_INACTIVE`|Chức danh đã ngừng áp dụng, hãy chọn chức danh khác.|
|departmentId|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Phòng ban không được để trống.|
|departmentId|Không có phòng ban này|400 `INVALID_REQUISITION_DEPARTMENT`|Phòng ban không tồn tại.|
|departmentId|Phòng ban `active = false`|400 `REQUISITION_DEPARTMENT_INACTIVE`|Phòng ban đã ngừng áp dụng, hãy chọn phòng ban khác.|
|headcount|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Số lượng cần tuyển không được để trống.|
|headcount|0 hoặc số âm|400 `VALIDATION_ERROR`|Số lượng cần tuyển phải lớn hơn 0.|
|headcount|Từ 1.000 trở lên|400 `VALIDATION_ERROR`|Số lượng cần tuyển tối đa 999 người.|
|reason|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Lý do tuyển không được để trống.|
|reason|Chuỗi khác hai mã, kể cả chữ thường (`replacement`), có khoảng trắng (`" REPLACEMENT"`) hoặc chuỗi rỗng|400 `VALIDATION_ERROR`|Lý do tuyển chỉ được là REPLACEMENT (tuyển thay thế) hoặc NEW_HEADCOUNT (tăng mới).|

Với bốn lỗi `INVALID_REQUISITION_*` và `REQUISITION_*_INACTIVE` của chức danh và phòng ban, `message` và lời nhắn trong `fieldErrors` là cùng một câu; response có `Cache-Control: no-store`.

Lý do tuyển sai (trước task 246 là `INVALID_JSON` chung chung, không nói trường nào sai) nay là lỗi của đúng trường `reason`, và được trả **cùng lúc** với các trường sai khác. Ví dụ body thiếu `positionId`, `headcount` là 1000 và `reason` là `"OTHER"`:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Vui lòng kiểm tra dữ liệu đã nhập.",
  "fieldErrors": {
    "positionId": "Chức danh không được để trống.",
    "headcount": "Số lượng cần tuyển tối đa 999 người.",
    "reason": "Lý do tuyển chỉ được là REPLACEMENT (tuyển thay thế) hoặc NEW_HEADCOUNT (tăng mới)."
  }
}
```

Thứ tự các khóa trong `fieldErrors` không cố định. `reason` gửi dạng số hoặc `true`/`false` được đọc thành chuỗi (`"1"`, `"true"`) nên cũng nhận lời nhắn trên; gửi object hoặc mảng (`{}`, `["REPLACEMENT"]`) thì cả JSON không đúng hợp đồng và trả `INVALID_JSON`.

Quyết định của backend (chờ BA/PO xác nhận):

- **Tối đa 999 người mỗi yêu cầu.** Mục đích là chặn gõ nhầm (ví dụ 10000 thay vì 10); nhu cầu lớn hơn thì tách thành nhiều yêu cầu. Giới hạn này chỉ nằm ở API; CHECK của V13 vẫn chỉ là `headcount > 0` vì task không được sửa migration đã có.
- **Chức danh và phòng ban phải đang áp dụng, kể cả khi sửa.** HR đặt `active = false` để ngừng tuyển cho chức danh/phòng ban đó, nên nháp mới không chọn được. Nháp tạo trước khi chức danh/phòng ban bị ngừng vẫn **xem được** như cũ, nhưng muốn lưu lại thì phải chọn chức danh/phòng ban khác đang áp dụng (hoặc HR bật lại). Điểm này khác API quản trị tài khoản (cho giữ phòng ban cũ đã ngừng): yêu cầu tuyển dụng xin người cho hiện tại, không chỉ ghi nhận quá khứ.
- **Chỉ xét cờ của chính phòng ban được chọn.** Phòng ban đang áp dụng nằm dưới một phòng cha đã ngừng vẫn chọn được, khớp với [API phòng ban](departments.md): ngừng phòng cha không tự ngừng phòng con.
- Người `SCOPED` sửa nháp ngoài phạm vi nhận 403 trước bước kiểm chức danh/phòng ban (bước 5 trước bước 7 trong thứ tự kiểm tra của mục "Cập nhật bản nháp"). Lỗi từng trường (bước 2) vẫn trả trước bước phạm vi như mọi API ghi khác.

**Đồng thời:** dòng chức danh và dòng phòng ban được đọc bằng `SELECT active ... FOR SHARE` trong transaction ghi (mức cô lập mặc định READ COMMITTED). Khóa chia sẻ giữ đến khi tạo/sửa xong, nên `PUT /positions/{id}` hoặc `PUT /departments/{id}` của HR (ví dụ ngừng áp dụng) phải chờ, không thể chen vào giữa lúc kiểm và lúc lưu. Ngược lại, nếu HR đang ngừng áp dụng dở dang, yêu cầu tạo/sửa chờ HR xong rồi dùng giá trị mới: HR commit thì trả `*_INACTIVE`, HR hủy thì lưu bình thường. Nhiều yêu cầu dùng cùng chức danh/phòng ban vẫn chạy song song vì khóa chia sẻ không chặn nhau.

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
7. So hai mức lương đề xuất (`REQUISITION_SALARY_RANGE_INVALID`), rồi chức danh và phòng ban trong body tồn tại và đang áp dụng (`INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`), giống tạo. Kể cả khi body giữ nguyên chức danh/phòng ban của nháp, chúng vẫn phải đang áp dụng.

Hai người sửa cùng một bản nháp cùng lúc được xếp hàng nhờ khóa dòng: người đến sau chờ người trước commit rồi ghi đè toàn bộ (người lưu sau cùng thắng). Chưa có kiểm tra phiên bản (optimistic locking), nên giao diện nên tải lại chi tiết trước khi sửa. Mọi lỗi đều không đổi dòng nào.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Body: thiếu `positionId`/`departmentId`/`headcount`/`reason`, số lượng ngoài 1–999, lý do tuyển không phải `REPLACEMENT`/`NEW_HEADCOUNT`, lương âm hoặc vượt 1.000.000.000.000, văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`. Tham số: `{id}` không phải UUID, `status`/`page`/`size` sai định dạng, `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|INVALID_JSON|JSON sai; UUID hoặc ngày không đúng định dạng (ví dụ `2026-02-30`); lương hoặc `headcount` không phải số nguyên JSON (ví dụ `1.5`, `"2"`) hoặc vượt 2.147.483.647; `reason` là object/mảng; có trường ngoài hợp đồng|
|400|REQUISITION_SALARY_RANGE_INVALID|`proposedSalaryMin` lớn hơn `proposedSalaryMax`; `fieldErrors.proposedSalaryMax` có lời nhắn cho form|
|400|INVALID_REQUISITION_POSITION|Không có chức danh với `positionId`; `fieldErrors.positionId`|
|400|REQUISITION_POSITION_INACTIVE|Chức danh `positionId` đã ngừng áp dụng (`active = false`); `fieldErrors.positionId`|
|400|INVALID_REQUISITION_DEPARTMENT|Không có phòng ban với `departmentId`; `fieldErrors.departmentId`|
|400|REQUISITION_DEPARTMENT_INACTIVE|Phòng ban `departmentId` đã ngừng áp dụng (`active = false`); `fieldErrors.departmentId`|
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

Dùng bảng `recruitment_requisitions` của V13 (task 243) và quyền `REQUISITIONS_*` có sẵn từ V3; không thêm migration, không đổi quyền, không cần sửa `.env`. Danh sách lọc theo chỉ mục `recruitment_requisitions_department_id_idx` và `..._status_idx` của V13. Các phòng ban người gọi phụ trách được tìm bằng một truy vấn đệ quy (`WITH RECURSIVE`) trên `departments.parent_id`; truy vấn dùng `UNION` nên vẫn dừng nếu dữ liệu sửa tay tạo vòng lặp cha–con. Các CHECK và khóa ngoại của V13 vẫn là lớp chặn cuối; API kiểm trước để trả lỗi tiếng Việt thay vì 500. Task 246 cũng không thêm migration: giới hạn 999 người và điều kiện chức danh/phòng ban đang áp dụng chỉ nằm ở API (`RequisitionRequest`, `RequisitionService`). Không có API xóa chức danh/phòng ban, và dòng đã kiểm được giữ khóa `FOR SHARE` đến khi lưu xong, nên chức danh/phòng ban đã kiểm không bị xóa hay bị ngừng áp dụng trước khi lưu, kể cả bằng SQL tay (lệnh đó phải chờ khóa).
