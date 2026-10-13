# API tin tuyển dụng

Phạm vi TKNHTTDNB1-302 (lưu bản nháp và thông tin công khai), TKNHTTDNB1-303 (API tạo, sửa bản nháp), TKNHTTDNB1-304 (kiểm tra dữ liệu trước khi lưu), story TKNHTTDNB1-38 (S3-09). URL dùng tiền tố `/api/v1`, header `Authorization: Bearer …`; mọi response JSON thành công và các lỗi `JOB_POSTING_*` dùng `Cache-Control: no-store`.

Tiêu chí của story S3-09:

- "Nội dung mô tả công việc được kế thừa từ yêu cầu, sửa lại được cho phù hợp ngôn ngữ tuyển dụng": đã có (303, 304). Tin chép mô tả công việc và yêu cầu ứng viên của yêu cầu một lần khi tạo, rồi chỉ sửa trên tin; yêu cầu không bao giờ bị ghi (task 304 kiểm bằng test, mục "Sửa JD của tin không đổi JD của yêu cầu").
- "Khai báo địa điểm, hình thức làm việc, hạn nhận hồ sơ, có hiển thị mức lương hay không": đã có (303, 304). Task 304 kiểm hạn nộp không ở quá khứ, giá trị danh mục còn dùng và văn bản thuần (mục "Kiểm tra dữ liệu trước khi lưu").
- "Chỉ yêu cầu ở trạng thái Đã duyệt mới tạo được tin": đã có (303), mục "Tạo tin từ yêu cầu đã duyệt".

Gửi duyệt, duyệt và xuất bản tin (story S3-10, task 308), bản xem trước và cổng tuyển dụng công khai (task 309), người xuất bản, thời điểm công khai và lịch sử duyệt (task 310) nằm ở [API duyệt và xuất bản tin tuyển dụng](job-posting-publication.md). Tài liệu này mô tả phần soạn tin; từ task 308, tin còn sửa được ở trạng thái `DRAFT` và `REVISION_REQUESTED` (HR yêu cầu chỉnh sửa).

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /job-postings`|`JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc `JOB_POSTINGS_READ_ALL` hoặc `JOB_POSTINGS_READ_SCOPED`|ADMIN, HR_MANAGER (mọi yêu cầu đã duyệt); RECRUITER (yêu cầu được phân công)|
|`GET /job-postings/source-requisitions`|Như dòng trên|Như dòng trên|
|`GET /job-postings`, `GET /job-postings/{id}`|Như dòng trên: quyền **ghi** kèm quyền đọc, không phải chỉ quyền đọc|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|
|`PUT /job-postings/{id}`|Như dòng trên|Như dòng `GET`|

- Mọi API nội bộ của tin dành cho **người soạn tin**. Matcher ở `SecurityConfiguration` cho `JOB_POSTINGS_WRITE_ALL` hoặc `JOB_POSTINGS_WRITE_SCOPED`; `JobPostingService` còn đòi quyền đọc `JOB_POSTINGS_READ_*`, vì quyền ghi không bao gồm quyền đọc và mọi thao tác ghi trả về cả tin. Người chỉ có quyền ghi bị 403 trước khi backend tra cứu gì, kể cả với `{id}` hay `requisitionId` không tồn tại. Theo seed hiện tại, ai có quyền ghi tin cũng có quyền đọc cùng phạm vi.
- Phạm vi là `ALL` khi cả quyền ghi lẫn quyền đọc là `ALL`; còn lại là `SCOPED` (mục "Phạm vi dữ liệu").
- Tạo tin **không** cần quyền đọc yêu cầu tuyển dụng (`REQUISITIONS_READ_*`); phạm vi tin là phân công recruiter, cùng quy tắc mà từ task 285 cũng cho recruiter được phân công đọc chính yêu cầu. Đây là khác biệt có chủ ý với sao chép yêu cầu (task 278), vốn đòi quyền đọc bản gốc. Qua tin, recruiter thấy bản sao mô tả công việc, yêu cầu ứng viên, chức danh, phòng ban, số lượng và lương đề xuất đã duyệt của yêu cầu; không thấy giải trình lương, lý do tuyển hay lịch sử duyệt (từ task 285, recruiter được phân công xem những phần đó qua `GET /requisitions/{id}` và tiến độ duyệt).
- `JOB_POSTINGS_READ_ALL` đơn lẻ (HIRING_MANAGER, APPROVER; CANDIDATE cũng có trong seed nhưng không tài khoản nào mang vai trò này) nhận 403 ở mọi API trên: không xem bản nháp. Họ xem tin đã xuất bản qua cổng tuyển dụng công khai (task 309, mục "Tin công khai" của [API duyệt và xuất bản](job-posting-publication.md)), giống trang giới thiệu công ty. INTERVIEWER và tài khoản không có vai trò cũng nhận 403.
- Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi, backend kiểm lại hạn token, phiên, quyền ghi và quyền đọc sau mỗi lần chờ khóa (mục "Khóa và đồng thời").

## Phạm vi dữ liệu (task 303)

- Người có `ALL` (ADMIN, HR_MANAGER) soạn và xem tin của mọi yêu cầu.
- Người chỉ có `SCOPED` (RECRUITER) chỉ soạn và xem tin của yêu cầu mà mình là recruiter chính **hoặc** recruiter hỗ trợ ([API phân công recruiter](requisition-recruiters.md), bảng `requisition_recruiters` của V16). Danh sách tin và danh sách yêu cầu nguồn đưa điều kiện này vào câu SQL, nên tin của yêu cầu khác không được đọc ra khỏi database. Từ task 285, câu SQL và kiểm tra từng tin lấy từ `RecruiterAssignmentAccess`, quy tắc phân công dùng chung với việc xem yêu cầu tuyển dụng; hành vi của tin không đổi. Tạo, xem chi tiết và sửa trả 403 `FORBIDDEN` khi yêu cầu của tin không được phân công cho người gọi.
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

