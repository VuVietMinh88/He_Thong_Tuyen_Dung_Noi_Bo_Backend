# API tin tuyển dụng

Phạm vi TKNHTTDNB1-302 (lưu bản nháp và thông tin công khai), TKNHTTDNB1-303 (API tạo, sửa bản nháp), story TKNHTTDNB1-38 (S3-09). URL dùng tiền tố `/api/v1`, header `Authorization: Bearer …`; mọi response JSON thành công và các lỗi `JOB_POSTING_*` dùng `Cache-Control: no-store`.

Tiêu chí của story S3-09:

- "Nội dung mô tả công việc được kế thừa từ yêu cầu, sửa lại được cho phù hợp ngôn ngữ tuyển dụng": đã có (303). Tin chép mô tả công việc và yêu cầu ứng viên của yêu cầu một lần khi tạo, rồi chỉ sửa trên tin; yêu cầu không bao giờ bị ghi.
- "Khai báo địa điểm, hình thức làm việc, hạn nhận hồ sơ, có hiển thị mức lương hay không": đã có (303). Task 304 thêm kiểm tra hạn nộp không ở quá khứ, giá trị danh mục còn dùng và văn bản thuần.
- "Chỉ yêu cầu ở trạng thái Đã duyệt mới tạo được tin": đã có (303), mục "Tạo tin từ yêu cầu đã duyệt".

Gửi duyệt, duyệt và xuất bản tin (story S3-10, task 308–310) chưa có: hiện mọi tin đều là bản nháp (`DRAFT`).

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /job-postings`|`JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc `JOB_POSTINGS_READ_ALL` hoặc `JOB_POSTINGS_READ_SCOPED`|ADMIN, HR_MANAGER (mọi yêu cầu đã duyệt); RECRUITER (yêu cầu được phân công)|
|`GET /job-postings/source-requisitions`|Như dòng trên|Như dòng trên|
|`GET /job-postings`, `GET /job-postings/{id}`|Như dòng trên: quyền **ghi** kèm quyền đọc, không phải chỉ quyền đọc|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|
|`PUT /job-postings/{id}`|Như dòng trên|Như dòng `GET`|

- Mọi API nội bộ của tin dành cho **người soạn tin**. Matcher ở `SecurityConfiguration` cho `JOB_POSTINGS_WRITE_ALL` hoặc `JOB_POSTINGS_WRITE_SCOPED`; `JobPostingService` còn đòi quyền đọc `JOB_POSTINGS_READ_*`, vì quyền ghi không bao gồm quyền đọc và mọi thao tác ghi trả về cả tin. Người chỉ có quyền ghi bị 403 trước khi backend tra cứu gì, kể cả với `{id}` hay `requisitionId` không tồn tại. Theo seed hiện tại, ai có quyền ghi tin cũng có quyền đọc cùng phạm vi.
- Phạm vi là `ALL` khi cả quyền ghi lẫn quyền đọc là `ALL`; còn lại là `SCOPED` (mục "Phạm vi dữ liệu").
- Tạo tin **không** cần quyền đọc yêu cầu tuyển dụng (`REQUISITIONS_READ_*`). Recruiter được phân công vẫn chưa đọc được yêu cầu (task 285) nhưng phải soạn được tin của nó. Đây là khác biệt có chủ ý với sao chép yêu cầu (task 278), vốn đòi quyền đọc bản gốc. Qua tin, recruiter thấy bản sao mô tả công việc, yêu cầu ứng viên, chức danh, phòng ban, số lượng và lương đề xuất đã duyệt của yêu cầu; không thấy giải trình lương, lý do tuyển hay lịch sử duyệt.
- `JOB_POSTINGS_READ_ALL` đơn lẻ (HIRING_MANAGER, APPROVER; CANDIDATE cũng có trong seed nhưng không tài khoản nào mang vai trò này) nhận 403 ở mọi API trên: không xem bản nháp. Họ sẽ xem tin đã xuất bản qua cổng tuyển dụng công khai (task 309), giống trang giới thiệu công ty. INTERVIEWER và tài khoản không có vai trò cũng nhận 403.
- Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi, backend kiểm lại hạn token, phiên, quyền ghi và quyền đọc sau mỗi lần chờ khóa (mục "Khóa và đồng thời").

## Phạm vi dữ liệu (task 303)

