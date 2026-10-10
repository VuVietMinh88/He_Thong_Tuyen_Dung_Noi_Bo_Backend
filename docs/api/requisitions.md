# API yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-244 (tạo và lưu nháp), TKNHTTDNB1-245 (xem và cập nhật bản nháp), TKNHTTDNB1-246 (kiểm tra trường bắt buộc và lý do tuyển), TKNHTTDNB1-247 (bắt buộc giải trình khi dải lương đề xuất ngoài chuẩn), TKNHTTDNB1-248 (ngày cần người không ở quá khứ) và TKNHTTDNB1-249 (kiểm tra quyền và phòng ban của người tạo yêu cầu), story TKNHTTDNB1-29 (S2-10). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ `REQUISITION_*`, `INVALID_REQUISITION_*`, `SALARY_JUSTIFICATION_REQUIRED`, `NEEDED_BY_IN_PAST` dùng `Cache-Control: no-store`.

Hiện có `POST /requisitions`, `GET /requisitions`, `GET /requisitions/{id}` và `PUT /requisitions/{id}`. Task 246 đã thêm kiểm tra trường bắt buộc, lý do tuyển, giới hạn số lượng và chức danh/phòng ban đang áp dụng (mục "Kiểm tra trường bắt buộc và lý do tuyển"). Task 247 bắt buộc nhập giải trình khi dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh (mục "Giải trình khi dải lương đề xuất ngoài chuẩn"). Task 248 từ chối ngày cần người trước ngày hôm nay theo múi giờ nghiệp vụ (mục "Ngày cần người không ở quá khứ"). Task 249 bắt phòng ban ghi vào yêu cầu (khi tạo, và khi sửa) phải thuộc phạm vi người gọi (mục "Phạm vi dữ liệu"). Sau task 249, phần backend cho các tiêu chí của story S2-10 đã có; trong story còn task 250 (kết nối giao diện, phía frontend) và 251 (test tạo và lưu nháp).

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|
|`GET /requisitions`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|
|`GET /requisitions/{id}`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|Như dòng trên|
|`PUT /requisitions/{id}`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|Như dòng `POST`|

INTERVIEWER và tài khoản không có vai trò nhận 403 `FORBIDDEN` ở cả bốn API. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi (`POST`, `PUT`), backend khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền trước khi ghi, giống API chức danh. Quyền đọc và quyền ghi được kiểm riêng: `REQUISITIONS_READ_*` không cho phép tạo/sửa, `REQUISITIONS_WRITE_*` không cho phép gọi hai API `GET`.

## Phạm vi dữ liệu (task 245, 249)

- Người có `ALL` (ADMIN, HR_MANAGER) xem, tạo và sửa yêu cầu của **mọi** phòng ban, và chuyển nháp giữa mọi phòng ban.
- Người chỉ có `SCOPED` chỉ xem, tạo và sửa yêu cầu thuộc **phòng ban mình phụ trách** (`departments.manager_user_id` là người gọi), tính cả mọi phòng ban con, cháu bên dưới trong cây. Ví dụ cây `IT > IT_DEV > IT_QA`: trưởng IT thấy và tạo được yêu cầu cho cả ba phòng; trưởng IT_DEV thấy và tạo được cho IT_DEV và IT_QA nhưng không cho IT.
- Người tạo yêu cầu không quyết định quyền xem: khi HR đổi người phụ trách phòng ban, bản nháp của phòng ban đó chuyển sang người phụ trách mới, người phụ trách cũ không xem/sửa được nữa (nhưng `createdBy` vẫn giữ người tạo).
- Trạng thái `active` của phòng ban không ảnh hưởng phạm vi: phòng ban ngừng áp dụng vẫn do người phụ trách của nó xem. Riêng việc **lưu** nháp vào phòng ban ngừng áp dụng bị chặn từ task 246 (xem mục kiểm tra bên dưới).
- Theo cách hiểu này RECRUITER và APPROVER thường không phụ trách phòng ban nào nên nhận danh sách rỗng và 403 khi mở, sửa hay tạo một yêu cầu. Cách hiểu này cần BA/PO xác nhận (câu hỏi 2 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)); khi có luồng phân công recruiter/duyệt, phạm vi của hai vai trò này sẽ được mở rộng.
- Yêu cầu tồn tại nhưng ngoài phạm vi trả 403 `FORBIDDEN` theo quy ước chung của [thiết kế phân quyền](../architecture/authorization.md), không trả 404. UUID không tồn tại trả 404 `REQUISITION_NOT_FOUND` cho mọi người gọi; vì vậy người `SCOPED` phân biệt được "không tồn tại" và "không được phép", nhưng UUID là ngẫu nhiên nên không đoán được mã của phòng ban khác.