UUID trong ví dụ chỉ minh họa. Danh sách chỉ có đủ thông tin để chọn: không có mô tả công việc, lương hay giải trình. Nội dung đầy đủ hiện trên bản nháp sau khi tạo. Yêu cầu biến mất khỏi danh sách ngay khi đã có tin. API này thay cho `GET /requisitions?status=APPROVED` mà frontend đang gọi: trước task 285 recruiter nhận danh sách rỗng hoặc 403 ở API đó; từ task 285 API đó trả yêu cầu đã duyệt mà recruiter được phân công nhưng gồm cả yêu cầu đã có tin.

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
|title|Không bắt buộc. Tối đa 255 ký tự, văn bản thuần **trên một dòng** (mục "Chỉ nhận văn bản thuần"); khoảng trắng ở hai đầu bị bỏ. Không gửi, `null` hoặc chỉ có khoảng trắng thì lấy **tên chức danh** của yêu cầu|
|jobDescription|Không bắt buộc. Tối đa 10.000 ký tự, văn bản thuần, được xuống dòng và dùng tab. Không gửi, `null` hoặc chỉ có khoảng trắng thì **chép** mô tả công việc của yêu cầu|
|candidateRequirements|Như `jobDescription`, chép yêu cầu ứng viên của yêu cầu|
|benefits|Không bắt buộc. Tối đa 10.000 ký tự, văn bản thuần như `jobDescription`. Không bao giờ chép từ yêu cầu; trống thì lưu `null`|
|workLocationId|Không bắt buộc. UUID của một giá trị danh mục `WORK_LOCATION` (địa điểm làm việc, [API danh mục tuyển dụng](recruitment-catalogs.md)); không có hoặc thuộc danh mục khác thì 400 `INVALID_JOB_POSTING_WORK_LOCATION`; đã ngừng sử dụng (`active = false`) thì 400 `JOB_POSTING_WORK_LOCATION_INACTIVE`|
|employmentTypeId|Không bắt buộc. UUID của một giá trị danh mục `EMPLOYMENT_TYPE` (hình thức làm việc); không có hoặc thuộc danh mục khác thì 400 `INVALID_JOB_POSTING_EMPLOYMENT_TYPE`; đã ngừng sử dụng thì 400 `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`|
|applicationDeadline|Không bắt buộc. Hạn nộp hồ sơ dạng `yyyy-MM-dd`, tính cả ngày hạn; ngày không tồn tại (`2026-02-30`) là `INVALID_JSON`. Không được trước hôm nay theo giờ Việt Nam: 400 `JOB_POSTING_DEADLINE_IN_PAST` (mục "Hạn nộp hồ sơ")|
|showSalary|Không bắt buộc. `true` thì ứng viên sẽ thấy lương đề xuất đã duyệt của yêu cầu; không gửi hoặc `null` là `false`|

Kế thừa nội dung:

- Tin giữ **bản sao riêng** của tiêu đề, mô tả công việc và yêu cầu ứng viên. Backend đọc yêu cầu bằng một truy vấn JDBC chỉ lấy các cột cần chép và không bao giờ ghi vào yêu cầu, nên sửa tin sau này không đổi yêu cầu đã duyệt. Văn bản được chép giữ nguyên từng ký tự, kể cả thụt đầu dòng và dòng trống; từ task 304, xuống dòng kiểu Windows (`\r\n`) hoặc Mac cũ (`\r`) được đổi thành `\n`. Phần chép sang không bị kiểm văn bản thuần (mục "Nội dung kế thừa có thẻ HTML").
- Văn bản có nội dung trong body được lưu như người dùng nhập, sau khi tiêu đề bỏ khoảng trắng ở hai đầu và xuống dòng đổi thành `\n` (task 304). Gửi văn bản thì thay phần kế thừa tương ứng, nên frontend có thể tạo tin với nội dung đã sửa ngay lần lưu đầu.
- Chức danh, phòng ban, số lượng và lương **không** chép sang tin: chúng được đọc từ yêu cầu ở mỗi lần xem, vì yêu cầu đã duyệt không sửa được nữa.
- Bản nháp lưu được khi chưa có địa điểm, hình thức làm việc, hạn nộp. Gửi duyệt (task 308) mới bắt buộc ba chi tiết này.

Trường ngoài hợp đồng, kể cả `id`, `status`, `revision`, `createdBy`, `createdAt`, `publishedAt`, `salaryMin`, `salaryMax`, `positionTitle`, `departmentId`, `headcount`, `level`, `skills`, `currency`, `salaryType`, `isSalaryNegotiable`, `publishInternal`, `publishCareerPage`, bị từ chối với 400 `INVALID_JSON`: trạng thái, phiên bản và người tạo do server quyết định; chức danh, phòng ban, số lượng và lương đọc từ yêu cầu.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (401 nếu chưa đăng nhập, 403 nếu thiếu quyền ghi tin).
2. Body: `INVALID_JSON`, rồi từng trường (`VALIDATION_ERROR` với mọi trường sai trong `fieldErrors`): thiếu `requisitionId`, văn bản quá dài hoặc không phải văn bản thuần.
3. Khóa tài khoản người gọi rồi phiên; kiểm hạn token, phiên, quyền ghi **và** quyền đọc tin (401 `SESSION_INVALID` hoặc 403), trước mọi tra cứu.
4. Khóa dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`) rồi kiểm lại người gọi; không có yêu cầu thì 400 `INVALID_JOB_POSTING_REQUISITION` (lỗi form của `requisitionId`, như mã không tồn tại là 404 trước 403). Câu khóa chỉ đọc bảng yêu cầu, tên chức danh (tiêu đề mặc định) đọc bằng truy vấn con trên dòng đã khóa: nếu trong lúc chờ khóa có người lưu yêu cầu sang chức danh khác, yêu cầu vẫn được tìm thấy với chức danh mới và trả lỗi theo trạng thái (bước 6), không thành "không tồn tại".
5. Phạm vi: người `SCOPED` không được phân công yêu cầu này thì 403 `FORBIDDEN`.
6. Yêu cầu không ở trạng thái `APPROVED`: 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
7. Yêu cầu đã có tin: 409 `JOB_POSTING_ALREADY_EXISTS`.
8. Hạn nộp hồ sơ, nếu có gửi, không trước hôm nay theo giờ Việt Nam: 400 `JOB_POSTING_DEADLINE_IN_PAST` (task 304).
9. Địa điểm làm việc, nếu có gửi, phải là giá trị của đúng danh mục (400 `INVALID_JOB_POSTING_WORK_LOCATION`) và còn dùng (400 `JOB_POSTING_WORK_LOCATION_INACTIVE`, task 304); rồi hình thức làm việc như vậy (`INVALID_JOB_POSTING_EMPLOYMENT_TYPE`, `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`). Giá trị được đọc và khóa `FOR SHARE`.
10. Kiểm lại người gọi và phạm vi lần nữa (bước 9 có thể phải chờ HR đang sửa danh mục), rồi lưu. Người gọi trở thành người soạn đầu tiên của tin.

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
  "actions": { "canEdit": true, "canSubmit": true, "canApprove": false, "canRequestRevision": false, "canPublish": false },
  "lastReview": null,
  "publishedById": null,
  "publishedByName": null,
  "publishedAt": null
}
```