- Người có `ALL` (ADMIN, HR_MANAGER) soạn và xem tin của mọi yêu cầu.
- Người chỉ có `SCOPED` (RECRUITER) chỉ soạn và xem tin của yêu cầu mà mình là recruiter chính **hoặc** recruiter hỗ trợ ([API phân công recruiter](requisition-recruiters.md), bảng `requisition_recruiters` của V16). Danh sách tin và danh sách yêu cầu nguồn đưa điều kiện này vào câu SQL, nên tin của yêu cầu khác không được đọc ra khỏi database. Tạo, xem chi tiết và sửa trả 403 `FORBIDDEN` khi yêu cầu của tin không được phân công cho người gọi.
- UUID không tồn tại trả 404 `JOB_POSTING_NOT_FOUND` (xem, sửa) hoặc 400 `INVALID_JOB_POSTING_REQUISITION` (tạo) với mọi người gọi, trước khi kiểm phạm vi. UUID là ngẫu nhiên nên không đoán được mã tin của người khác.
- Người tạo tin không quyết định quyền. Khi HR bàn giao recruiter chính hoặc bỏ một recruiter hỗ trợ, người cũ mất quyền với tin ngay ở lần gọi kế tiếp, còn người được giao tiếp nhận bản nháp. `createdById` vẫn giữ người tạo.
- Khi ghi, phạm vi được kiểm trong lúc giữ khóa dòng yêu cầu tuyển dụng. Mọi thay đổi nhóm recruiter cũng khóa dòng này trước, nên một lần bàn giao commit trong lúc thao tác đang chờ khóa sẽ được thấy, và nhóm không đổi được cho tới khi thao tác lưu xong.

Quyết định của backend (chờ BA/PO xác nhận):

- Recruiter hỗ trợ có cùng quyền với recruiter chính đối với tin của yêu cầu.
- Tin ngoài phạm vi trả 403 theo quy ước chung của [thiết kế phân quyền](../architecture/authorization.md), không giấu bằng 404.

## Chọn yêu cầu nguồn

`GET /job-postings/source-requisitions?page=0&size=20` trả **200** các yêu cầu tuyển dụng còn tạo được tin: trạng thái `APPROVED` (`JobPostingService.SOURCE_STATUSES`), chưa có tin, và với người `SCOPED` chỉ yêu cầu được phân công cho mình. Sắp xếp yêu cầu tạo sau lên trước (`createdAt` giảm dần, rồi UUID).