Task 245 giới hạn **yêu cầu đã có** mà người gọi được xem/sửa. Task 249 (kết quả Jira: "Bảo đảm Trưởng bộ phận tạo và cập nhật yêu cầu thuộc phạm vi phòng ban được phép") giới hạn thêm **phòng ban ghi vào body** (`departmentId`):

- `POST`: người `SCOPED` chỉ tạo được yêu cầu cho phòng ban thuộc phạm vi trên. Phòng ban khác, kể cả phòng ban **cha** của phòng mình phụ trách, trả 403 `FORBIDDEN` và không lưu gì.
- `PUT`: phải qua **hai** lần kiểm. Phòng ban hiện tại của nháp phải thuộc phạm vi (task 245), và phòng ban mới trong body cũng phải thuộc phạm vi. Vì vậy trưởng bộ phận chuyển được nháp giữa các phòng mình phụ trách (ví dụ IT sang IT_QA), nhưng không chuyển được ra ngoài (IT sang SALES), cũng không "kéo" nháp của phòng khác về phòng mình. Lần kiểm nào không qua cũng trả 403 `FORBIDDEN` và dòng giữ nguyên.
- Người `ALL` không bị giới hạn phòng ban; tài khoản có nhiều vai trò được `ALL` nếu một vai trò có `REQUISITIONS_WRITE_ALL` (ví dụ HIRING_MANAGER kiêm HR_MANAGER).
- Phạm vi chỉ phụ thuộc cây phòng ban, không phụ thuộc vai trò cụ thể: RECRUITER hay APPROVER được đặt làm người phụ trách một phòng ban cũng tạo được yêu cầu cho phòng ban đó; ai không phụ trách phòng ban nào thì nhận 403 với mọi `departmentId` (chờ BA/PO, câu hỏi 2 như trên).
- `active` của phòng ban chọn trong body vẫn phải là `true` (task 246), nhưng không ảnh hưởng phạm vi: phòng ban con đang áp dụng nằm dưới một phòng ban đã ngừng vẫn thuộc phạm vi của người phụ trách phòng ban đã ngừng đó.

Thứ tự: kiểm phạm vi của phòng ban trong body **sau** khi phòng ban đó được xác nhận tồn tại và đang áp dụng, và **trước** khi so dải lương chuẩn (bước 7 của tạo, trong bước 7 của sửa ở dưới). Hệ quả:

- `departmentId` không tồn tại vẫn là 400 `INVALID_REQUISITION_DEPARTMENT` với mọi người gọi, không phải 403, giống "UUID không tồn tại là 404 trước 403" ở trên. Phòng ban đã ngừng là 400 `REQUISITION_DEPARTMENT_INACTIVE`. Cây phòng ban vốn đọc được bởi mọi vai trò nội bộ (`ORGANIZATION_READ_ALL`), nên thứ tự này không làm lộ thêm thông tin.
- Lỗi chỉ dựa trên body (từng trường, `REQUISITION_SALARY_RANGE_INVALID`, `NEEDED_BY_IN_PAST`) và lỗi chức danh được trả trước 403 này.
- Lương ngoài dải chuẩn mà chưa có giải trình nhưng phòng ban ngoài phạm vi trả 403, không trả `SALARY_JUSTIFICATION_REQUIRED`: dải chuẩn không được so cho yêu cầu không được phép lưu.