| Trường | Ý nghĩa |
|---|---|
|status, revision|Trạng thái (`DRAFT`, `PENDING_APPROVAL`, `REVISION_REQUESTED`, `APPROVED`, `PUBLISHED`; ý nghĩa ở [API duyệt và xuất bản](job-posting-publication.md)) và số phiên bản: 1 khi tạo, tăng 1 sau mỗi lần lưu và mỗi bước duyệt (task 308). Gửi lại `revision` đang xem làm `expectedRevision` khi sửa, gửi duyệt, duyệt hay xuất bản|
|title, jobDescription, candidateRequirements, benefits|Nội dung riêng của tin|
|positionId, positionName, departmentId, departmentName, headcount|Đọc từ yêu cầu nguồn, chỉ đọc|
|workLocationId/Name, employmentTypeId/Name|Giá trị danh mục đã chọn và tên hiện tại; `null` khi chưa chọn|
|applicationDeadline, showSalary|Hạn nộp hồ sơ (`null` khi chưa chọn) và có cho ứng viên thấy lương không|
|salaryMin, salaryMax|Lương đề xuất đã duyệt của yêu cầu (`proposedSalaryMin`/`proposedSalaryMax`, số nguyên đồng VND mỗi người mỗi tháng). **Chỉ đọc**: tin không lưu số tiền nào, chỉ lưu `showSalary`. Người soạn thấy đúng mức lương sẽ công khai nếu bật `showSalary`|
|createdById, createdByName, createdAt, updatedAt|Người tạo (id và họ tên, không có email), thời điểm tạo và lần sửa nội dung gần nhất (UTC, micro giây)|
|actions.canEdit|`true` khi `PUT` sẽ qua được phần kiểm trạng thái: tin ở trạng thái sửa được (`DRAFT` hoặc `REVISION_REQUESTED`) và yêu cầu nguồn vẫn `APPROVED`. Người đọc được tin cũng sửa được tin, nên cờ này không phụ thuộc người xem|
|actions.canSubmit, canApprove, canRequestRevision, canPublish|Task 308: gửi duyệt, duyệt, yêu cầu chỉnh sửa, xuất bản có qua được phần kiểm trạng thái và quyền của người xem không; `canApprove` còn `false` với người đã soạn, sửa hoặc gửi duyệt tin lần gần nhất. Chi tiết ở [API duyệt và xuất bản](job-posting-publication.md), mục "Kết quả trả về và lịch sử"|
|lastReview|Task 308: quyết định gần nhất của HR (`action` là `APPROVED` hoặc `REVISION_REQUESTED`, `comment`, `actorId`, `actorName`, `occurredAt`); `null` khi chưa có quyết định nào|
|publishedById, publishedByName, publishedAt|Task 310: người xuất bản (id và họ tên, không có email) và thời điểm công khai (UTC, micro giây); `null` khi tin chưa xuất bản. Chi tiết ở [API duyệt và xuất bản](job-posting-publication.md), mục "Người xuất bản và thời điểm công khai"|