```json
{
  "items": [
    {
      "requisitionId": "00000000-0000-0000-0000-000000000011",
      "positionId": "00000000-0000-0000-0000-000000000003",
      "positionName": "Lập trình viên Java",
      "departmentId": "00000000-0000-0000-0000-000000000002",
      "departmentName": "Phòng Công nghệ",
      "headcount": 2,
      "neededBy": "2026-12-01"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

UUID trong ví dụ chỉ minh họa. Danh sách chỉ có đủ thông tin để chọn: không có mô tả công việc, lương hay giải trình. Nội dung đầy đủ hiện trên bản nháp sau khi tạo. Yêu cầu biến mất khỏi danh sách ngay khi đã có tin. API này thay cho `GET /requisitions?status=APPROVED` mà frontend đang gọi: recruiter nhận danh sách rỗng hoặc 403 ở API đó.

`page` từ 0 (mặc định 0), `size` từ 1 đến 100 (mặc định 20), `page × size` không vượt 2.147.483.647; sai thì 400 `VALIDATION_ERROR` "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.". `page`/`size` không phải số nguyên trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ.".

## Tạo tin từ yêu cầu đã duyệt

`POST /job-postings` tạo một tin ở trạng thái `DRAFT`, `revision` 1, người tạo là người gọi (lấy từ token), trả **201** với tin vừa tạo (cùng cấu trúc như chi tiết ở mục "Danh sách và chi tiết").

```json
{
  "requisitionId": "00000000-0000-0000-0000-000000000011",
  "title": "Lập trình viên Java (Hà Nội)",
  "jobDescription": null,
  "candidateRequirements": null,
  "benefits": "Bảo hiểm đầy đủ, 13 tháng lương.",
  "workLocationId": "00000000-0000-0000-0000-000000000021",
  "employmentTypeId": "00000000-0000-0000-0000-000000000022",
  "applicationDeadline": "2026-11-30",
  "showSalary": true
}
```

UUID trong ví dụ chỉ minh họa. Body tối thiểu là `{"requisitionId": "..."}`.

| Trường | Quy tắc |
|---|---|
|requisitionId|Bắt buộc ("Vui lòng chọn yêu cầu tuyển dụng đã duyệt."). UUID của một yêu cầu tuyển dụng; không có yêu cầu đó thì 400 `INVALID_JOB_POSTING_REQUISITION` với `fieldErrors.requisitionId` "Yêu cầu tuyển dụng không tồn tại."|
|title|Không bắt buộc. Tối đa 255 ký tự, không chứa ký tự NUL. Không gửi, `null` hoặc chỉ có khoảng trắng thì lấy **tên chức danh** của yêu cầu|
|jobDescription|Không bắt buộc. Tối đa 10.000 ký tự, không chứa ký tự NUL. Không gửi, `null` hoặc chỉ có khoảng trắng thì **chép** mô tả công việc của yêu cầu|
|candidateRequirements|Như `jobDescription`, chép yêu cầu ứng viên của yêu cầu|
|benefits|Không bắt buộc. Tối đa 10.000 ký tự, không chứa ký tự NUL. Không bao giờ chép từ yêu cầu; trống thì lưu `null`|
|workLocationId|Không bắt buộc. UUID của một giá trị danh mục `WORK_LOCATION` (địa điểm làm việc, [API danh mục tuyển dụng](recruitment-catalogs.md)); không có hoặc thuộc danh mục khác thì 400 `INVALID_JOB_POSTING_WORK_LOCATION`|
|employmentTypeId|Không bắt buộc. UUID của một giá trị danh mục `EMPLOYMENT_TYPE` (hình thức làm việc); không có hoặc thuộc danh mục khác thì 400 `INVALID_JOB_POSTING_EMPLOYMENT_TYPE`|
|applicationDeadline|Không bắt buộc. Hạn nộp hồ sơ dạng `yyyy-MM-dd`, tính cả ngày hạn; ngày không tồn tại (`2026-02-30`) là `INVALID_JSON`. Task 304 thêm quy tắc không trước ngày hôm nay|
|showSalary|Không bắt buộc. `true` thì ứng viên sẽ thấy lương đề xuất đã duyệt của yêu cầu; không gửi hoặc `null` là `false`|

Kế thừa nội dung:

- Tin giữ **bản sao riêng** của tiêu đề, mô tả công việc và yêu cầu ứng viên. Backend đọc yêu cầu bằng một truy vấn JDBC chỉ lấy các cột cần chép và không bao giờ ghi vào yêu cầu, nên sửa tin sau này không đổi yêu cầu đã duyệt. Văn bản được chép đúng từng ký tự, kể cả xuống dòng và thụt đầu dòng.
- Văn bản có nội dung trong body được giữ nguyên như người dùng nhập. Gửi văn bản thì thay phần kế thừa tương ứng, nên frontend có thể tạo tin với nội dung đã sửa ngay lần lưu đầu.
- Chức danh, phòng ban, số lượng và lương **không** chép sang tin: chúng được đọc từ yêu cầu ở mỗi lần xem, vì yêu cầu đã duyệt không sửa được nữa.
- Bản nháp lưu được khi chưa có địa điểm, hình thức làm việc, hạn nộp. Gửi duyệt (task 308) mới bắt buộc ba chi tiết này.

Trường ngoài hợp đồng, kể cả `id`, `status`, `revision`, `createdBy`, `createdAt`, `publishedAt`, `salaryMin`, `salaryMax`, `positionTitle`, `departmentId`, `headcount`, `level`, `skills`, `currency`, `salaryType`, `isSalaryNegotiable`, `publishInternal`, `publishCareerPage`, bị từ chối với 400 `INVALID_JSON`: trạng thái, phiên bản và người tạo do server quyết định; chức danh, phòng ban, số lượng và lương đọc từ yêu cầu.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (401 nếu chưa đăng nhập, 403 nếu thiếu quyền ghi tin).
2. Body: `INVALID_JSON`, rồi từng trường (`VALIDATION_ERROR` với mọi trường sai trong `fieldErrors`): thiếu `requisitionId`, văn bản quá dài hoặc chứa ký tự NUL.
3. Khóa tài khoản người gọi rồi phiên; kiểm hạn token, phiên, quyền ghi **và** quyền đọc tin (401 `SESSION_INVALID` hoặc 403), trước mọi tra cứu.
4. Khóa dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`) rồi kiểm lại người gọi; không có yêu cầu thì 400 `INVALID_JOB_POSTING_REQUISITION` (lỗi form của `requisitionId`, như mã không tồn tại là 404 trước 403). Câu khóa chỉ đọc bảng yêu cầu, tên chức danh (tiêu đề mặc định) đọc bằng truy vấn con trên dòng đã khóa: nếu trong lúc chờ khóa có người lưu yêu cầu sang chức danh khác, yêu cầu vẫn được tìm thấy với chức danh mới và trả lỗi theo trạng thái (bước 6), không thành "không tồn tại".
5. Phạm vi: người `SCOPED` không được phân công yêu cầu này thì 403 `FORBIDDEN`.
6. Yêu cầu không ở trạng thái `APPROVED`: 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
7. Yêu cầu đã có tin: 409 `JOB_POSTING_ALREADY_EXISTS`.
8. Địa điểm rồi hình thức làm việc, nếu có gửi, phải là giá trị của đúng danh mục: 400 `INVALID_JOB_POSTING_WORK_LOCATION`, rồi `INVALID_JOB_POSTING_EMPLOYMENT_TYPE`. Giá trị được đọc và khóa `FOR SHARE`.
9. Kiểm lại người gọi và phạm vi lần nữa (bước 8 có thể phải chờ HR đang sửa danh mục), rồi lưu. Người gọi trở thành người soạn đầu tiên của tin.

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 3 trở đi. Lỗi nào cũng không lưu gì.