**Đồng thời:** phạm vi được đọc **sau** khi dòng phòng ban trong body bị khóa `SELECT ... FOR SHARE` (bước 6 của tạo). Nếu HR đang đổi người phụ trách hoặc phòng ban cha của chính phòng ban đó, request chờ HR xong rồi dùng cây mới: ví dụ HR chuyển IT_DEV từ dưới IT sang dưới SALES và commit trong lúc trưởng IT đang tạo nháp cho IT_DEV thì trưởng IT nhận 403, HR hủy thì lưu bình thường. Sau khi kiểm, khóa giữ đến khi lưu xong nên HR không đổi được người phụ trách hay phòng ban cha của phòng ban đó trước khi nháp được lưu. Thay đổi ở phòng ban **cha** (ví dụ đổi người phụ trách IT khi nháp chọn IT_DEV) không bị chặn: nếu nó commit sau lúc kiểm thì được tính như xảy ra ngay sau khi nháp được lưu, và nháp đi theo người phụ trách mới như mọi nháp khác.

Quyết định của backend (chờ BA/PO xác nhận):

- Phòng ban ngoài phạm vi trả 403 `FORBIDDEN` theo quy ước chung, không trả lỗi form 400 riêng cho `departmentId`. Giao diện nên chỉ cho chọn các phòng ban người dùng phụ trách (tính được từ `GET /departments/tree`: node có `managerUserId` là người đang đăng nhập cùng toàn bộ `children` bên dưới), nên 403 này chỉ xảy ra khi dữ liệu trên form đã cũ hoặc request bị sửa tay.
- Không thêm quyền hay cột "phòng ban được phép" riêng: phạm vi tính từ `departments.manager_user_id` và `parent_id` ở mỗi lần lưu.

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
|salaryJustification|Tối đa 2.000 ký tự, không chứa ký tự NUL. **Bắt buộc** khi một mức lương đề xuất đã nhập nằm ngoài dải lương chuẩn của chức danh (task 247); ngoài trường hợp đó thì không bắt buộc|
|neededBy|Không bắt buộc. Ngày cần người dạng `yyyy-MM-dd`, không có giờ. Nếu có thì không được **trước ngày hôm nay** theo múi giờ nghiệp vụ, mặc định giờ Việt Nam (task 248); hôm nay được phép|
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
5. Ngày cần người trước ngày hôm nay theo múi giờ nghiệp vụ: `NEEDED_BY_IN_PAST` (task 248).
6. Chức danh tồn tại (`INVALID_REQUISITION_POSITION`) và đang áp dụng (`REQUISITION_POSITION_INACTIVE`), rồi phòng ban tồn tại (`INVALID_REQUISITION_DEPARTMENT`) và đang áp dụng (`REQUISITION_DEPARTMENT_INACTIVE`).
7. Phòng ban trong body thuộc phạm vi người gọi (chỉ kiểm với người `SCOPED`): nếu không thì 403 `FORBIDDEN` (task 249, mục "Phạm vi dữ liệu").
8. Mức lương đề xuất ngoài dải lương chuẩn của chức danh mà không có giải trình: `SALARY_JUSTIFICATION_REQUIRED` (task 247).

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 4 trở đi.

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

**Đồng thời:** dòng chức danh và dòng phòng ban được đọc bằng `SELECT active ... FOR SHARE` trong transaction ghi (mức cô lập mặc định READ COMMITTED). Khóa chia sẻ giữ đến khi tạo/sửa xong, nên `PUT /positions/{id}` hoặc `PUT /departments/{id}` của HR (ví dụ ngừng áp dụng) phải chờ, không thể chen vào giữa lúc kiểm và lúc lưu. Ngược lại, nếu HR đang ngừng áp dụng dở dang, yêu cầu tạo/sửa chờ HR xong rồi dùng giá trị mới: HR commit thì trả `*_INACTIVE`, HR hủy thì lưu bình thường. Nhiều yêu cầu dùng cùng chức danh/phòng ban vẫn chạy song song vì khóa chia sẻ không chặn nhau. `DELETE /departments/{id}` (task 197) khóa dòng phòng ban `FOR UPDATE`: đang có yêu cầu tạo/sửa chọn phòng đó thì lệnh xóa chờ rồi trả 409 `DEPARTMENT_HAS_OPEN_REQUISITIONS`; lệnh xóa chạy trước thì yêu cầu tạo/sửa chờ xóa xong rồi nhận 400 `INVALID_REQUISITION_DEPARTMENT`.