Tên chức danh, phòng ban, giá trị danh mục và người tạo được đọc lúc trả, nên đổi tên ở danh mục hiện tên mới. Thứ tự kiểm: quyền (401/403), tin tồn tại (404 `JOB_POSTING_NOT_FOUND`), phạm vi (403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.

`GET /job-postings?status=DRAFT&page=0&size=20` trả **200** các tin người gọi được xem theo mục "Phạm vi dữ liệu": `{items, page, size, totalElements, totalPages}`. Mỗi item gồm `id`, `requisitionId`, `title`, `positionName`, `departmentName`, `status`, `revision`, `applicationDeadline`, `createdByName`, `createdAt`, `updatedAt`, `publishedAt` (task 310, `null` khi chưa xuất bản); văn bản và lương chỉ có ở chi tiết. Sắp xếp tin tạo sau lên trước (`createdAt` giảm dần, rồi UUID); lưu tin không đổi vị trí của nó. `status` không bắt buộc, đúng chữ hoa (một trong sáu trạng thái, ví dụ `PENDING_APPROVAL` cho danh sách chờ duyệt, `CLOSED` cho tin đã đóng cùng yêu cầu, task 292); giá trị khác (ví dụ `draft`) trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ.". `page`, `size` như danh sách yêu cầu nguồn, lời nhắn "Trang hoặc số lượng tin tuyển dụng không hợp lệ.". Recruiter không được phân công yêu cầu nào nhận `items` rỗng với `totalElements` và `totalPages` bằng 0.

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
|title|Bắt buộc ("Tiêu đề tin không được để trống."), tối đa 255 ký tự, văn bản thuần trên một dòng như khi tạo|
|jobDescription|Bắt buộc ("Mô tả công việc không được để trống."), tối đa 10.000 ký tự, văn bản thuần như khi tạo|
|candidateRequirements|Bắt buộc ("Yêu cầu ứng viên không được để trống."), như `jobDescription`|
|benefits, workLocationId, employmentTypeId, applicationDeadline|Như khi tạo, không bắt buộc|
|showSalary|Bắt buộc ("Vui lòng chọn có hiển thị mức lương hay không.")|

- `PUT` **thay toàn bộ** nội dung: trường không bắt buộc mà không gửi hoặc gửi `null` bị xóa, nên giao diện phải gửi lại mọi trường đang có trên form. Nhờ vậy bản nháp vẫn lưu được trong lúc viết dở.
- Văn bản chỉ có khoảng trắng (khoảng trắng nào được tính: mục "Kiểm tra dữ liệu trước khi lưu") được coi như không gửi: tiêu đề, mô tả công việc, yêu cầu ứng viên nhận lời nhắn "không được để trống", quyền lợi được lưu `null`. Không có bước kế thừa lại từ yêu cầu khi sửa.
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
7. Tin chưa đóng cùng yêu cầu, nếu đã đóng thì 409 `JOB_POSTING_CLOSED` (task 292).
8. Tin ở trạng thái sửa được (`DRAFT` hoặc `REVISION_REQUESTED`), nếu không thì 409 `JOB_POSTING_NOT_EDITABLE`.
9. `expectedRevision` đúng `revision` hiện tại, nếu không thì 409 `JOB_POSTING_CHANGED`.
10. Yêu cầu nguồn vẫn `APPROVED`, nếu không thì 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
11. Hạn nộp, rồi địa điểm và hình thức làm việc như bước 8, 9 của tạo (400).
12. Kiểm lại người gọi và phạm vi, rồi lưu; người gọi được ghi là người soạn của tin.

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 3 trở đi. Lỗi nào cũng không đổi dòng nào.

## Kiểm tra dữ liệu trước khi lưu (task 304)

Kết quả Jira: "Kiểm tra thông tin cần thiết và hạn ứng tuyển; cập nhật JD trên tin không làm sửa JD nguồn." Mọi lần lưu tin (`POST` tạo và `PUT` sửa) chạy cùng các quy tắc dưới đây trước khi ghi. Lỗi nào cũng không lưu gì, không tăng `revision` và không ghi thêm người soạn. Task 304 không thêm API, migration hay mã quyền.

| Trường | Sai thế nào | HTTP / mã | Lời nhắn (`fieldErrors.<trường>`) |
|---|---|---|---|
|title|Thiếu hoặc chỉ có khoảng trắng (khi sửa; khi tạo thì kế thừa)|400 `VALIDATION_ERROR`|"Tiêu đề tin không được để trống."|
|title|Quá 255 ký tự|400 `VALIDATION_ERROR`|"Tiêu đề tin tối đa 255 ký tự."|
|title|Không phải văn bản thuần trên một dòng (thẻ HTML, xuống dòng, tab, ký tự điều khiển, chỉ có ký tự vô hình)|400 `VALIDATION_ERROR`|"Tiêu đề tin phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML."|
|jobDescription|Thiếu hoặc chỉ có khoảng trắng (khi sửa; khi tạo thì kế thừa)|400 `VALIDATION_ERROR`|"Mô tả công việc không được để trống."|
|jobDescription|Quá 10.000 ký tự|400 `VALIDATION_ERROR`|"Mô tả công việc tối đa 10.000 ký tự."|
|jobDescription|Không phải văn bản thuần (được xuống dòng và dùng tab)|400 `VALIDATION_ERROR`|"Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML."|
|candidateRequirements|Như `jobDescription`|400 `VALIDATION_ERROR`|"Yêu cầu ứng viên không được để trống.", "Yêu cầu ứng viên tối đa 10.000 ký tự.", "Yêu cầu ứng viên phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML."|
|benefits|Quá 10.000 ký tự hoặc không phải văn bản thuần (không bắt buộc)|400 `VALIDATION_ERROR`|"Quyền lợi tối đa 10.000 ký tự.", "Quyền lợi phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML."|
|showSalary|Thiếu (khi sửa)|400 `VALIDATION_ERROR`|"Vui lòng chọn có hiển thị mức lương hay không."|
|expectedRevision|Thiếu hoặc nhỏ hơn 1 (khi sửa)|400 `VALIDATION_ERROR`|"Thiếu số phiên bản tin tuyển dụng đang xem." hoặc "Số phiên bản tin tuyển dụng không hợp lệ."|
|applicationDeadline|Ngày không tồn tại hoặc sai định dạng (`2026-02-30`, `30/11/2026`)|400 `INVALID_JSON`|Không có `fieldErrors`|
|applicationDeadline|Trước hôm nay theo giờ Việt Nam|400 `JOB_POSTING_DEADLINE_IN_PAST`|"Hạn nộp hồ sơ không được trước ngày hôm nay."|
|workLocationId|Không tồn tại hoặc thuộc danh mục khác|400 `INVALID_JOB_POSTING_WORK_LOCATION`|"Địa điểm làm việc không tồn tại."|
|workLocationId|Đã ngừng sử dụng (`active = false`)|400 `JOB_POSTING_WORK_LOCATION_INACTIVE`|"Địa điểm làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác."|
|employmentTypeId|Không tồn tại hoặc thuộc danh mục khác|400 `INVALID_JOB_POSTING_EMPLOYMENT_TYPE`|"Hình thức làm việc không tồn tại."|
|employmentTypeId|Đã ngừng sử dụng|400 `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`|"Hình thức làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác."|

`VALIDATION_ERROR` liệt kê **mọi** trường sai của body trong `fieldErrors`, với `message` chung "Vui lòng kiểm tra dữ liệu đã nhập.". Các lỗi theo nghiệp vụ (hạn nộp, danh mục) chỉ được kiểm khi body đã hợp lệ, mỗi lần chỉ trả lỗi đầu tiên theo thứ tự hạn nộp, địa điểm, hình thức làm việc, và dùng cùng một câu ở `message` và ở `fieldErrors` của trường đó:

```json
{
  "code": "JOB_POSTING_DEADLINE_IN_PAST",
  "message": "Hạn nộp hồ sơ không được trước ngày hôm nay.",
  "fieldErrors": {
    "applicationDeadline": "Hạn nộp hồ sơ không được trước ngày hôm nay."
  }
}
```

Trước khi kiểm, backend chuẩn hóa văn bản: tiêu đề bỏ mọi khoảng trắng ở hai đầu (kể cả khoảng trắng không ngắt `U+00A0`, tab và xuống dòng), bốn văn bản đổi xuống dòng `\r\n` và `\r` thành `\n`; thụt đầu dòng, tab và dòng trống bên trong được giữ nguyên. Văn bản chỉ còn khoảng trắng thông thường (dấu cách, tab, xuống dòng, cùng các khoảng trắng Unicode mà `String.isBlank()` của Java bỏ qua, như `U+3000`) được coi như không gửi: khi tạo thì kế thừa từ yêu cầu (quyền lợi lưu `null`), khi sửa chỉ nhận lời nhắn "không được để trống", không kèm lời nhắn văn bản thuần. Riêng `jobDescription`, `candidateRequirements` và `benefits` chỉ gồm khoảng trắng không ngắt (`U+00A0`, `U+2007`, `U+202F`) hoặc ký tự độ rộng 0 (`U+200B`) **không** được coi là trống: cả khi tạo lẫn khi sửa, chúng nhận lời nhắn văn bản thuần (không có ký tự nhìn thấy được), không được kế thừa và không được lưu `null`. Tiêu đề chỉ có khoảng trắng không ngắt thì vẫn coi như không gửi, vì bước trên đã bỏ chúng; tiêu đề chỉ có `U+200B` nhận lời nhắn văn bản thuần. Độ dài được đếm sau bước này, theo ký tự chứ không theo byte: tiêu đề 255 chữ "Đ" vẫn hợp lệ.

Người không có quyền ghi tin (ví dụ HIRING_MANAGER) nhận 403 trước mọi kiểm tra body. Recruiter qua được matcher (`JOB_POSTINGS_WRITE_SCOPED`) và body được kiểm (`@Valid`) trước khi service xét phạm vi, nên recruiter ngoài nhóm gửi body sai vẫn nhận 400 của body, giống [API yêu cầu tuyển dụng](requisitions.md); body hợp lệ thì nhận 403 phạm vi trước lỗi hạn nộp hay danh mục.

### Hạn nộp hồ sơ

- **Hôm nay** là ngày hiện tại theo **múi giờ nghiệp vụ** `app.business-zone` (mặc định `Asia/Ho_Chi_Minh`, UTC+7, như ngày cần người của yêu cầu ở task 248), không phải ngày UTC hay múi giờ của JVM. Ví dụ lúc `2026-10-11T17:30:00Z` ở Việt Nam đã là 00:30 ngày 12/10, nên `applicationDeadline = "2026-10-11"` bị từ chối dù theo UTC vẫn đang là ngày 11/10.
- **Hôm nay được phép** (hạn nộp tính cả ngày hạn); không có ngày xa nhất (`9999-12-31` vẫn lưu được). Bản nháp được để trống hạn nộp; gửi duyệt (task 308) mới bắt buộc có.
- **So ở mỗi lần lưu** với ngày hiện tại. Bản nháp có hạn nộp đã qua vẫn đọc được như cũ, nhưng `PUT` lại (kể cả gửi nội dung y hệt) bị từ chối cho tới khi chọn ngày từ hôm nay trở đi hoặc để trống. Tin không tự bị sửa khi qua ngày.
- Hạn nộp **không liên quan** tới ngày cần người (`neededBy`) của yêu cầu: hai ngày không được so với nhau, và hạn nộp không điền sẵn từ `neededBy`.
- "Hôm nay" được đọc sau khi đã khóa dòng yêu cầu (tạo) hoặc dòng tin (sửa), nên request phải chờ các khóa này qua 00:00 thì so với ngày mới. Ngày được so **trước** khi khóa `FOR SHARE` giá trị danh mục, vì lỗi hạn nộp được báo trước lỗi danh mục có chủ ý. Nếu request so ngày xong rồi phải chờ khóa danh mục qua 00:00, hoặc qua 00:00 ngay trước lúc commit, ngày vừa lưu có thể đã là hôm qua tính theo lúc commit; lần lưu sau và lần gửi duyệt (task 308) sẽ kiểm lại.
- Giao diện nên chặn sẵn ngày trước hôm nay (theo giờ Việt Nam) trên ô chọn ngày, nhưng backend là nơi kiểm cuối cùng. Trong code, `JobPostingService` so với `BusinessCalendar.today()`.

### Chỉ nhận văn bản thuần

`title`, `jobDescription`, `candidateRequirements` và `benefits` sẽ hiện cho ứng viên trên cổng tuyển dụng công khai (task 309), nên chỉ nhận **văn bản thuần**, không nhận HTML, để tránh stored XSS. Backend dùng lại quy tắc `@PlainText` của [trang giới thiệu công ty](company-profile.md) (mục "Chỉ nhận văn bản thuần, không nhận HTML") và trả 400 `VALIDATION_ERROR` khi văn bản:

- có `<` đứng **ngay trước** chữ cái Latin, `/`, `!` hoặc `?`, tức là thứ trình duyệt hiểu là thẻ, thẻ đóng, chú thích hoặc khai báo HTML: `<b>`, `</p>`, `<!-- -->`, `<?xml`;
- có ký tự điều khiển, kể cả ký tự NUL (PostgreSQL không lưu được). `jobDescription`, `candidateRequirements`, `benefits` được dùng xuống dòng `\n` và tab; `title` phải nằm trên một dòng, không có tab;
- có ký tự ngắt dòng/ngắt đoạn Unicode `U+2028`, `U+2029`, hoặc ký tự điều khiển hướng chữ `U+202A`–`U+202E`, `U+2066`–`U+2069` (làm chữ hiện theo thứ tự khác thứ tự đã gõ);
- không có ký tự nào nhìn thấy được, ví dụ chỉ gồm khoảng trắng độ rộng 0 `U+200B`;
- có nửa emoji đứng lẻ (UTF-16 surrogate lẻ, chỉ gửi được bằng escape JSON).

Vẫn hợp lệ vì trình duyệt không coi là HTML: `lương < 20 triệu`, `<3`, `C++ <= 5 năm`; các văn bản này được lưu đúng như gửi. Frontend vẫn phải hiển thị mọi văn bản của tin dạng chữ (`textContent`, `{{ }}`), không dùng `v-html`, `innerHTML` hay `dangerouslySetInnerHTML`.

### Sửa JD của tin không đổi JD của yêu cầu

Tin giữ bản sao riêng của mô tả công việc và yêu cầu ứng viên (V21, task 302). Code của tin đọc và khóa yêu cầu nguồn bằng một truy vấn JDBC chỉ lấy vài cột (`JobPostingSourceRepository.lockSource`) và không bao giờ nạp entity `RecruitmentRequisition`, nên không có đối tượng nào để Hibernate tự ghi ngược vào yêu cầu. Test `editingTheJobDescriptionOfThePostingNeverChangesTheRequisition` (`JobPostingValidationIntegrationTest`) kiểm điều này: toàn bộ dòng yêu cầu (kể cả `updated_at`), lịch sử duyệt và nhóm recruiter của yêu cầu giống hệt trước khi tạo tin, sau khi tạo và sau ba lần sửa mô tả công việc; `GET /requisitions/{id}` vẫn trả mô tả gốc, và sao chép yêu cầu (`POST /requisitions/{id}/copy`) vẫn chép mô tả gốc.

### Nội dung kế thừa có thẻ HTML

API yêu cầu tuyển dụng chỉ chặn ký tự NUL, nên mô tả công việc hay yêu cầu ứng viên của yêu cầu có thể chứa thẻ HTML hoặc xuống dòng kiểu Windows. Khi tạo tin, phần kế thừa **không** bị kiểm văn bản thuần, vì đó là văn bản đã được duyệt cùng yêu cầu và người soạn không sửa được yêu cầu đã duyệt: backend chép nó (chỉ đổi xuống dòng thành `\n`) và tạo tin vẫn trả 201. Bản nháp giữ nguyên văn bản đó. Lần `PUT` gửi lại văn bản có thẻ thì nhận 400 với `fieldErrors.jobDescription` (hoặc `candidateRequirements`): người soạn phải sửa phần có thẻ trước khi lưu lại được, và trước khi gửi duyệt (task 308 kiểm lại văn bản đã lưu). Văn bản gửi trong body khi tạo vẫn bị kiểm như khi sửa.

## Khóa và đồng thời

- Mọi thao tác ghi khóa theo cùng một thứ tự: tài khoản người gọi, phiên, dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`), dòng tin (`FOR NO KEY UPDATE`, `@Lock(PESSIMISTIC_WRITE)` của Hibernate), rồi giá trị danh mục được chọn (`FOR SHARE`, địa điểm trước, hình thức sau). Tin không bao giờ bị khóa trước yêu cầu của nó: khi sửa, backend đọc `requisitionId` của tin (không đổi được) mà không khóa, khóa yêu cầu rồi mới khóa tin. Thứ tự này giống các thao tác ghi yêu cầu và phân công recruiter, nên không gây khóa chết với chúng.
- Mỗi lần chờ khóa, token có thể hết hạn hay quyền có thể bị gỡ, nên người gọi được kiểm lại sau mỗi khóa (quyền đọc lại từ database): token hết hạn, phiên bị thu hồi hay tài khoản bị khóa trả 401 `SESSION_INVALID`; bị gỡ quyền trả 403; bị bỏ khỏi nhóm recruiter trả 403. Không lưu gì.
- Hai lần tạo tin cho cùng một yêu cầu chạy lần lượt nhờ khóa dòng yêu cầu; lần sau trả 409 `JOB_POSTING_ALREADY_EXISTS`. Ràng buộc unique `job_postings_requisition_id_key` là lớp chặn cuối.
- Hai lần lưu cùng một tin chạy lần lượt nhờ khóa dòng tin; lần sau mang `expectedRevision` cũ nên trả 409 `JOB_POSTING_CHANGED`, không ghi đè thay đổi của lần trước.
- Giá trị danh mục được khóa `FOR SHARE` tới khi lưu xong: HR không xóa hay ngừng dùng được giá trị đó giữa lúc kiểm và lúc lưu. Một lần xóa commit trong lúc thao tác đang chờ được thấy và trả 400 `INVALID_JOB_POSTING_*`, không phải 500 từ khóa ngoại; một lần ngừng sử dụng (`active = false`) commit trong lúc chờ trả 400 `JOB_POSTING_*_INACTIVE` (task 304).
- Yêu cầu tuyển dụng chỉ được đọc qua JDBC (không qua entity `RecruitmentRequisition`), nên code của tin không thể vô tình ghi vào yêu cầu. Bị khóa trong lúc tạo hay sửa tin, yêu cầu vẫn không đổi `updatedAt` hay bất kỳ cột nào.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|Body: thiếu `requisitionId` (tạo), thiếu `expectedRevision`, `title`, `jobDescription`, `candidateRequirements`, `showSalary` (sửa), văn bản quá dài hoặc không phải văn bản thuần (task 304); lỗi theo trường nằm trong `fieldErrors`. Tham số: `{id}` không phải UUID, `status` sai, `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|`INVALID_JSON`|JSON sai hoặc thiếu body; UUID hay ngày sai định dạng (ví dụ `2026-02-30`); có trường ngoài hợp đồng, kể cả `requisitionId` khi sửa|
|400|`INVALID_JOB_POSTING_REQUISITION`|Không có yêu cầu tuyển dụng với `requisitionId`; `fieldErrors.requisitionId`|
|400|`INVALID_JOB_POSTING_WORK_LOCATION`|`workLocationId` không phải giá trị của danh mục `WORK_LOCATION` (không tồn tại hoặc thuộc danh mục khác); `fieldErrors.workLocationId`|
|400|`INVALID_JOB_POSTING_EMPLOYMENT_TYPE`|`employmentTypeId` không phải giá trị của danh mục `EMPLOYMENT_TYPE`; `fieldErrors.employmentTypeId`|
|400|`JOB_POSTING_DEADLINE_IN_PAST`|`applicationDeadline` trước hôm nay theo giờ Việt Nam ("Hạn nộp hồ sơ không được trước ngày hôm nay."); `fieldErrors.applicationDeadline` (task 304)|
|400|`JOB_POSTING_WORK_LOCATION_INACTIVE`|Địa điểm làm việc đã ngừng sử dụng ("Địa điểm làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác."); `fieldErrors.workLocationId` (task 304)|
|400|`JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`|Hình thức làm việc đã ngừng sử dụng ("Hình thức làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác."); `fieldErrors.employmentTypeId` (task 304)|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn, phiên bị thu hồi, tài khoản bị khóa, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền ghi tin; có quyền ghi nhưng thiếu quyền đọc tin; `JOB_POSTINGS_READ_ALL` đơn lẻ; recruiter với yêu cầu hoặc tin không được phân công; route không khai báo (ví dụ `POST /job-postings/draft`, `DELETE` hay `PATCH /job-postings/{id}`)|
|404|`JOB_POSTING_NOT_FOUND`|Không có tin với `{id}` ("Không tìm thấy tin tuyển dụng.")|
|409|`JOB_POSTING_REQUISITION_NOT_APPROVED`|Yêu cầu không ở trạng thái `APPROVED`, khi tạo và khi sửa ("Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.")|
|409|`JOB_POSTING_ALREADY_EXISTS`|Yêu cầu đã có tin ("Yêu cầu tuyển dụng này đã có tin tuyển dụng.")|
|409|`JOB_POSTING_NOT_EDITABLE`|Sửa tin đã gửi duyệt, đã duyệt hoặc đã xuất bản ("Chỉ sửa được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa."), từ task 308|
|409|`JOB_POSTING_CHANGED`|`expectedRevision` cũ ("Tin tuyển dụng đã thay đổi (có thể người khác vừa sửa hoặc xử lý). Vui lòng tải lại trước khi thao tác.")|
|409|`JOB_POSTING_CLOSED`|Sửa tin đã đóng cùng yêu cầu ("Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không thể sửa, gửi duyệt, duyệt hay xuất bản nữa."), từ task 292|
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
|Chọn yêu cầu bằng `GET /requisitions?status=APPROVED&size=100` rồi `GET /requisitions/{id}`; trước task 285 recruiter nhận danh sách rỗng hoặc 403 rồi bị đưa sang `/unauthorized`; từ task 285 recruiter thấy yêu cầu đã duyệt mình được phân công, kể cả yêu cầu đã có tin|`GET /job-postings/source-requisitions?size=100`, rồi `POST /job-postings {requisitionId, …}`. Server tự chép tiêu đề, mô tả công việc, yêu cầu ứng viên; không tự ánh xạ trường của yêu cầu ở client (task 301)|
|`saveDraft` gửi `POST /job-postings/draft` với id do client tạo (`jp-draft-…`)|Lần lưu đầu `POST /job-postings` (201, server cấp UUID); các lần sau `PUT /job-postings/{id}` kèm `expectedRevision`. `/job-postings/draft` trả 403 (không khai báo). Không bao giờ gửi `id` (task 300)|
|"Đăng tin" gửi `POST /job-postings` với `status: 'PUBLISHED'`|Chỉ lưu bản nháp; gửi duyệt là `POST /job-postings/{id}/submit` của task 308, người soạn không tự xuất bản (task 300)|
|Body là cả `JobPostingData`|`POST`: `requisitionId` và tùy chọn `title`, `jobDescription`, `candidateRequirements`, `benefits`, `workLocationId`, `employmentTypeId`, `applicationDeadline`, `showSalary`. `PUT`: như vậy nhưng không có `requisitionId`, thêm `expectedRevision`. Trường khác trả `INVALID_JSON` (task 300)|
|`requirements`, `deadline`, `workLocation` (chuỗi), `employmentType` (mã), `isSalaryNegotiable` (ngược nghĩa)|`candidateRequirements`, `applicationDeadline` (không trước hôm nay theo giờ Việt Nam; backend là nơi kiểm cuối cùng, task 304), `workLocationId`/`employmentTypeId` lấy từ `GET /recruitment-catalogs/{WORK_LOCATION\|EMPLOYMENT_TYPE}/items?active=true` (tên trả về ở `…Name`), `showSalary` (`true` là hiện lương) (task 300)|
|Hạn nộp điền sẵn từ `req.neededBy`|Để trống; hạn nộp và ngày cần người là hai việc khác nhau (task 301)|
|Lương nhập được theo triệu, USD, `salaryType`|`salaryMin`/`salaryMax` là lương đề xuất đã duyệt, đồng VND, **chỉ đọc**; chỉ `showSalary` sửa được; không có tiền tệ hay kiểu lương (task 300)|
|`positionTitle`, phòng ban, số lượng, cấp bậc sửa được|`positionName`, `departmentName`, `headcount` chỉ đọc; không có cấp bậc (task 300)|
|Khối `catch` giả thành công, tên người cứng ("Trần Thị Thu Hà")|Bỏ; hiện `message` của lỗi, gắn `fieldErrors` (`title`, `jobDescription`, `candidateRequirements`, `benefits`, `workLocationId`, `employmentTypeId`, `applicationDeadline`, `showSalary`, `requisitionId`, `expectedRevision`) vào ô tương ứng; dùng `createdByName`|
|Route và menu tin tuyển dụng dựa vào `REQUISITIONS_READ_*` hoặc `can('view','job_posting')` (`JOB_POSTINGS_READ_*`)|Mọi màn hình nội bộ của tin (danh sách, chi tiết, form) dựa vào `JOB_POSTINGS_WRITE_ALL` hoặc `JOB_POSTINGS_WRITE_SCOPED` (`can('edit','job_posting')`). HIRING_MANAGER và APPROVER (chỉ `JOB_POSTINGS_READ_ALL`) không được thấy các link này, nếu không sẽ bị 403 và bị đưa sang `/unauthorized` (task 300)|

Hiện nút sửa theo `actions.canEdit`, nút gửi duyệt theo `actions.canSubmit` (các nút duyệt, xuất bản: [API duyệt và xuất bản](job-posting-publication.md)). Gặp 409 `JOB_POSTING_CHANGED` thì báo và tải lại tin. Hiển thị mọi văn bản của tin dạng chữ, không dùng `dangerouslySetInnerHTML`, `v-html` hay `innerHTML`. Ô chọn địa điểm và hình thức chỉ liệt kê giá trị còn dùng (`?active=true`); tin đang giữ giá trị đã ngừng dùng thì báo người soạn chọn giá trị khác (lỗi `JOB_POSTING_*_INACTIVE`). Mô tả công việc kế thừa có thẻ HTML phải được sửa trước lần lưu kế tiếp (lỗi ở `fieldErrors.jobDescription`). Nhãn trạng thái: `DRAFT` Nháp, `PENDING_APPROVAL` Chờ duyệt, `REVISION_REQUESTED` Cần chỉnh sửa, `APPROVED` Đã duyệt, `PUBLISHED` Đã xuất bản, `CLOSED` Đã đóng (task 292: yêu cầu đã tuyển đủ hoặc bị hủy; không còn nút nào).

QA cần chuẩn bị: V10 không tạo sẵn giá trị danh mục, nên HR phải tạo giá trị `WORK_LOCATION` và `EMPLOYMENT_TYPE` trước; HR phải phân công recruiter (`POST /requisitions/{id}/assign`) trước khi recruiter thấy hay tạo được tin.

## Database và phạm vi

Dùng hai bảng của V21 (task 302): `job_postings` và `job_posting_authors`, xem [database](../database/README.md). Task 303 không thêm migration và không thêm mã quyền: dùng `JOB_POSTINGS_*` có sẵn từ V3 và `requisition_recruiters` của V16 làm phạm vi `SCOPED`.

- Địa điểm và hình thức làm việc là khóa ngoại thường (`ON DELETE RESTRICT`, không `DEFERRABLE`) tới `recruitment_catalog_items`; database không biết loại danh mục, nên API kiểm loại bằng `RecruitmentCatalogItemRepository.findActiveForShare` (đọc kèm `FOR SHARE`). Task 303 từ chối giá trị không tồn tại hoặc thuộc danh mục khác; từ task 304, giá trị đã ngừng dùng (`active = false`) cũng bị từ chối khi lưu, còn tin đã giữ giá trị đó vẫn đọc được.
- Task 304 không thêm migration: hạn nộp so với hôm nay và giá trị danh mục còn dùng thay đổi theo thời gian, nên không viết được bằng CHECK; API kiểm ở mỗi lần lưu. Quy tắc văn bản thuần nằm ở `JobPostingCreateRequest`, `JobPostingRequest` (`@PlainText` của gói `companyprofile`) và `JobPostingText` (chuẩn hóa xuống dòng, bỏ khoảng trắng ở hai đầu tiêu đề).
- Tin không lưu số tiền lương. Chức danh, phòng ban, số lượng và lương đề xuất đọc từ yêu cầu bằng một câu SQL chung cho chi tiết, danh sách và response của mọi thao tác ghi (`JobPostingReadRepository.SELECT_ROW`).
- Code của tin nằm ở gói `vn.ttcs.recruitment.jobposting`. Trạng thái của yêu cầu được tạo tin là `JobPostingService.SOURCE_STATUSES` (`APPROVED`); trạng thái của tin còn sửa được là `JobPostingService.EDITABLE_STATUSES` (`DRAFT`, `REVISION_REQUESTED`). Khi thêm trạng thái mới cho yêu cầu hay cho tin, `JobPostingSourceStatusTest` và `JobPostingStatusTest` không biên dịch cho tới khi trạng thái đó được xếp nhóm.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md). Gồm:

- `JOB_POSTINGS_*_SCOPED` của Recruiter là tin của yêu cầu mà người đó là recruiter chính hoặc hỗ trợ, kiểm lại ở mỗi lần gọi; bàn giao xong thì người cũ mất quyền ngay;
- tạo tin không cần quyền đọc yêu cầu tuyển dụng; phạm vi tin theo phân công recruiter (từ task 285 recruiter được phân công cũng đọc được yêu cầu);
- `JOB_POSTINGS_READ_ALL` đơn lẻ (HIRING_MANAGER, APPROVER) không xem bản nháp, chỉ xem cổng công khai (task 309), giống trang giới thiệu công ty;
- mỗi yêu cầu chỉ có một tin;
- không có trạng thái Từ chối cho tin; người duyệt dùng "yêu cầu chỉnh sửa" (task 308);
- chỉ tin nháp và tin cần chỉnh sửa sửa được; tin đã gửi duyệt, đã duyệt hoặc đã xuất bản không sửa được (task 308); tin đã xuất bản chưa sửa hay gỡ được; tin chỉ đóng (`CLOSED`, V25, task 292, trạng thái cuối) khi yêu cầu được đóng vì đã tuyển đủ hoặc bị hủy;
- bản nháp lưu được khi chưa có địa điểm, hình thức làm việc, hạn nộp; gửi duyệt (task 308) mới bắt buộc;
- hạn nộp không trước hôm nay theo giờ Việt Nam, tính cả ngày hạn; không giới hạn ngày xa nhất; không so với ngày cần người của yêu cầu;
- văn bản của tin chỉ nhận văn bản thuần (không HTML); mô tả công việc kế thừa có thẻ HTML được giữ trong nháp và phải sửa trước khi lưu lại hoặc gửi duyệt;
- địa điểm và hình thức làm việc lấy từ danh mục dùng chung; giá trị đã ngừng dùng chặn lưu tin (và gửi duyệt, task 308) nhưng tin đang giữ nó vẫn đọc được;
- lương công khai là lương đề xuất đã duyệt của yêu cầu (tối thiểu, tối đa), chỉ hiện khi chọn, mặc định không hiện; tin không nhập được mức lương khác;
- trường của tin: tiêu đề (mặc định tên chức danh), quyền lợi (không bắt buộc); không có kỹ năng, cấp bậc, kênh đăng.