## Danh sách và chi tiết

`GET /job-postings/{id}` trả **200** với tin:

```json
{
  "id": "00000000-0000-0000-0000-000000000031",
  "requisitionId": "00000000-0000-0000-0000-000000000011",
  "status": "DRAFT",
  "revision": 1,
  "title": "Lập trình viên Java",
  "positionId": "00000000-0000-0000-0000-000000000003",
  "positionName": "Lập trình viên Java",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "departmentName": "Phòng Công nghệ",
  "headcount": 2,
  "jobDescription": "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java.",
  "benefits": null,
  "workLocationId": null,
  "workLocationName": null,
  "employmentTypeId": null,
  "employmentTypeName": null,
  "applicationDeadline": null,
  "showSalary": false,
  "salaryMin": 20000000,
  "salaryMax": 22000000,
  "createdById": "00000000-0000-0000-0000-000000000001",
  "createdByName": "Nguyễn Văn A",
  "createdAt": "2026-10-07T08:00:00.123456Z",
  "updatedAt": "2026-10-07T08:00:00.123456Z",
  "actions": { "canEdit": true }
}
```

| Trường | Ý nghĩa |
|---|---|
|status, revision|Trạng thái (hiện chỉ `DRAFT`) và số phiên bản: 1 khi tạo, tăng 1 sau mỗi lần lưu. Gửi lại `revision` đang xem làm `expectedRevision` khi sửa|
|title, jobDescription, candidateRequirements, benefits|Nội dung riêng của tin|
|positionId, positionName, departmentId, departmentName, headcount|Đọc từ yêu cầu nguồn, chỉ đọc|
|workLocationId/Name, employmentTypeId/Name|Giá trị danh mục đã chọn và tên hiện tại; `null` khi chưa chọn|
|applicationDeadline, showSalary|Hạn nộp hồ sơ (`null` khi chưa chọn) và có cho ứng viên thấy lương không|
|salaryMin, salaryMax|Lương đề xuất đã duyệt của yêu cầu (`proposedSalaryMin`/`proposedSalaryMax`, số nguyên đồng VND mỗi người mỗi tháng). **Chỉ đọc**: tin không lưu số tiền nào, chỉ lưu `showSalary`. Người soạn thấy đúng mức lương sẽ công khai nếu bật `showSalary`|
|createdById, createdByName, createdAt, updatedAt|Người tạo (id và họ tên, không có email), thời điểm tạo và lần sửa nội dung gần nhất (UTC, micro giây)|
|actions.canEdit|`true` khi `PUT` sẽ qua được phần kiểm trạng thái: tin ở trạng thái sửa được (`DRAFT`) và yêu cầu nguồn vẫn `APPROVED`. Người đọc được tin cũng sửa được tin, nên cờ này không phụ thuộc người xem. Task 308 thêm các cờ gửi duyệt, duyệt, xuất bản|