Response của tạo (cũng là cấu trúc của chi tiết và response của sửa):

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

UUID trong ví dụ chỉ minh họa. Trường chưa nhập có giá trị `null` (khóa vẫn có trong JSON). `createdAt`/`updatedAt` là UTC, độ chính xác micro giây như PostgreSQL lưu; lúc tạo hai giá trị bằng nhau. `proposedSalaryMin`/`proposedSalaryMax` là mức người tạo đề xuất, không phải dải lương chuẩn của chức danh; response không chứa dải chuẩn, nên người không có `SALARY_RANGES_READ_ALL` không thấy được con số của dải chuẩn qua API này (lỗi giải trình của task 247 cũng không chứa con số, xem mục "Giải trình khi dải lương đề xuất ngoài chuẩn").

## Danh sách

`GET /requisitions?status=DRAFT&page=0&size=20` trả các yêu cầu người gọi được xem theo mục "Phạm vi dữ liệu", trả **200**.

| Tham số | Ý nghĩa |
|---|---|
|status|Không bắt buộc. Mã trạng thái đúng chữ hoa; hiện chỉ có `DRAFT`. Bỏ qua (hoặc để rỗng) để lấy mọi trạng thái|
|page|Từ 0, mặc định 0; `page × size` (số dòng bỏ qua) không được vượt 2.147.483.647|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`; mỗi item có 15 trường của response tạo ở trên và thêm `daysOpen`, `daysUntilNeededBy`, `overdue`. Ba trường tính toán chỉ có trong item danh sách; tạo, chi tiết và sửa giữ cấu trúc response cũ.

`daysOpen` là số ngày từ ngày tạo tới hôm nay (ngày tạo tính là 0). `daysUntilNeededBy` là số ngày từ hôm nay tới `neededBy`, âm khi đã trễ và `null` nếu chưa có ngày cần người. `overdue` chỉ là `true` khi `neededBy` trước hôm nay; đến hạn hôm nay chưa trễ. Các ngày được tính theo `app.business-zone`, mặc định `Asia/Ho_Chi_Minh`, tại thời điểm đọc danh sách.

Sắp xếp yêu cầu tạo sau lên trước (`createdAt` giảm dần, rồi UUID) để phân trang ổn định; sửa bản nháp không đổi vị trí của nó. Trang ngoài phạm vi có `items` rỗng. Người `SCOPED` không phụ trách phòng ban nào nhận `items` rỗng với `totalElements` và `totalPages` bằng 0. Điều kiện phòng ban nằm trong câu truy vấn SQL, nên yêu cầu của phòng ban khác không được đọc ra khỏi database.

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
7. So hai mức lương đề xuất (`REQUISITION_SALARY_RANGE_INVALID`), rồi ngày cần người (`NEEDED_BY_IN_PAST`), rồi chức danh và phòng ban trong body tồn tại và đang áp dụng (`INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`), rồi phòng ban trong body thuộc phạm vi người gọi (403 `FORBIDDEN`, task 249), rồi giải trình khi lương đề xuất ngoài dải chuẩn (`SALARY_JUSTIFICATION_REQUIRED`), giống tạo. Kể cả khi body giữ nguyên chức danh/phòng ban của nháp, chúng vẫn phải đang áp dụng, và phòng ban vẫn được kiểm phạm vi lần nữa sau khi bị khóa `FOR SHARE`.

Hai người sửa cùng một bản nháp cùng lúc được xếp hàng nhờ khóa dòng: người đến sau chờ người trước commit rồi ghi đè toàn bộ (người lưu sau cùng thắng). Chưa có kiểm tra phiên bản (optimistic locking), nên giao diện nên tải lại chi tiết trước khi sửa. Mọi lỗi đều không đổi dòng nào.

## Giải trình khi dải lương đề xuất ngoài chuẩn (task 247)

Kết quả Jira: so sánh với dải chuẩn của chức danh và yêu cầu nhập giải trình nếu đề xuất nằm ngoài chuẩn. Quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp), cho mọi vai trò được ghi (ADMIN, HR_MANAGER và người `SCOPED`).

- **Dải chuẩn** là `salary_min`–`salary_max` hiện tại của chức danh `positionId` trong body (bảng `positions` của V7), lấy qua `SalaryBandService` của task 206. **Hai đầu dải tính là trong chuẩn.** Ví dụ dải chuẩn 15.000.000–25.000.000: đề xuất 15.000.000–25.000.000 không cần giải trình; 14.999.999 hoặc 25.000.001 là ngoài chuẩn. Dải cố định (`salary_min = salary_max`) chỉ nhận đúng một mức đó.
- **Mỗi mức đã nhập được so riêng.** `proposedSalaryMin` hoặc `proposedSalaryMax` thấp hơn `salary_min` hoặc cao hơn `salary_max` là ngoài chuẩn, tính cả phía **thấp hơn** lẫn phía **cao hơn**. Dải đề xuất rộng hơn dải chuẩn (ví dụ 10.000.000–30.000.000) cũng là ngoài chuẩn.
- **Nháp vẫn được nhập một đầu** như task 244: chỉ đầu đã nhập được so. Chưa nhập mức lương nào thì không cần giải trình.
- Ngoài chuẩn mà `salaryJustification` không gửi, `null`, rỗng hoặc chỉ có khoảng trắng: 400 `SALARY_JUSTIFICATION_REQUIRED`, không lưu gì.
- Có giải trình (tối đa 2.000 ký tự, không chứa NUL như trước) thì lưu bình thường, văn bản giữ nguyên như người dùng nhập. Đề xuất trong chuẩn vẫn được nhập giải trình; giải trình đó được lưu, không bị xóa.
- **So ở mỗi lần lưu với dải hiện tại.** Nếu HR thu hẹp dải sau khi nháp đã lưu, `GET` vẫn trả nháp như cũ, nhưng lần `PUT` sau (kể cả gửi nội dung y hệt) phải có giải trình. `PUT` đổi sang chức danh khác thì so với dải của chức danh mới.
- Vì `PUT` thay toàn bộ nội dung, gửi giải trình rỗng trong khi lương vẫn ngoài chuẩn bị từ chối và giải trình đã lưu được giữ nguyên. Đưa lương về trong chuẩn thì xóa được giải trình.

```json
{
  "code": "SALARY_JUSTIFICATION_REQUIRED",
  "message": "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình.",
  "fieldErrors": {
    "salaryJustification": "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình."
  }
}
```

**Không lộ dải chuẩn.** Lời nhắn chỉ nói đề xuất nằm ngoài dải chuẩn: không có con số, không nói thấp hơn hay cao hơn. Response tạo, chi tiết và sửa giữ đúng 15 trường như trên; item danh sách thêm ba trường thời gian nhưng không có `salaryMin`/`salaryMax` của chức danh hoặc cờ "ngoài chuẩn". Điều này áp dụng cho mọi người gọi, kể cả HR_MANAGER (người có `SALARY_RANGES_READ_ALL` xem dải chuẩn ở [API chức danh](positions.md)). Trong code, `RequisitionService` chỉ nhận `BELOW`/`WITHIN`/`ABOVE` từ `SalaryBandService.compare`, không cầm con số của dải. Giới hạn còn lại: chính quy tắc này cho người gọi biết một mức lương cụ thể nằm trong hay ngoài dải (lưu được hay bị đòi giải trình), nên thử nhiều mức có thể dò ra hai đầu dải. Mỗi lần thử lưu được đều tạo hoặc sửa một bản nháp có ghi người tạo và thời điểm, nên việc dò để lại dấu vết. Nếu BA/PO cần chặn hẳn việc này thì phải đổi yêu cầu nghiệp vụ (ví dụ luôn bắt giải trình).

Thứ tự: đây là bước 8 của tạo và phần cuối bước 7 của sửa ở trên, chạy sau mọi bước kiểm khác. Chức danh không tồn tại hoặc đã ngừng áp dụng trả `INVALID_REQUISITION_POSITION`/`REQUISITION_POSITION_INACTIVE` trước, không bao giờ trả 404 `POSITION_NOT_FOUND` hay 409 `POSITION_INACTIVE` của `SalaryBandService`. Giải trình dài hơn 2.000 ký tự là `VALIDATION_ERROR` ở bước 2, không phải `SALARY_JUSTIFICATION_REQUIRED`. Người thiếu quyền, sửa nháp ngoài phạm vi hoặc chọn phòng ban ngoài phạm vi (task 249) nhận 403 trước khi lương được so.

**Đồng thời:** dải chuẩn được đọc sau khi dòng chức danh đã bị khóa `FOR SHARE` (bước 6 của tạo), trong cùng transaction ghi. `PUT /positions/{id}` của HR đổi dải lương phải chờ tạo/sửa nháp xong; ngược lại, nếu HR đang đổi dải dở dang, yêu cầu tạo/sửa chờ HR xong rồi so với dải HR đã commit (HR hủy thì so với dải cũ).

Quyết định của backend (chờ BA/PO xác nhận):

- Ngoài chuẩn tính cả **thấp hơn** dải, không chỉ cao hơn: tiêu chí story ghi "nằm ngoài dải chuẩn".
- Không bắt nhập đủ hai mức lương khi một mức đã có: nháp được lưu dở (task 244). Việc bắt đủ thông tin trước khi gửi duyệt thuộc luồng duyệt sau này.
- Không thêm cờ kiểu `salaryOutsideStandardBand` vào response: giao diện biết qua lỗi 400; cờ phải tính lại ở mỗi `GET`/danh sách vì HR có thể đổi dải sau khi lưu. Nháp đã lưu không được kiểm lại khi HR đổi dải; nó chỉ được kiểm ở lần lưu sau (luồng gửi duyệt sau này nên kiểm lại).

## Ngày cần người không ở quá khứ (task 248)

Kết quả Jira: từ chối ngày cần người trước ngày hiện tại theo múi giờ nghiệp vụ. Quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp), cho mọi vai trò được ghi (ADMIN, HR_MANAGER và người `SCOPED`).

- **Hôm nay** là ngày hiện tại theo **múi giờ nghiệp vụ** `app.business-zone` (trong `src/main/resources/application.properties`, mặc định `Asia/Ho_Chi_Minh`, tức UTC+7), không phải ngày UTC và không phải múi giờ của JVM (README chạy server với `-Duser.timezone=UTC`). Từ 00:00 đến 07:00 giờ Việt Nam, ngày UTC vẫn là hôm trước. Ví dụ lúc `2026-10-06T17:30:00Z` ở Việt Nam đã là 00:30 ngày 07/10, nên `neededBy = "2026-10-06"` bị từ chối dù theo UTC vẫn đang là ngày 06/10.
- `neededBy` **trước** hôm nay: 400 `NEEDED_BY_IN_PAST`, không lưu gì. **Hôm nay** và mọi ngày sau được phép; không có giới hạn ngày xa nhất (ví dụ `9999-12-31` vẫn lưu được).
- `neededBy` không gửi hoặc `null` vẫn lưu được: nháp được để trống ngày cần người như task 244.
- **So ở mỗi lần lưu với ngày hiện tại.** Nháp lưu hôm qua với ngày cần người là hôm qua: `GET` vẫn trả như cũ, nhưng `PUT` lại (kể cả gửi nội dung y hệt) bị từ chối và dòng giữ nguyên; muốn lưu thì chọn ngày từ hôm nay trở đi hoặc để trống. Nháp đã lưu không tự bị sửa khi qua ngày.
- "Hôm nay" được tính **sau khi đã khóa tài khoản, phiên (và dòng yêu cầu khi sửa)**, không phải lúc request đến. Request gửi lúc 23:59:59 nhưng phải chờ **các khóa này** tới sau 00:00 thì so với ngày mới.
- Giới hạn sát nửa đêm: ngày được so **trước** khi khóa `FOR SHARE` dòng chức danh và phòng ban (bước 6 của tạo), vì thứ tự lỗi (`NEEDED_BY_IN_PAST` trước lỗi chức danh/phòng ban) được giữ có chủ ý. Nếu request so ngày xong rồi phải chờ khóa chức danh/phòng ban qua 00:00 (ví dụ HR đang sửa đúng chức danh đó bằng `PUT /positions/{id}`), hoặc qua 00:00 trong khoảng ngắn trước khi commit, thì ngày vừa lưu có thể đã sớm hơn hôm nay một ngày tính theo lúc commit. Lần `PUT` sau sẽ bị từ chối nếu ngày đó vẫn ở quá khứ; luồng gửi duyệt sau này nên kiểm lại.

```json
{
  "code": "NEEDED_BY_IN_PAST",
  "message": "Ngày cần người không được trước ngày hôm nay.",
  "fieldErrors": {
    "neededBy": "Ngày cần người không được trước ngày hôm nay."
  }
}
```

Thứ tự: bước 5 của tạo (sau so hai mức lương, trước kiểm chức danh/phòng ban và giải trình lương) và trong bước 7 của sửa (cùng vị trí). Vì vậy ngày ở quá khứ được báo trước cả chức danh không tồn tại, phòng ban đã ngừng áp dụng hay lương ngoài dải chưa có giải trình. Lỗi từng trường (bước 2) vẫn trả trước, và `fieldErrors` của `VALIDATION_ERROR` không có `neededBy`. Ngày sai định dạng hoặc không tồn tại (`31/12/2026`, `2026-02-30`) vẫn là `INVALID_JSON` như trước. Người thiếu quyền hoặc sửa nháp ngoài phạm vi nhận 403, `PUT` UUID không tồn tại nhận 404, trước khi ngày được so. Riêng phòng ban trong body ngoài phạm vi (task 249) được kiểm sau ngày, nên ngày ở quá khứ được báo trước 403 đó.

**Cấu hình.** `app.business-zone` nhận tên múi giờ IANA như `Asia/Ho_Chi_Minh` (hoặc `UTC`, `+07:00`). Tên sai làm ứng dụng dừng ngay khi khởi động thay vì tính sai ngày. Mọi máy chủ chạy backend phải dùng cùng một giá trị. Không cần sửa `.env`. Trong code, `BusinessCalendar.today()` (gói `vn.ttcs.recruitment.common`) đọc `Clock` của ứng dụng rồi đổi sang ngày theo múi giờ này; `RequisitionService` chỉ so `neededBy` với ngày đó. Giao diện nên chặn sẵn ngày trước hôm nay (theo giờ Việt Nam) trên ô chọn ngày, nhưng backend vẫn là nơi kiểm cuối cùng.

Quyết định của backend (chờ BA/PO xác nhận):

- **Hôm nay được phép**: tiêu chí story là "không được ở quá khứ", và Jira ghi "trước ngày hiện tại".
- **Kiểm cả khi sửa nháp có ngày cũ không đổi**: yêu cầu tuyển dụng xin người cho hiện tại và tương lai, giống quy tắc chức danh/phòng ban phải đang áp dụng (task 246) và dải lương hiện tại (task 247). Luồng gửi duyệt sau này nên kiểm lại.
- **Không giới hạn ngày xa nhất** và không bắt nhập ngày khi lưu nháp.
- **Một múi giờ nghiệp vụ chung** cho cả hệ thống (công ty ở Việt Nam), không theo múi giờ của từng người dùng hay trình duyệt.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Body: thiếu `positionId`/`departmentId`/`headcount`/`reason`, số lượng ngoài 1–999, lý do tuyển không phải `REPLACEMENT`/`NEW_HEADCOUNT`, lương âm hoặc vượt 1.000.000.000.000, văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`. Tham số: `{id}` không phải UUID, `status`/`page`/`size` sai định dạng, `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|INVALID_JSON|JSON sai; UUID hoặc ngày không đúng định dạng (ví dụ `2026-02-30`); lương hoặc `headcount` không phải số nguyên JSON (ví dụ `1.5`, `"2"`) hoặc vượt 2.147.483.647; `reason` là object/mảng; có trường ngoài hợp đồng|
|400|REQUISITION_SALARY_RANGE_INVALID|`proposedSalaryMin` lớn hơn `proposedSalaryMax`; `fieldErrors.proposedSalaryMax` có lời nhắn cho form|
|400|NEEDED_BY_IN_PAST|`neededBy` trước ngày hôm nay theo múi giờ nghiệp vụ `app.business-zone` (mặc định `Asia/Ho_Chi_Minh`); `fieldErrors.neededBy`|
|400|INVALID_REQUISITION_POSITION|Không có chức danh với `positionId`; `fieldErrors.positionId`|
|400|REQUISITION_POSITION_INACTIVE|Chức danh `positionId` đã ngừng áp dụng (`active = false`); `fieldErrors.positionId`|
|400|INVALID_REQUISITION_DEPARTMENT|Không có phòng ban với `departmentId`; `fieldErrors.departmentId`|
|400|REQUISITION_DEPARTMENT_INACTIVE|Phòng ban `departmentId` đã ngừng áp dụng (`active = false`); `fieldErrors.departmentId`|
|400|SALARY_JUSTIFICATION_REQUIRED|Một mức lương đề xuất đã nhập nằm ngoài dải lương chuẩn của chức danh và `salaryJustification` trống; `fieldErrors.salaryJustification`. Lời nhắn không chứa dải chuẩn|
|401|UNAUTHORIZED hoặc SESSION_INVALID|Thiếu, sai, hết hạn token; phiên bị thu hồi; người gọi bị khóa, kể cả khi điều này xảy ra lúc yêu cầu ghi đang chờ khóa|
|403|FORBIDDEN|Thiếu quyền của thao tác (bảng đầu trang); hoặc người `SCOPED` xem/sửa yêu cầu của phòng ban mình không phụ trách; hoặc người `SCOPED` tạo yêu cầu cho, hay chuyển nháp sang, phòng ban mình không phụ trách (task 249)|
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

Dùng bảng `recruitment_requisitions` của V13 (task 243) và quyền `REQUISITIONS_*` có sẵn từ V3; không thêm migration, không đổi quyền, không cần sửa `.env`. Danh sách lọc theo chỉ mục `recruitment_requisitions_department_id_idx` và `..._status_idx` của V13. Các phòng ban người gọi phụ trách được tìm bằng một truy vấn đệ quy (`WITH RECURSIVE`) trên `departments.parent_id`; truy vấn dùng `UNION` nên vẫn dừng nếu dữ liệu sửa tay tạo vòng lặp cha–con. Các CHECK và khóa ngoại của V13 vẫn là lớp chặn cuối; API kiểm trước để trả lỗi tiếng Việt thay vì 500. Task 246 cũng không thêm migration: giới hạn 999 người và điều kiện chức danh/phòng ban đang áp dụng chỉ nằm ở API (`RequisitionRequest`, `RequisitionService`). Không có API xóa chức danh; API xóa phòng ban (task 197) không xóa phòng còn yêu cầu tuyển dụng chưa đóng. Dòng đã kiểm được giữ khóa `FOR SHARE` đến khi lưu xong, nên chức danh/phòng ban đã kiểm không bị xóa hay bị ngừng áp dụng trước khi lưu, kể cả bằng SQL tay (lệnh đó phải chờ khóa). Task 247 cũng không thêm migration và không đổi quyền: dải chuẩn đọc từ cột `salary_min`/`salary_max` của V7 qua `SalaryBandService`, giải trình lưu vào cột `salary_justification` có sẵn của V13. Task 248 cũng không thêm migration và không đổi quyền: `needed_by` vẫn là cột `DATE` (không có giờ) của V13; quy tắc phụ thuộc ngày hiện tại nên chỉ API kiểm, không phải CHECK của database (một nháp hợp lệ hôm nay sẽ "sai" vào ngày mai). Task 249 cũng không thêm migration và không đổi quyền: phạm vi phòng ban dùng lại truy vấn đệ quy ở trên với cột `manager_user_id`, `parent_id` của V5 và `department_id` của V13; nó phụ thuộc người gọi và cây phòng ban lúc lưu nên chỉ API kiểm, không phải CHECK hay khóa ngoại.