Tên chức danh, phòng ban, giá trị danh mục và người tạo được đọc lúc trả, nên đổi tên ở danh mục hiện tên mới. Thứ tự kiểm: quyền (401/403), tin tồn tại (404 `JOB_POSTING_NOT_FOUND`), phạm vi (403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.

`GET /job-postings?status=DRAFT&page=0&size=20` trả **200** các tin người gọi được xem theo mục "Phạm vi dữ liệu": `{items, page, size, totalElements, totalPages}`. Mỗi item gồm `id`, `requisitionId`, `title`, `positionName`, `departmentName`, `status`, `revision`, `applicationDeadline`, `createdByName`, `createdAt`, `updatedAt`; văn bản và lương chỉ có ở chi tiết. Sắp xếp tin tạo sau lên trước (`createdAt` giảm dần, rồi UUID); lưu tin không đổi vị trí của nó. `status` không bắt buộc, đúng chữ hoa (hiện chỉ `DRAFT`); giá trị khác (ví dụ `draft`) trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ.". `page`, `size` như danh sách yêu cầu nguồn, lời nhắn "Trang hoặc số lượng tin tuyển dụng không hợp lệ.". Recruiter không được phân công yêu cầu nào nhận `items` rỗng với `totalElements` và `totalPages` bằng 0.

## Lưu bản nháp (PUT)

`PUT /job-postings/{id}` lưu lại tin với nội dung mới, trả **200** cùng tin sau khi lưu.

```json
{
  "expectedRevision": 1,
  "title": "Lập trình viên Java",
  "jobDescription": "Phát triển API tuyển dụng.\n- Spring Boot",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java.",
  "benefits": null,
  "workLocationId": "00000000-0000-0000-0000-000000000021",
  "employmentTypeId": null,
  "applicationDeadline": null,
  "showSalary": false
}
```

| Trường | Quy tắc |
|---|---|
|expectedRevision|Bắt buộc ("Thiếu số phiên bản tin tuyển dụng đang xem."), số nguyên từ 1 ("Số phiên bản tin tuyển dụng không hợp lệ."). Là `revision` của tin mà người soạn đang xem|
|title|Bắt buộc ("Tiêu đề tin không được để trống."), tối đa 255 ký tự, không chứa ký tự NUL|
|jobDescription|Bắt buộc ("Mô tả công việc không được để trống."), tối đa 10.000 ký tự, không chứa ký tự NUL|
|candidateRequirements|Bắt buộc ("Yêu cầu ứng viên không được để trống."), như `jobDescription`|
|benefits, workLocationId, employmentTypeId, applicationDeadline|Như khi tạo, không bắt buộc|
|showSalary|Bắt buộc ("Vui lòng chọn có hiển thị mức lương hay không.")|

- `PUT` **thay toàn bộ** nội dung: trường không bắt buộc mà không gửi hoặc gửi `null` bị xóa, nên giao diện phải gửi lại mọi trường đang có trên form. Nhờ vậy bản nháp vẫn lưu được trong lúc viết dở.
- Văn bản chỉ có khoảng trắng được coi như không gửi: tiêu đề, mô tả công việc, yêu cầu ứng viên nhận lời nhắn "không được để trống", quyền lợi được lưu `null`. Không có bước kế thừa lại từ yêu cầu khi sửa.
- `requisitionId` không đổi được (gửi lên là `INVALID_JSON`), cùng mọi trường ngoài hợp đồng như khi tạo.
- `expectedRevision` khác `revision` hiện tại của tin trả 409 `JOB_POSTING_CHANGED` và không lưu gì: một tab khác hoặc người khác đã lưu tin sau khi người này tải nó. Giao diện tải lại tin rồi cho người dùng lưu lại.
- Lưu thành công thì `revision` tăng 1, `updatedAt` là thời điểm lưu; `createdById`, `createdAt` giữ nguyên.
- Người tạo và mọi người đã lưu thành công được ghi vào bảng `job_posting_authors` (mỗi người một lần). Bảng này dùng cho quy tắc "người đã soạn hoặc sửa tin không tự duyệt tin đó" của task 308. Lần lưu bị từ chối không ghi ai.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (401/403); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với mọi trường sai trong `fieldErrors`.
3. Khóa tài khoản người gọi rồi phiên; kiểm hạn token, phiên, quyền ghi và quyền đọc tin (401 `SESSION_INVALID` hoặc 403), trước mọi tra cứu.
4. Tin tồn tại: 404 `JOB_POSTING_NOT_FOUND`.
5. Khóa dòng yêu cầu tuyển dụng của tin, kiểm lại người gọi; khóa dòng tin, kiểm lại người gọi.
6. Phạm vi: 403 `FORBIDDEN`.
7. Tin ở trạng thái sửa được (`DRAFT`), nếu không thì 409 `JOB_POSTING_NOT_EDITABLE`.
8. `expectedRevision` đúng `revision` hiện tại, nếu không thì 409 `JOB_POSTING_CHANGED`.
9. Yêu cầu nguồn vẫn `APPROVED`, nếu không thì 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
10. Địa điểm rồi hình thức làm việc như bước 8 của tạo (400).
11. Kiểm lại người gọi và phạm vi, rồi lưu; người gọi được ghi là người soạn của tin.

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 3 trở đi. Lỗi nào cũng không đổi dòng nào.

## Khóa và đồng thời

- Mọi thao tác ghi khóa theo cùng một thứ tự: tài khoản người gọi, phiên, dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`), dòng tin (`FOR NO KEY UPDATE`, `@Lock(PESSIMISTIC_WRITE)` của Hibernate), rồi giá trị danh mục được chọn (`FOR SHARE`, địa điểm trước, hình thức sau). Tin không bao giờ bị khóa trước yêu cầu của nó: khi sửa, backend đọc `requisitionId` của tin (không đổi được) mà không khóa, khóa yêu cầu rồi mới khóa tin. Thứ tự này giống các thao tác ghi yêu cầu và phân công recruiter, nên không gây khóa chết với chúng.
- Mỗi lần chờ khóa, token có thể hết hạn hay quyền có thể bị gỡ, nên người gọi được kiểm lại sau mỗi khóa (quyền đọc lại từ database): token hết hạn, phiên bị thu hồi hay tài khoản bị khóa trả 401 `SESSION_INVALID`; bị gỡ quyền trả 403; bị bỏ khỏi nhóm recruiter trả 403. Không lưu gì.
- Hai lần tạo tin cho cùng một yêu cầu chạy lần lượt nhờ khóa dòng yêu cầu; lần sau trả 409 `JOB_POSTING_ALREADY_EXISTS`. Ràng buộc unique `job_postings_requisition_id_key` là lớp chặn cuối.
- Hai lần lưu cùng một tin chạy lần lượt nhờ khóa dòng tin; lần sau mang `expectedRevision` cũ nên trả 409 `JOB_POSTING_CHANGED`, không ghi đè thay đổi của lần trước.
- Giá trị danh mục được khóa `FOR SHARE` tới khi lưu xong: HR không xóa hay ngừng dùng được giá trị đó giữa lúc kiểm và lúc lưu. Một lần xóa commit trong lúc thao tác đang chờ được thấy và trả 400 `INVALID_JOB_POSTING_*`, không phải 500 từ khóa ngoại.
- Yêu cầu tuyển dụng chỉ được đọc qua JDBC (không qua entity `RecruitmentRequisition`), nên code của tin không thể vô tình ghi vào yêu cầu. Bị khóa trong lúc tạo hay sửa tin, yêu cầu vẫn không đổi `updatedAt` hay bất kỳ cột nào.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|Body: thiếu `requisitionId` (tạo), thiếu `expectedRevision`, `title`, `jobDescription`, `candidateRequirements`, `showSalary` (sửa), văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`. Tham số: `{id}` không phải UUID, `status` sai, `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|`INVALID_JSON`|JSON sai hoặc thiếu body; UUID hay ngày sai định dạng (ví dụ `2026-02-30`); có trường ngoài hợp đồng, kể cả `requisitionId` khi sửa|
|400|`INVALID_JOB_POSTING_REQUISITION`|Không có yêu cầu tuyển dụng với `requisitionId`; `fieldErrors.requisitionId`|
|400|`INVALID_JOB_POSTING_WORK_LOCATION`|`workLocationId` không phải giá trị của danh mục `WORK_LOCATION` (không tồn tại hoặc thuộc danh mục khác); `fieldErrors.workLocationId`|
|400|`INVALID_JOB_POSTING_EMPLOYMENT_TYPE`|`employmentTypeId` không phải giá trị của danh mục `EMPLOYMENT_TYPE`; `fieldErrors.employmentTypeId`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn, phiên bị thu hồi, tài khoản bị khóa, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền ghi tin; có quyền ghi nhưng thiếu quyền đọc tin; `JOB_POSTINGS_READ_ALL` đơn lẻ; recruiter với yêu cầu hoặc tin không được phân công; route không khai báo (ví dụ `POST /job-postings/draft`, `DELETE` hay `PATCH /job-postings/{id}`)|
|404|`JOB_POSTING_NOT_FOUND`|Không có tin với `{id}` ("Không tìm thấy tin tuyển dụng.")|
|409|`JOB_POSTING_REQUISITION_NOT_APPROVED`|Yêu cầu không ở trạng thái `APPROVED`, khi tạo và khi sửa ("Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.")|
|409|`JOB_POSTING_ALREADY_EXISTS`|Yêu cầu đã có tin ("Yêu cầu tuyển dụng này đã có tin tuyển dụng.")|
|409|`JOB_POSTING_NOT_EDITABLE`|Sửa tin không ở trạng thái sửa được ("Chỉ sửa được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa."); xảy ra từ khi task 308 thêm trạng thái duyệt|
|409|`JOB_POSTING_CHANGED`|`expectedRevision` cũ ("Tin tuyển dụng đã thay đổi (có thể người khác vừa sửa hoặc xử lý). Vui lòng tải lại trước khi thao tác.")|
|409|`RECRUITMENT_CATALOG_ITEM_IN_USE`|Ở [API danh mục tuyển dụng](recruitment-catalogs.md): xóa giá trị địa điểm hoặc hình thức làm việc đang được tin dùng|

Ví dụ lỗi một trường:

```json
{
  "code": "INVALID_JOB_POSTING_WORK_LOCATION",
  "message": "Địa điểm làm việc không tồn tại.",
  "fieldErrors": {
    "workLocationId": "Địa điểm làm việc không tồn tại."
  }
}
```

## Ghi chú cho frontend

Frontend nhánh `UI` (`src/services/jobPostingService.ts`, `JobPostingForm.tsx`) cần sửa:

| Frontend hiện tại | Backend |
|---|---|
|Chọn yêu cầu bằng `GET /requisitions?status=APPROVED&size=100` rồi `GET /requisitions/{id}`; recruiter nhận danh sách rỗng hoặc 403 rồi bị đưa sang `/unauthorized`|`GET /job-postings/source-requisitions?size=100`, rồi `POST /job-postings {requisitionId, …}`. Server tự chép tiêu đề, mô tả công việc, yêu cầu ứng viên; không tự ánh xạ trường của yêu cầu ở client (task 301)|
|`saveDraft` gửi `POST /job-postings/draft` với id do client tạo (`jp-draft-…`)|Lần lưu đầu `POST /job-postings` (201, server cấp UUID); các lần sau `PUT /job-postings/{id}` kèm `expectedRevision`. `/job-postings/draft` trả 403 (không khai báo). Không bao giờ gửi `id` (task 300)|
|"Đăng tin" gửi `POST /job-postings` với `status: 'PUBLISHED'`|Chỉ lưu bản nháp; gửi duyệt là `POST /job-postings/{id}/submit` của task 308, người soạn không tự xuất bản (task 300)|
|Body là cả `JobPostingData`|`POST`: `requisitionId` và tùy chọn `title`, `jobDescription`, `candidateRequirements`, `benefits`, `workLocationId`, `employmentTypeId`, `applicationDeadline`, `showSalary`. `PUT`: như vậy nhưng không có `requisitionId`, thêm `expectedRevision`. Trường khác trả `INVALID_JSON` (task 300)|
|`requirements`, `deadline`, `workLocation` (chuỗi), `employmentType` (mã), `isSalaryNegotiable` (ngược nghĩa)|`candidateRequirements`, `applicationDeadline`, `workLocationId`/`employmentTypeId` lấy từ `GET /recruitment-catalogs/{WORK_LOCATION\|EMPLOYMENT_TYPE}/items?active=true` (tên trả về ở `…Name`), `showSalary` (`true` là hiện lương) (task 300)|
|Hạn nộp điền sẵn từ `req.neededBy`|Để trống; hạn nộp và ngày cần người là hai việc khác nhau (task 301)|
|Lương nhập được theo triệu, USD, `salaryType`|`salaryMin`/`salaryMax` là lương đề xuất đã duyệt, đồng VND, **chỉ đọc**; chỉ `showSalary` sửa được; không có tiền tệ hay kiểu lương (task 300)|
|`positionTitle`, phòng ban, số lượng, cấp bậc sửa được|`positionName`, `departmentName`, `headcount` chỉ đọc; không có cấp bậc (task 300)|
|Khối `catch` giả thành công, tên người cứng ("Trần Thị Thu Hà")|Bỏ; hiện `message` của lỗi, gắn `fieldErrors` (`title`, `jobDescription`, `candidateRequirements`, `benefits`, `workLocationId`, `employmentTypeId`, `applicationDeadline`, `showSalary`, `requisitionId`, `expectedRevision`) vào ô tương ứng; dùng `createdByName`|
|Route và menu tin tuyển dụng dựa vào `REQUISITIONS_READ_*` hoặc `can('view','job_posting')` (`JOB_POSTINGS_READ_*`)|Mọi màn hình nội bộ của tin (danh sách, chi tiết, form) dựa vào `JOB_POSTINGS_WRITE_ALL` hoặc `JOB_POSTINGS_WRITE_SCOPED` (`can('edit','job_posting')`). HIRING_MANAGER và APPROVER (chỉ `JOB_POSTINGS_READ_ALL`) không được thấy các link này, nếu không sẽ bị 403 và bị đưa sang `/unauthorized` (task 300)|

Hiện nút sửa theo `actions.canEdit`. Gặp 409 `JOB_POSTING_CHANGED` thì báo và tải lại tin. Hiển thị mọi văn bản của tin dạng chữ, không dùng `dangerouslySetInnerHTML`, `v-html` hay `innerHTML`. Nhãn trạng thái: `DRAFT` Nháp.

QA cần chuẩn bị: V10 không tạo sẵn giá trị danh mục, nên HR phải tạo giá trị `WORK_LOCATION` và `EMPLOYMENT_TYPE` trước; HR phải phân công recruiter (`POST /requisitions/{id}/assign`) trước khi recruiter thấy hay tạo được tin.

## Database và phạm vi

Dùng hai bảng của V21 (task 302): `job_postings` và `job_posting_authors`, xem [database](../database/README.md). Task 303 không thêm migration và không thêm mã quyền: dùng `JOB_POSTINGS_*` có sẵn từ V3 và `requisition_recruiters` của V16 làm phạm vi `SCOPED`.

- Địa điểm và hình thức làm việc là khóa ngoại thường (`ON DELETE RESTRICT`, không `DEFERRABLE`) tới `recruitment_catalog_items`; database không biết loại danh mục, nên API kiểm loại bằng `RecruitmentCatalogItemRepository.findActiveForShare` (đọc kèm `FOR SHARE`). Task 303 chỉ từ chối giá trị không tồn tại hoặc thuộc danh mục khác; giá trị đã ngừng dùng (`active = false`) vẫn được nhận cho tới task 304.
- Tin không lưu số tiền lương. Chức danh, phòng ban, số lượng và lương đề xuất đọc từ yêu cầu bằng một câu SQL chung cho chi tiết, danh sách và response của mọi thao tác ghi (`JobPostingReadRepository.SELECT_ROW`).
- Code của tin nằm ở gói `vn.ttcs.recruitment.jobposting`. Trạng thái của yêu cầu được tạo tin là `JobPostingService.SOURCE_STATUSES` (`APPROVED`); trạng thái của tin còn sửa được là `JobPostingService.EDITABLE_STATUSES` (`DRAFT`). Khi thêm trạng thái mới cho yêu cầu hay cho tin, `JobPostingSourceStatusTest` và `JobPostingStatusTest` không biên dịch cho tới khi trạng thái đó được xếp nhóm.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md). Gồm:

- `JOB_POSTINGS_*_SCOPED` của Recruiter là tin của yêu cầu mà người đó là recruiter chính hoặc hỗ trợ, kiểm lại ở mỗi lần gọi; bàn giao xong thì người cũ mất quyền ngay;
- tạo tin không cần quyền đọc yêu cầu tuyển dụng; recruiter đọc bản sao mô tả công việc, yêu cầu ứng viên và lương đề xuất của yêu cầu qua tin dù chưa đọc được yêu cầu (task 285);
- `JOB_POSTINGS_READ_ALL` đơn lẻ (HIRING_MANAGER, APPROVER) không xem bản nháp, chỉ xem cổng công khai sau này, giống trang giới thiệu công ty;
- mỗi yêu cầu chỉ có một tin;
- không có trạng thái Từ chối cho tin; người duyệt sẽ dùng "yêu cầu chỉnh sửa" (task 308);
- chỉ bản nháp sửa được; tin đã gửi duyệt, đã duyệt hoặc đã xuất bản không sửa được (task 308); gỡ hay đóng tin khi đóng yêu cầu là task 292;
- lương công khai là lương đề xuất đã duyệt của yêu cầu (tối thiểu, tối đa), chỉ hiện khi chọn, mặc định không hiện; tin không nhập được mức lương khác;
- trường của tin: tiêu đề (mặc định tên chức danh), quyền lợi (không bắt buộc); không có kỹ năng, cấp bậc, kênh đăng.
