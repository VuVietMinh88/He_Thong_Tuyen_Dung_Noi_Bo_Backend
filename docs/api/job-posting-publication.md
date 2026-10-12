# API duyệt và xuất bản tin tuyển dụng

Phạm vi TKNHTTDNB1-308 (API duyệt và xuất bản tin tuyển dụng), TKNHTTDNB1-309 (dùng chung nội dung cho xem trước và tin công khai), TKNHTTDNB1-310 (ghi người xuất bản và thời điểm công khai), story TKNHTTDNB1-39 (S3-10). URL dùng tiền tố `/api/v1`, header `Authorization: Bearer …`; mọi response JSON thành công và các lỗi `JOB_POSTING_*` dùng `Cache-Control: no-store`. Soạn và lưu bản nháp nằm ở [API tin tuyển dụng](job-postings.md) (story S3-09).

Kết quả Jira của task 308: "Kiểm tra quyền và chặn xuất bản tin chưa được duyệt." Task 309: bản xem trước và tin công khai dùng chung một nội dung (mục "Xem trước", "Tin công khai"). Task 310: "Lưu dấu vết xuất bản và chuyển tin sang trạng thái có thể công khai" (mục "Người xuất bản và thời điểm công khai", "Lịch sử duyệt và xuất bản").

Tiêu chí của story S3-10:

- "Tin phải qua duyệt trước khi hiển thị công khai": đã có (308). Chỉ tin `APPROVED` mới xuất bản được (mục "Xuất bản"); tin ở mọi trạng thái khác trả 409. Cổng tuyển dụng công khai (task 309) chỉ hiện tin `PUBLISHED` còn hạn nộp, của yêu cầu còn đã duyệt; tin khác trả 404 như tin không tồn tại (mục "Tin công khai").
- "Xem trước đúng như giao diện công khai trước khi xuất bản": đã có (308, 309). Từ lúc gửi duyệt, nội dung tin bị khóa (không `PUT` được) và mọi bước duyệt, xuất bản đều mang `expectedRevision` (308). Bản xem trước và tin công khai được dựng bằng cùng một câu truy vấn và cùng một hàm, và người duyệt duyệt, xuất bản bằng `revision` của bản xem trước (309, mục "Xem trước"). Giới hạn: tên chức danh, phòng ban, địa điểm và hình thức làm việc được đọc trực tiếp ở cả hai, nên đổi tên các mục này sau khi duyệt hiện ngay trên cổng mà không cần duyệt lại (câu hỏi 16).
- "Ghi lại người xuất bản và thời điểm": đã có (308, 310). Mỗi lần xuất bản ghi sự kiện `PUBLISHED` với người làm và thời điểm vào lịch sử `job_posting_events` (308). Tin giữ người xuất bản và thời điểm công khai trong chính nó (`publishedById`, `publishedByName`, `publishedAt` ở mọi response nội bộ), database bắt tin đã xuất bản phải có cả hai, lịch sử đọc được qua `GET /job-postings/{id}/history` và không sửa, không xóa được (310, mục "Người xuất bản và thời điểm công khai", "Lịch sử duyệt và xuất bản", "Lịch sử không sửa, không xóa").

## Trạng thái của tin tuyển dụng

| Mã | Tên | Ý nghĩa |
|---|---|---|
|`DRAFT`|Nháp|Người soạn đang viết, chưa gửi duyệt|
|`PENDING_APPROVAL`|Chờ duyệt|Đã gửi duyệt, chờ HR duyệt hoặc yêu cầu chỉnh sửa; nội dung bị khóa|
|`REVISION_REQUESTED`|Cần chỉnh sửa|HR yêu cầu chỉnh sửa (kèm nhận xét); người soạn sửa rồi gửi duyệt lại|
|`APPROVED`|Đã duyệt, chưa xuất bản|HR đã duyệt; chưa công khai cho tới khi xuất bản; HR vẫn yêu cầu chỉnh sửa được|
|`PUBLISHED`|Đã xuất bản|Ứng viên thấy tin trên cổng công khai (task 309) khi còn hạn nộp và yêu cầu nguồn còn đã duyệt|

```
DRAFT ──gửi duyệt──> PENDING_APPROVAL ──APPROVE──> APPROVED ──xuất bản──> PUBLISHED
                        │      ▲                       │
       REQUEST_REVISION │      │ gửi duyệt lại          │ REQUEST_REVISION
                        ▼      │                       │
                     REVISION_REQUESTED <──────────────┘
```

| Mã | Sửa (`PUT`) | Gửi duyệt | Duyệt (`APPROVE`) | Yêu cầu chỉnh sửa | Xuất bản | Công khai |
|---|---|---|---|---|---|---|
|`DRAFT`|Có|Có|–|–|–|–|
|`PENDING_APPROVAL`|–|–|Có|Có|–|–|
|`REVISION_REQUESTED`|Có|Có|–|–|–|–|
|`APPROVED`|–|–|–|Có (rút lại duyệt)|Có|–|
|`PUBLISHED`|–|–|–|–|– (409 `JOB_POSTING_ALREADY_PUBLISHED`)|Có (khi còn hạn nộp và yêu cầu còn đã duyệt, task 309)|

- V21 chỉ cho `DRAFT`; V22 (task 308) mở CHECK `valid_job_posting_status` cho năm trạng thái trên cùng lúc thêm giá trị vào enum `JobPostingStatus`. Không có trạng thái Từ chối: người duyệt yêu cầu chỉnh sửa. Gỡ hay đóng tin khi đóng yêu cầu là task 292 (S3-07), sẽ thêm `CLOSED`.
- Mỗi cột "Có" là một nhóm trong code: `JobPostingService.EDITABLE_STATUSES` (sửa), `JobPostingService.SUBMITTABLE_STATUSES` (gửi duyệt), `JobPostingDecision.from()` (duyệt, yêu cầu chỉnh sửa), `JobPostingApprovalService.PUBLISHABLE_STATUSES` (xuất bản), `JobPostingService.PUBLIC_STATUSES` (công khai, task 309). `JobPostingStatusTest` và `JobPostingDecisionTest` không biên dịch được cho tới khi một trạng thái mới được xếp vào từng nhóm.
- Mỗi bước (gửi duyệt, duyệt, yêu cầu chỉnh sửa, xuất bản) tăng `revision` thêm 1, như một lần lưu, nên client đang xem trạng thái cũ bị từ chối (409 `JOB_POSTING_CHANGED`). `updatedAt` vẫn là lần sửa nội dung gần nhất, các bước này không đổi nó.
- Tin đã duyệt không sửa được: HR yêu cầu chỉnh sửa, người soạn sửa và gửi lại, rồi cần một lần duyệt mới. Vì vậy thứ được xuất bản luôn là nội dung được duyệt lần cuối.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /job-postings/{id}/submit`|`JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc `JOB_POSTINGS_READ_*` (như sửa tin)|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|
|`POST /job-postings/{id}/approvals`|`JOB_POSTINGS_WRITE_ALL` (ở matcher và kiểm lại trong service), **và** quyền đọc tin|ADMIN, HR_MANAGER, trừ người đã soạn hoặc sửa tin và người gửi duyệt lần gần nhất khi **duyệt** (mục "Người soạn không tự duyệt")|
|`POST /job-postings/{id}/publish`|Như dòng trên|ADMIN, HR_MANAGER, kể cả người vừa duyệt tin|
|`GET /job-postings/{id}/preview`|Như đọc tin (`GET /job-postings/{id}`): `JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc tin (task 309)|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|
|`GET /public/job-postings`, `GET /public/job-postings/{id}`|Công khai, không cần token (task 309)|Mọi người, kể cả ứng viên không có tài khoản|
|`GET /job-postings/{id}/history`|Như đọc tin (`GET /job-postings/{id}`): `JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc tin (task 310)|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|

- Gửi duyệt là thao tác ghi của người soạn, cùng quyền và cùng phạm vi như sửa tin ([API tin tuyển dụng](job-postings.md), mục "Phạm vi dữ liệu"): recruiter chỉ gửi được tin của yêu cầu mình được phân công.
- Duyệt và xuất bản là quyết định của HR. `JOB_POSTINGS_WRITE_SCOPED` của recruiter không đủ: recruiter nhận 403 ở matcher. HIRING_MANAGER, APPROVER (chỉ có `JOB_POSTINGS_READ_ALL`), INTERVIEWER và tài khoản không có vai trò cũng nhận 403.
- `JobPostingApprovalService` kiểm lại `JOB_POSTINGS_WRITE_ALL` sau mỗi lần chờ khóa (`JobPostingAccess.requireReviewer`), cùng hạn token, phiên và quyền đọc. Quyền bị gỡ, hoặc bị hạ từ `WRITE_ALL` xuống `WRITE_SCOPED`, trong lúc request đang chờ đều trả 403. Phạm vi của người duyệt là phạm vi **đọc** (`ALL` với mọi người có `JOB_POSTINGS_WRITE_ALL` trong seed).

## Gửi duyệt

`POST /job-postings/{id}/submit` gửi tin **đã lưu** đi duyệt. Mọi thứ được gửi lấy từ tin đã lưu; muốn đổi nội dung thì `PUT` trước rồi mới gửi.

```json
{ "expectedRevision": 2, "comment": "Nhờ chị duyệt giúp." }
```

| Trường | Quy tắc |
|---|---|
|expectedRevision|Bắt buộc ("Thiếu số phiên bản tin tuyển dụng đang xem."), số nguyên từ 1 ("Số phiên bản tin tuyển dụng không hợp lệ."). Là `revision` của tin mà người soạn đang xem|
|comment|Không bắt buộc. Ghi chú cho người duyệt, tối đa 2.000 ký tự ("Nhận xét tối đa 2.000 ký tự."), không chứa ký tự NUL ("Nhận xét chứa ký tự không hợp lệ."); để trống hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả khoảng trắng không ngắt U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B) thì lưu `null`; ghi chú có ký tự nhìn thấy được lưu đúng như gửi, giữ nguyên xuống dòng|

Trường khác (ví dụ `status`, `note`, `publishedAt`) trả 400 `INVALID_JSON`. Response **200** là tin (cùng dạng `GET /job-postings/{id}`) với `status: "PENDING_APPROVAL"` và `revision` tăng 1. Lịch sử có thêm một sự kiện `SUBMITTED` (người gửi, thời điểm, `comment`).

Tin phải đủ thông tin và nội dung đã lưu phải còn hợp lệ; nếu không, trả 400 `JOB_POSTING_INCOMPLETE` "Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt." với `fieldErrors` gồm **mọi** trường có vấn đề:

| Trường | Khi nào | Lời nhắn |
|---|---|---|
|`workLocationId`|Chưa chọn|"Vui lòng chọn địa điểm làm việc trước khi gửi duyệt."|
|`employmentTypeId`|Chưa chọn|"Vui lòng chọn hình thức làm việc trước khi gửi duyệt."|
|`applicationDeadline`|Chưa chọn|"Vui lòng chọn hạn nộp hồ sơ trước khi gửi duyệt."|
|`title`, `jobDescription`, `candidateRequirements`, `benefits`|Văn bản đã lưu không qua quy tắc lưu tin (task 304), thường là mô tả công việc chép từ yêu cầu có thẻ HTML mà người soạn chưa sửa|Lời nhắn của lần lưu, ví dụ "Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML."|

```json
{
  "code": "JOB_POSTING_INCOMPLETE",
  "message": "Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt.",
  "fieldErrors": {
    "workLocationId": "Vui lòng chọn địa điểm làm việc trước khi gửi duyệt.",
    "applicationDeadline": "Vui lòng chọn hạn nộp hồ sơ trước khi gửi duyệt."
  }
}
```

Bản nháp vẫn lưu được khi thiếu ba chi tiết trên (task 303); chỉ gửi duyệt mới bắt buộc. CHECK `job_posting_details_required` của V22 là lớp chặn cuối trong database. Sau đó, như một lần lưu, hạn nộp không được trước hôm nay theo giờ Việt Nam (400 `JOB_POSTING_DEADLINE_IN_PAST`, ví dụ bản nháp lưu từ lâu mà hạn nộp đã qua) và địa điểm, hình thức làm việc phải còn dùng (400 `JOB_POSTING_WORK_LOCATION_INACTIVE`, `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`). Gửi duyệt không biến người gửi thành người soạn, nhưng người gửi lần gần nhất không được duyệt tin đó.

### Thứ tự kiểm khi gửi duyệt

1. Quyền ở `SecurityConfiguration` (401/403); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON` (thiếu body, trường lạ), rồi `VALIDATION_ERROR` với mọi trường sai trong `fieldErrors`.
3. Khóa tài khoản người gọi rồi phiên; kiểm hạn token, phiên, quyền ghi và quyền đọc tin (401 `SESSION_INVALID` hoặc 403), trước mọi tra cứu.
4. Tin tồn tại: 404 `JOB_POSTING_NOT_FOUND`.
5. Khóa dòng yêu cầu tuyển dụng của tin, kiểm lại người gọi; khóa dòng tin, kiểm lại người gọi.
6. Phạm vi: 403 `FORBIDDEN`.
7. Tin ở `DRAFT` hoặc `REVISION_REQUESTED`, nếu không thì 409 `JOB_POSTING_NOT_SUBMITTABLE`.
8. `expectedRevision` đúng `revision` hiện tại, nếu không thì 409 `JOB_POSTING_CHANGED`.
9. Yêu cầu nguồn vẫn `APPROVED`, nếu không thì 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
10. Đủ thông tin và văn bản đã lưu còn hợp lệ: 400 `JOB_POSTING_INCOMPLETE`.
11. Hạn nộp (400 `JOB_POSTING_DEADLINE_IN_PAST`), rồi địa điểm và hình thức làm việc còn dùng (đọc `FOR SHARE`, 400 `JOB_POSTING_*_INACTIVE`).
12. Kiểm lại người gọi và phạm vi, rồi chuyển sang `PENDING_APPROVAL` và ghi sự kiện `SUBMITTED`.

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 3 trở đi. Lỗi nào cũng không đổi tin và không ghi sự kiện.

## Quyết định duyệt

`POST /job-postings/{id}/approvals` là quyết định của HR về một tin đã gửi duyệt.

```json
{ "action": "REQUEST_REVISION", "comment": "Bổ sung quyền lợi.", "expectedRevision": 3 }
```

| Trường | Quy tắc |
|---|---|
|action|Bắt buộc ("Vui lòng chọn thao tác duyệt."), đúng chữ hoa `APPROVE` hoặc `REQUEST_REVISION` ("Thao tác duyệt chỉ được là APPROVE (duyệt) hoặc REQUEST_REVISION (yêu cầu chỉnh sửa)."). Mã khác, kể cả `REJECT`, là lỗi form|
|comment|Nhận xét của người duyệt, tối đa 2.000 ký tự, không chứa NUL; để trống hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B) thì coi như không gửi; nhận xét có ký tự nhìn thấy được giữ nguyên xuống dòng. **Bắt buộc** khi `REQUEST_REVISION`: thiếu thì 400 `JOB_POSTING_COMMENT_REQUIRED` với `fieldErrors.comment` "Vui lòng nhập nhận xét khi yêu cầu chỉnh sửa tin tuyển dụng."; không bắt buộc khi `APPROVE`|
|expectedRevision|Như khi gửi duyệt. Từ task 309 là `revision` của bản xem trước mà người duyệt đã xem|

| `action` | Nhận khi tin ở | Tin chuyển sang | Sự kiện |
|---|---|---|---|
|`APPROVE`|`PENDING_APPROVAL`|`APPROVED`|`APPROVED`|
|`REQUEST_REVISION`|`PENDING_APPROVAL` hoặc `APPROVED` (chưa xuất bản)|`REVISION_REQUESTED`|`REVISION_REQUESTED`|

Trường khác (ví dụ `status`, `note`, `reason`, `feedback`, `approvedAt`) trả 400 `INVALID_JSON`. Response **200** là tin sau quyết định, có `lastReview` là quyết định vừa ghi. Một quyết định ở trạng thái không nhận nó trả 409 `JOB_POSTING_NOT_UNDER_REVIEW` "Tin tuyển dụng không còn ở bước duyệt này. Vui lòng tải lại để xem trạng thái mới nhất.", ví dụ duyệt tin đã có người khác duyệt.

Duyệt khi hạn nộp đã qua (theo giờ Việt Nam; chính ngày hạn vẫn được) trả 409 `JOB_POSTING_DEADLINE_PASSED`: HR không sửa được hạn nộp của tin đang khóa, nên yêu cầu chỉnh sửa để người soạn đổi hạn. Yêu cầu chỉnh sửa không bị chặn vì hạn nộp. Giá trị danh mục bị ngừng dùng sau khi gửi duyệt không chặn duyệt hay xuất bản.

### Thứ tự kiểm khi quyết định

1. Quyền ở `SecurityConfiguration`: `JOB_POSTINGS_WRITE_ALL` (401/403); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR`.
3. `REQUEST_REVISION` không có nhận xét: 400 `JOB_POSTING_COMMENT_REQUIRED`, trước mọi khóa và tra cứu (cả với `{id}` không tồn tại).
4. Khóa tài khoản rồi phiên; kiểm hạn token, phiên, `JOB_POSTINGS_WRITE_ALL` và quyền đọc tin (401 `SESSION_INVALID` hoặc 403), trước mọi tra cứu.
5. Tin tồn tại: 404 `JOB_POSTING_NOT_FOUND`.
6. Khóa dòng yêu cầu rồi dòng tin, kiểm lại người gọi sau mỗi khóa.
7. Phạm vi đọc của người duyệt: 403.
8. Trạng thái nhận quyết định này, nếu không thì 409 `JOB_POSTING_NOT_UNDER_REVIEW`.
9. `expectedRevision`, nếu sai thì 409 `JOB_POSTING_CHANGED`.
10. Chỉ với `APPROVE`: người gọi không phải người soạn tin và không phải người gửi duyệt lần gần nhất, nếu không thì 403 `FORBIDDEN`.
11. Yêu cầu nguồn vẫn `APPROVED`, nếu không thì 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
12. Chỉ với `APPROVE`: hạn nộp chưa qua, nếu không thì 409 `JOB_POSTING_DEADLINE_PASSED`.
13. Đổi trạng thái (`revision` tăng 1) và ghi sự kiện với người duyệt, thời điểm và nhận xét.

Hai lỗi 409 ở bước 8, 9 đứng trước 403 ở bước 10 có chủ ý, giống [duyệt yêu cầu tuyển dụng](requisition-approvals.md): người tạo tin bấm duyệt một tin đã được người khác duyệt nhận lời nhắn tải lại, không phải lỗi quyền.

## Người soạn không tự duyệt

Người đã soạn hoặc sửa nội dung tin (người tạo và mọi người đã lưu bản sửa, bảng `job_posting_authors`) và người gửi duyệt lần gần nhất không được duyệt tin đó (403). Khác với yêu cầu tuyển dụng (người sửa không bị loại, ma trận mục 7.14), vì HR và ADMIN vừa soạn được mọi tin vừa là người duyệt. Người đã sửa thì không bao giờ duyệt được tin đó nữa; họ vẫn được yêu cầu chỉnh sửa và xuất bản tin đã được người khác duyệt.

- Quy tắc nằm ở một chỗ, `JobPostingReviewRule.mayApprove(người gọi, người soạn, người gửi gần nhất)`, dùng cho cả quyết định lẫn cờ `actions.canApprove`, nên nút duyệt và server không bao giờ lệch nhau.
- Người soạn được đọc sau khi đã khóa dòng tin. Mọi lần lưu (thêm người soạn) và gửi duyệt (đổi người gửi gần nhất) cũng giữ khóa này, nên danh sách người duyệt đọc được là cuối cùng cho lượt duyệt đó.
- "Người gửi gần nhất" là người của sự kiện `SUBMITTED` có `revision` lớn nhất. Người từng gửi ở lượt trước nhưng chưa từng sửa nội dung thì duyệt được lượt sau, khi người khác gửi lại.
- Ví dụ: ADMIN yêu cầu chỉnh sửa, HR tự sửa văn bản rồi recruiter gửi lại. HR đã thành người soạn nên không duyệt được; ADMIN duyệt. Nếu cả HR và ADMIN đều đã sửa tin, cần thêm một tài khoản có `JOB_POSTINGS_WRITE_ALL` để duyệt.

## Xuất bản

`POST /job-postings/{id}/publish` xuất bản một tin đã duyệt.

```json
{ "expectedRevision": 4 }
```

`expectedRevision` như khi gửi duyệt; trường khác (ví dụ `status`, `note`, `channels`, `publishedAt`) trả 400 `INVALID_JSON`: người xuất bản và thời điểm do server quyết định, không có kênh đăng. Response **200** là tin với `status: "PUBLISHED"` và, từ task 310, `publishedById`, `publishedByName`, `publishedAt` (mục "Người xuất bản và thời điểm công khai"). Mọi người có `JOB_POSTINGS_WRITE_ALL` xuất bản được, kể cả người vừa duyệt.

- Tin chưa được duyệt (`DRAFT`, `PENDING_APPROVAL`, `REVISION_REQUESTED`) trả 409 `JOB_POSTING_NOT_APPROVED` "Tin tuyển dụng chưa được duyệt nên chưa thể xuất bản.". Đây là lỗi "chưa được duyệt" mà frontend cần hiện.
- Tin đã xuất bản trả 409 `JOB_POSTING_ALREADY_PUBLISHED` "Tin tuyển dụng đã được xuất bản."; không có bước nào đưa tin ra khỏi `PUBLISHED` trong Sprint 3.
- Hạn nộp đã qua trả 409 `JOB_POSTING_DEADLINE_PASSED` như khi duyệt.

### Thứ tự kiểm khi xuất bản

1. Quyền ở `SecurityConfiguration`: `JOB_POSTINGS_WRITE_ALL` (401/403); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR`.
3. Khóa tài khoản rồi phiên; kiểm hạn token, phiên, `JOB_POSTINGS_WRITE_ALL` và quyền đọc tin (401/403), trước mọi tra cứu.
4. Tin tồn tại: 404 `JOB_POSTING_NOT_FOUND`.
5. Khóa dòng yêu cầu rồi dòng tin, kiểm lại người gọi sau mỗi khóa.
6. Phạm vi đọc: 403.
7. Đã xuất bản: 409 `JOB_POSTING_ALREADY_PUBLISHED`; chưa duyệt: 409 `JOB_POSTING_NOT_APPROVED`.
8. `expectedRevision`: 409 `JOB_POSTING_CHANGED`.
9. Yêu cầu nguồn vẫn `APPROVED`: 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.
10. Hạn nộp chưa qua: 409 `JOB_POSTING_DEADLINE_PASSED`.
11. Chuyển sang `PUBLISHED` (`revision` tăng 1) và ghi sự kiện `PUBLISHED`: người làm lấy từ token, thời điểm lấy từ đồng hồ server (UTC, micro giây), không có nhận xét. Từ task 310, cùng người và cùng thời điểm đó được ghi vào `published_by`, `published_at` của tin.

## Xem trước

`GET /job-postings/{id}/preview` (task 309) trả tin **đúng như ứng viên sẽ thấy** trên cổng tuyển dụng, ở mọi trạng thái (kể cả nháp), và không ghi gì vào database. Ai đọc được tin (`GET /job-postings/{id}`) thì xem trước được, cùng thứ tự kiểm: quyền (401/403, cả quyền đọc tin, trước mọi tra cứu), tin tồn tại (404 `JOB_POSTING_NOT_FOUND`), phạm vi (403). HIRING_MANAGER và APPROVER (chỉ có `JOB_POSTINGS_READ_ALL`) nhận 403 như khi đọc tin; họ xem tin đã xuất bản qua cổng công khai.

```json
{
  "id": "00000000-0000-0000-0000-000000000031",
  "status": "PENDING_APPROVAL",
  "revision": 2,
  "content": {
    "id": "00000000-0000-0000-0000-000000000031",
    "title": "Lập trình viên Java (Hà Nội)",
    "positionName": "Lập trình viên",
    "departmentName": "Phòng IT",
    "headcount": 2,
    "workLocationName": "Hà Nội",
    "employmentTypeName": "Toàn thời gian",
    "applicationDeadline": "2026-11-30",
    "jobDescription": "Phát triển API tuyển dụng.\n- Spring Boot",
    "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java.",
    "benefits": "Bảo hiểm đầy đủ."
  }
}
```

UUID trong ví dụ chỉ minh họa. `content` là đúng JSON của `GET /public/job-postings/{id}` (mục "Tin công khai"); `id`, `status`, `revision` ở ngoài là thông tin cho người soạn và người duyệt, không có trên cổng. Backend dùng chung một hàm dựng kết quả cho xem trước và tin công khai (`PublicJobPostingView.of`, từ cùng câu truy vấn `JobPostingReadRepository.SELECT_ROW`), như [trang giới thiệu công ty](company-profile.md#xem-trước), nên việc ẩn lương, tên danh mục và mọi trường khác giống hệt nhau ở hai nơi.

Người duyệt duyệt (`POST /job-postings/{id}/approvals`) và xuất bản (`POST /job-postings/{id}/publish`) với `expectedRevision` là `revision` của bản xem trước vừa xem. Nội dung tin bị khóa từ lúc gửi duyệt, và mọi thay đổi sau bản xem trước (yêu cầu chỉnh sửa, sửa, gửi lại) đều tăng `revision`, nên duyệt hoặc xuất bản theo bản xem trước cũ trả 409 `JOB_POSTING_CHANGED`: thứ được duyệt và xuất bản đúng là thứ người duyệt đã xem.

Tên chức danh, phòng ban, địa điểm và hình thức làm việc được đọc trực tiếp ở cả hai; đổi tên trong danh mục hiện ngay ở cả bản xem trước và tin công khai, không cần duyệt lại. Vì vậy bản xem trước và tin công khai luôn giống nhau tại cùng một thời điểm, nhưng tin công khai có thể khác bản xem trước lúc duyệt nếu sau đó có người đổi tên các mục này (câu hỏi 16).

HR_MANAGER và ADMIN sửa được các tên đó (`ORGANIZATION_WRITE_ALL`; chức danh cần thêm `SALARY_RANGES_WRITE_ALL`), và đổi tên không đổi `revision` của tin. Nếu BA/PO cần tin công khai đúng từng chữ với bản đã duyệt, backend sẽ chụp bốn tên này vào tin lúc gửi duyệt.

## Tin công khai

Cổng tuyển dụng cho ứng viên (task 309), không cần tài khoản:

| API | Trả về |
|---|---|
|`GET /public/job-postings?page=0&size=20`|Một trang tin đang công khai, tin xuất bản gần nhất trước|
|`GET /public/job-postings/{id}`|Một tin đang công khai, cùng dạng với `content` của bản xem trước|

Một tin là công khai khi đủ cả ba điều: trạng thái `PUBLISHED` (`JobPostingService.PUBLIC_STATUSES`); hạn nộp từ hôm nay trở đi theo giờ Việt Nam (chính ngày hạn vẫn hiện; từ 00:00 hôm sau tin tự ẩn nhưng vẫn ở `PUBLISHED`); yêu cầu nguồn vẫn `APPROVED`. Tin khác (nháp, chờ duyệt, cần chỉnh sửa, đã duyệt mà chưa xuất bản, quá hạn nộp, yêu cầu không còn đã duyệt) trả **404** `JOB_POSTING_NOT_FOUND` "Không tìm thấy tin tuyển dụng.", giống hệt tin không tồn tại, nên cổng không cho biết tin nào đang được soạn hay duyệt.

Trường của tin công khai: `id`, `title`, `positionName`, `departmentName`, `headcount`, `workLocationName`, `employmentTypeName`, `applicationDeadline`, `salaryMin`, `salaryMax`, `jobDescription`, `candidateRequirements`, `benefits` (`benefits` là `null` khi chưa viết). Không có trạng thái, `revision`, yêu cầu nguồn, người tạo, người duyệt, nhận xét hay thời điểm nào.

- Lương chỉ có khi tin chọn hiển thị (`showSalary: true`): `salaryMin`, `salaryMax` là lương đề xuất đã duyệt của yêu cầu (đồng VND mỗi người mỗi tháng). Khi không chọn, hai khóa này **không có** trong JSON (không phải `null`), và server không gửi số tiền đi.
- Mỗi mục của danh sách có `id`, `title`, `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`, `applicationDeadline` và lương nếu tin chọn hiển thị; văn bản chỉ có ở chi tiết. Trang có `items`, `page`, `size`, `totalElements`, `totalPages`; `page` từ 0, `size` từ 1 đến 100 (mặc định 0 và 20), sai thì 400 `VALIDATION_ERROR` "Trang hoặc số lượng tin tuyển dụng không hợp lệ.". Thứ tự: thời điểm xuất bản (`published_at` của tin, task 310) mới nhất trước; các tin xuất bản cùng lúc theo UUID giảm dần.
- Mọi response dùng `Cache-Control: no-store`, kể cả 404, vì tin rời cổng ngay khi quá hạn hoặc khi yêu cầu không còn đã duyệt.
- Như [trang giới thiệu công ty](company-profile.md), gửi kèm Bearer hỏng hoặc hết hạn vẫn bị 401 ở bộ lọc bảo mật, nên cổng tuyển dụng gọi hai API này **không** kèm header `Authorization`. Người đã đăng nhập (mọi vai trò) nhận đúng JSON như ứng viên.
- `permitAll` chỉ cho đúng phương thức GET và đúng hai đường dẫn này; ví dụ `POST /public/job-postings` hay `PUT /public/job-postings/{id}` trả 403.
- Hiển thị **mọi** chuỗi của tin công khai và của bản xem trước dạng chữ (không dùng `dangerouslySetInnerHTML`, `v-html` hay `innerHTML`), kể cả `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`: server chỉ kiểm văn bản thuần với `title`, `jobDescription`, `candidateRequirements`, `benefits` (task 304); bốn tên kia đến từ dữ liệu tổ chức, chỉ được kiểm độ dài.

## Xuất bản ở 308, dấu vết ở 310

Jira 310 ghi "Lưu dấu vết xuất bản và chuyển tin sang trạng thái có thể công khai", nhưng 308 phải "chặn xuất bản tin chưa được duyệt", nên thao tác xuất bản phải có từ 308. Ba task chia việc như sau:

- **308** thêm `POST /job-postings/{id}/publish`: chỉ nhận tin `APPROVED`, chuyển sang `PUBLISHED`, ghi sự kiện `PUBLISHED` (người làm từ token, thời điểm từ đồng hồ server).
- **309** cho tin `PUBLISHED` hiện trên `/api/v1/public/job-postings` và `/api/v1/public/job-postings/{id}` ("trạng thái có thể công khai") khi còn hạn nộp và yêu cầu còn đã duyệt; bản xem trước `GET /job-postings/{id}/preview` dùng chung nội dung với tin công khai (mục "Xem trước", "Tin công khai").
- **310** làm dấu vết xuất bản thành dữ liệu chính thức của tin: cột `published_by`, `published_at` có ràng buộc trong database (lấy lại từ sự kiện của 308 khi nâng cấp), trả ở mọi response nội bộ, API lịch sử duyệt và xuất bản, chặn sửa và xóa lịch sử, danh sách công khai sắp theo thời điểm xuất bản.

## Người xuất bản và thời điểm công khai (task 310)

Từ task 310, tin giữ người xuất bản và thời điểm công khai trong chính nó (cột `published_by`, `published_at` của V23). Mọi response nội bộ (`GET /job-postings/{id}` và kết quả của mọi thao tác ghi tin) có thêm ba trường:

```json
{
  "publishedById": "00000000-0000-0000-0000-000000000005",
  "publishedByName": "Nguyễn Thị Hoa",
  "publishedAt": "2026-10-11T03:04:00.987654Z"
}
```

| Trường | Ý nghĩa |
|---|---|
|publishedById, publishedByName|Người đã xuất bản tin (id và họ tên hiện tại, không có email); `null` khi tin chưa xuất bản|
|publishedAt|Thời điểm xuất bản (UTC, micro giây); `null` khi tin chưa xuất bản. Mỗi mục của `GET /job-postings` cũng có `publishedAt`|

- Chỉ `POST /job-postings/{id}/publish` ghi dấu vết, và chỉ một lần: người xuất bản lấy từ token của người gọi, thời điểm lấy từ đồng hồ server, không bao giờ từ body (gửi `publishedAt` hay trường lạ khác trả 400 `INVALID_JSON`). Đúng người và thời điểm đó cũng được ghi vào sự kiện `PUBLISHED` của lịch sử. Xuất bản lần hai trả 409 `JOB_POSTING_ALREADY_PUBLISHED` và không đổi dấu vết.
- Lần xuất bản bị từ chối (401, 403, 409) không ghi gì: tin chưa xuất bản luôn có ba trường `null`.
- Database bảo đảm (V23): có cả người lẫn thời điểm hoặc không có gì (`valid_job_posting_publication`); tin `PUBLISHED` luôn có dấu vết (`job_posting_publication_required`); tin ở `DRAFT`, `PENDING_APPROVAL`, `REVISION_REQUESTED`, `APPROVED` không có (`job_posting_unpublished_without_trail`). Người xuất bản là tài khoản không xóa được (`job_postings_published_by_fkey`, `ON DELETE RESTRICT`).
- Tin đã xuất bản trước V23 lấy người và thời điểm từ sự kiện `PUBLISHED` của nó khi nâng cấp.
- Tin công khai (`GET /public/job-postings`, `GET /public/job-postings/{id}`) và `content` của bản xem trước **cố ý không có** người xuất bản và thời điểm công khai, giống [trang giới thiệu công ty](company-profile.md) không cho ứng viên thấy ai đã cập nhật trang; nhờ vậy bản xem trước lúc duyệt vẫn đúng bằng tin công khai sau khi xuất bản. Danh sách công khai sắp theo `published_at`, tin xuất bản gần nhất trước.

## Lịch sử duyệt và xuất bản

`GET /job-postings/{id}/history` (task 310) trả **200** với mảng các bước của tin, bước mới nhất trước (`revision` giảm dần):

```json
[
  { "id": "00000000-0000-0000-0000-000000000071", "revision": 5, "action": "PUBLISHED",
    "statusBefore": "APPROVED", "statusAfter": "PUBLISHED", "actorId": "00000000-0000-0000-0000-000000000005",
    "actorName": "Nguyễn Thị Hoa", "occurredAt": "2026-10-11T03:04:00.987654Z", "comment": null },
  { "id": "00000000-0000-0000-0000-000000000070", "revision": 4, "action": "APPROVED",
    "statusBefore": "PENDING_APPROVAL", "statusAfter": "APPROVED", "actorId": "00000000-0000-0000-0000-000000000001",
    "actorName": "Quản trị viên", "occurredAt": "2026-10-11T03:03:00.333333Z", "comment": "Đồng ý đăng tin." },
  { "id": "00000000-0000-0000-0000-000000000069", "revision": 3, "action": "SUBMITTED",
    "statusBefore": "DRAFT", "statusAfter": "PENDING_APPROVAL", "actorId": "00000000-0000-0000-0000-000000000007",
    "actorName": "Trần Văn Tuyển", "occurredAt": "2026-10-11T03:02:00.222222Z", "comment": "Nhờ chị duyệt giúp." }
]
```

UUID trong ví dụ chỉ minh họa.

| Trường | Ý nghĩa |
|---|---|
|id|Id của bước (một dòng `job_posting_events`)|
|revision|Số phiên bản của tin ngay sau bước đó; khoảng trống là các lần tạo và lưu nội dung, không phải bước|
|action|`SUBMITTED` (gửi duyệt), `APPROVED` (duyệt), `REVISION_REQUESTED` (yêu cầu chỉnh sửa), `PUBLISHED` (xuất bản)|
|statusBefore, statusAfter|Trạng thái của tin trước và sau bước|
|actorId, actorName|Người làm (id và họ tên hiện tại); không có email, vai trò hay thông tin đăng nhập|
|occurredAt|Thời điểm (UTC, micro giây)|
|comment|Nhận xét khi gửi duyệt hoặc quyết định, giữ nguyên xuống dòng; `null` khi không có, luôn `null` với `PUBLISHED`|

- Tạo tin không phải một bước: frontend hiện dòng "Tạo" từ `createdByName`, `createdAt` của tin. Tin nháp chưa từng gửi duyệt có lịch sử rỗng `[]`.
- Bước `PUBLISHED` mới nhất mang đúng `publishedById` và `publishedAt` của tin.
- Quyền và thứ tự kiểm như đọc tin (`GET /job-postings/{id}`): quyền (401/403, cả quyền đọc tin, trước mọi tra cứu), tin tồn tại (404 `JOB_POSTING_NOT_FOUND`), phạm vi (403). HIRING_MANAGER và APPROVER (chỉ có `JOB_POSTINGS_READ_ALL`) nhận 403. Response dùng `Cache-Control: no-store` và không ghi gì vào database.

## Lịch sử không sửa, không xóa

Như lịch sử duyệt yêu cầu tuyển dụng (V20, task 268), từ V23 bảng `job_posting_events` chỉ thêm được dòng mới: trigger `job_posting_events_append_only` từ chối mọi `UPDATE` và `DELETE`, kể cả câu SQL viết tay, với lỗi "job_posting_events is append-only: UPDATE is not allowed" (hoặc `DELETE`, SQLSTATE `23514`). Không có API sửa hay xóa một bước: `PUT`, `PATCH`, `DELETE` trên `/job-postings/{id}/history` và `/job-postings/{id}/publish` trả 403 như mọi route không khai báo, và test `jobPostingTrailHasNoEditOrDeleteRoute` của ma trận quyền báo lỗi nếu ai đó thêm một route như vậy. `TRUNCATE` (chỉ chủ bảng làm được; test dùng để dọn dữ liệu) không chạy trigger theo dòng nên vẫn dùng được.

Cột `published_by`, `published_at` của tin không có trigger riêng: chỉ API ghi chúng, đúng một lần, và các CHECK ở trên giữ chúng khớp với trạng thái. Câu SQL viết tay vẫn sửa được hai cột này mà không đổi sự kiện `PUBLISHED` (giới hạn đã biết, ngoài phạm vi task 310).

## Kết quả trả về và lịch sử

Mọi API ở đây trả tin cùng dạng `GET /job-postings/{id}` ([API tin tuyển dụng](job-postings.md), mục "Danh sách và chi tiết"). Task 308 thêm vào dạng đó:

```json
{
  "actions": { "canEdit": false, "canSubmit": false, "canApprove": true, "canRequestRevision": true, "canPublish": false },
  "lastReview": {
    "action": "REVISION_REQUESTED",
    "comment": "Bổ sung quyền lợi.",
    "actorId": "00000000-0000-0000-0000-000000000005",
    "actorName": "Nguyễn Thị Hoa",
    "occurredAt": "2026-10-11T03:01:00.555555Z"
  }
}
```

| Trường | Ý nghĩa |
|---|---|
|actions.canEdit|`PUT` qua được phần kiểm trạng thái: tin ở `DRAFT`/`REVISION_REQUESTED` và yêu cầu nguồn còn `APPROVED`|
|actions.canSubmit|Gửi duyệt qua được phần kiểm trạng thái (cùng điều kiện như trên); chưa xét đủ thông tin|
|actions.canApprove|Người xem có `JOB_POSTINGS_WRITE_ALL`, tin ở `PENDING_APPROVAL`, yêu cầu nguồn còn `APPROVED`, và người xem không phải người soạn hay người gửi gần nhất|
|actions.canRequestRevision|Người xem có `JOB_POSTINGS_WRITE_ALL`, tin ở `PENDING_APPROVAL` hoặc `APPROVED`, và yêu cầu nguồn còn `APPROVED`|
|actions.canPublish|Người xem có `JOB_POSTINGS_WRITE_ALL`, tin ở `APPROVED`, và yêu cầu nguồn còn `APPROVED`|
|lastReview|Quyết định gần nhất của HR (`APPROVED` hoặc `REVISION_REQUESTED`): nhận xét, người duyệt (id và họ tên, không email), thời điểm; `null` khi chưa có quyết định nào|

Hạn nộp không nằm trong các cờ: tin quá hạn vẫn có `canApprove`/`canPublish`, và server trả 409 `JOB_POSTING_DEADLINE_PASSED` để giải thích. Từ task 290, Trưởng phòng Nhân sự [tạm dừng, đóng hoặc hủy được yêu cầu](requisition-status.md); khi yêu cầu nguồn không còn `APPROVED`, mọi cờ đều `false`, đúng như các bước trả 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`.

Mỗi bước được ghi một dòng vào `job_posting_events` (V22) trong cùng transaction với việc đổi trạng thái: số phiên bản của tin sau bước đó (`revision`), loại (`SUBMITTED`, `APPROVED`, `REVISION_REQUESTED`, `PUBLISHED`), trạng thái trước/sau, người làm, thời điểm, nhận xét. Tạo tin và lưu nội dung không phải bước duyệt nên không có sự kiện; vì vậy `revision` của lịch sử có khoảng trống. Lịch sử đọc qua `GET /job-postings/{id}/history` (task 310, mục "Lịch sử duyệt và xuất bản").

## Khóa và đồng thời

- Mọi bước khóa theo cùng thứ tự như lưu tin: tài khoản người gọi, phiên, dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`), rồi dòng tin. Gửi duyệt còn khóa giá trị danh mục `FOR SHARE` như khi lưu. Người gọi được kiểm lại sau mỗi khóa: token hết hạn, phiên bị thu hồi hay tài khoản bị khóa trả 401 `SESSION_INVALID`; bị gỡ quyền, bị hạ xuống `WRITE_SCOPED` hay bị bỏ khỏi nhóm recruiter trả 403. Không lưu gì.
- Hai người duyệt cùng một tin cùng lúc chạy lần lượt nhờ khóa: người sau nhận 409 `JOB_POSTING_NOT_UNDER_REVIEW`, chỉ một sự kiện `APPROVED` được ghi. Hai lần xuất bản cùng lúc: người sau nhận 409 `JOB_POSTING_ALREADY_PUBLISHED`. Chỉ mục unique `job_posting_events_one_publication_idx` là lớp chặn cuối trong database.
- Một thay đổi commit trong lúc request đang chờ khóa tin (người khác yêu cầu chỉnh sửa, lưu, gửi lại) được thấy và trả 409.
- Người soạn và người gửi gần nhất được đọc sau khi khóa dòng tin, nên quy tắc "người soạn không tự duyệt" không bị vượt qua bằng một lần lưu chen vào giữa.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|Body: thiếu hoặc sai `expectedRevision`, thiếu hoặc sai `action`, `comment` quá 2.000 ký tự hoặc có ký tự NUL; `{id}` không phải UUID; `page`/`size` của danh sách công khai sai ("Trang hoặc số lượng tin tuyển dụng không hợp lệ.")|
|400|`INVALID_JSON`|JSON sai hoặc thiếu body; trường ngoài hợp đồng (`status`, `note`, `reason`, `feedback`, `channels`, `publishedAt`, `approvedAt`…)|
|400|`JOB_POSTING_INCOMPLETE`|Gửi duyệt tin thiếu địa điểm, hình thức làm việc, hạn nộp, hoặc có văn bản đã lưu không còn hợp lệ ("Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt."); mọi trường trong `fieldErrors`|
|400|`JOB_POSTING_DEADLINE_IN_PAST`|Gửi duyệt tin có hạn nộp trước hôm nay theo giờ Việt Nam; `fieldErrors.applicationDeadline`|
|400|`JOB_POSTING_WORK_LOCATION_INACTIVE`, `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`|Gửi duyệt tin giữ giá trị danh mục đã ngừng dùng|
|400|`JOB_POSTING_COMMENT_REQUIRED`|`REQUEST_REVISION` không có nhận xét ("Vui lòng nhập nhận xét khi yêu cầu chỉnh sửa tin tuyển dụng."); `fieldErrors.comment`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn, phiên bị thu hồi, tài khoản bị khóa, kể cả khi điều đó xảy ra trong lúc chờ khóa; gọi API công khai kèm Bearer hỏng hoặc hết hạn|
|403|`FORBIDDEN`|Thiếu quyền; duyệt hoặc xuất bản không có `JOB_POSTINGS_WRITE_ALL`; người soạn/sửa tin hoặc người gửi duyệt lần gần nhất bấm duyệt; recruiter gửi duyệt tin của yêu cầu không được phân công; xem trước hoặc xem lịch sử tin ngoài phạm vi hoặc chỉ có `JOB_POSTINGS_READ_ALL`; route không khai báo (ví dụ `PUT /job-postings/{id}/publish`, `DELETE /job-postings/{id}/approvals`, `PATCH /job-postings/{id}/submit`, `POST /public/job-postings`, `PUT` hoặc `DELETE /job-postings/{id}/history`)|
|404|`JOB_POSTING_NOT_FOUND`|Không có tin với `{id}` (kể cả khi xem lịch sử); ở `GET /public/job-postings/{id}`, mọi tin không công khai (chưa xuất bản, quá hạn nộp, yêu cầu không còn đã duyệt), giống hệt tin không tồn tại|
|409|`JOB_POSTING_NOT_SUBMITTABLE`|Gửi duyệt tin không ở `DRAFT`/`REVISION_REQUESTED` ("Chỉ gửi duyệt được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.")|
|409|`JOB_POSTING_NOT_UNDER_REVIEW`|Quyết định ở trạng thái không nhận nó|
|409|`JOB_POSTING_NOT_APPROVED`|Xuất bản tin chưa được duyệt|
|409|`JOB_POSTING_ALREADY_PUBLISHED`|Xuất bản lần hai|
|409|`JOB_POSTING_DEADLINE_PASSED`|Duyệt hoặc xuất bản khi hạn nộp đã qua ("Hạn nộp hồ sơ của tin đã qua. Vui lòng yêu cầu chỉnh sửa để đổi hạn nộp trước khi duyệt hoặc xuất bản.")|
|409|`JOB_POSTING_CHANGED`|`expectedRevision` cũ|
|409|`JOB_POSTING_REQUISITION_NOT_APPROVED`|Yêu cầu nguồn không còn `APPROVED` (mọi bước)|

## Ghi chú cho frontend

Frontend nhánh `UI` (`src/services/jobPostingService.ts`, `JobPostingForm.tsx`, `JobPostingPreviewApproval.tsx`, `JobPostingAuditHistory.tsx`) cần sửa:

| Frontend hiện tại | Backend |
|---|---|
|"Đăng tin" gửi `POST /job-postings` với `status: 'PUBLISHED'`|Lưu (`POST`/`PUT`), rồi `POST /job-postings/{id}/submit {expectedRevision}`; người soạn không tự xuất bản (task 300)|
|Trang `/job-postings/:id/review` bỏ qua `:id` và dùng dữ liệu mẫu|Đọc `:id` (`useParams`); `GET /job-postings/{id}` lấy `status`, `actions`, `lastReview`; danh sách chờ duyệt là `GET /job-postings?status=PENDING_APPROVAL`; bản xem trước là `GET /job-postings/{id}/preview` (task 309) (task 306)|
|`/approve {note, status: 'PUBLISHED', approvedAt}`, `/request-revision {feedback, …}`, `/reject {reason, …}`|`POST /job-postings/{id}/approvals {action: "APPROVE" \| "REQUEST_REVISION", comment, expectedRevision}`; duyệt xong tin là **`APPROVED`**, chưa công khai; không có từ chối; nhận xét bắt buộc khi yêu cầu chỉnh sửa, tối đa 2.000 ký tự (task 306)|
|`/publish {note, channels, status, publishedAt}`, chưa được gọi|`POST /job-postings/{id}/publish {expectedRevision}`; hiện nút khi `actions.canPublish`; hiện lỗi 409 `JOB_POSTING_NOT_APPROVED` ("lỗi chưa được duyệt"), `JOB_POSTING_CHANGED` (tải lại), `JOB_POSTING_DEADLINE_PASSED` (task 307)|
|`/audit-logs` với `actor {name, email, role}`, `metadata`|`GET /job-postings/{id}/history` (task 310): mảng `[{id, revision, action, statusBefore, statusAfter, actorId, actorName, occurredAt, comment}]`, bước mới nhất trước, không có email hay vai trò; dòng "Tạo" lấy từ `createdByName`, `createdAt` của tin; thêm `APPROVED` vào bộ lọc (`JobPostingAuditHistory.tsx`); người xuất bản và thời điểm công khai lấy từ `publishedByName`, `publishedAt` của tin (task 307)|
|Nút dựa vào `REJECTED`/`PUBLISHED`; `APPROVED` hiện "Đã duyệt xuất bản"|Ẩn hiện nút theo `actions.*` (nút duyệt ẩn với người đã tạo hoặc sửa tin và người gửi gần nhất, nếu không sẽ 403 và bị đưa sang `/unauthorized`); nhãn `DRAFT` Nháp, `PENDING_APPROVAL` Chờ duyệt, `REVISION_REQUESTED` Cần chỉnh sửa, `APPROVED` Đã duyệt, `PUBLISHED` Đã xuất bản (task 306, 307)|
|Tên người cứng ("Nguyễn Văn Minh"), khối `catch` giả thành công|Bỏ; hiện `message` của lỗi, gắn `fieldErrors` (thêm `expectedRevision`, `comment`, `action`, và các trường của `JOB_POSTING_INCOMPLETE`) vào ô tương ứng; dùng `createdByName`, `lastReview.actorName`, `publishedByName`|
|Bản xem trước bỏ qua cờ lương (`JobPostingForm.tsx`, `JobPostingPreviewApproval.tsx`)|Màn duyệt hiển thị `content` của `GET /job-postings/{id}/preview`; khi lương bị ẩn thì không có khóa `salaryMin`/`salaryMax`; giữ `revision` của bản xem trước làm `expectedRevision` khi duyệt và xuất bản (task 306)|
|Chưa có trang công khai; `axiosClient` luôn gắn Bearer|Cổng tuyển dụng gọi `GET /public/job-postings` và `GET /public/job-postings/{id}` qua một axios instance **không** có `Authorization`; 404 là "không tìm thấy tin" (task sau)|
|Hiển thị bản xem trước và tin công khai|Hiển thị mọi chuỗi dạng chữ, kể cả `positionName`, `departmentName`, `workLocationName`, `employmentTypeName` (mục "Tin công khai") (task 306 và cổng tuyển dụng)|
|Màn duyệt và xuất bản dựa vào `REQUISITIONS_READ_*` hoặc `can('view','job_posting')`|Duyệt và xuất bản: `JOB_POSTINGS_WRITE_ALL`; gửi duyệt và xem trước: như sửa tin (`can('edit','job_posting')`)|

Gặp 403 khi bấm duyệt thường do người xem đã soạn, sửa hoặc vừa gửi tin; nên giải thích "người đã soạn/sửa tin không được duyệt" thay vì chuyển trang. Hiển thị nhận xét dạng chữ, giữ xuống dòng.

QA cần chuẩn bị: HR phải tạo giá trị `WORK_LOCATION` và `EMPLOYMENT_TYPE` (V10 không tạo sẵn) và phân công recruiter trước; cần **hai** tài khoản có `JOB_POSTINGS_WRITE_ALL` (HR và ADMIN, hoặc hai HR), vì người đã tạo, sửa hoặc gửi tin không duyệt được nó. HR đã sửa tin sau khi yêu cầu chỉnh sửa thì không duyệt được tin đó nữa, nên một tài khoản `JOB_POSTINGS_WRITE_ALL` khác phải duyệt.

## Database và phạm vi

V22 (task 308) mở CHECK trạng thái của `job_postings`, thêm CHECK `job_posting_details_required` (tin đã rời trạng thái sửa được phải có địa điểm, hình thức làm việc, hạn nộp) và tạo bảng `job_posting_events`, xem [database](../database/README.md). Không thêm mã quyền: dùng `JOB_POSTINGS_WRITE_ALL` có sẵn từ V3. Code nằm ở gói `vn.ttcs.recruitment.jobposting`: `JobPostingService.submit` (gửi duyệt), `JobPostingApprovalService` (`decide`, `publish`), `JobPostingReviewRule` (người soạn không tự duyệt), `JobPostingEventRepository` (ghi và đọc lịch sử). Task 309 không thêm migration: `JobPostingService.preview`, `getPublic`, `publicPage` đọc qua `JobPostingReadRepository` (`findRow`, `findPublic`, `publicPage`, cùng `SELECT_ROW`) và dựng kết quả bằng `PublicJobPostingView.of`. V23 (task 310) thêm `published_by`, `published_at` vào `job_postings` (lấy lại từ sự kiện `PUBLISHED` khi nâng cấp), ba CHECK của dấu vết xuất bản, hai chỉ mục và trigger chặn sửa, xóa `job_posting_events`; code: `JobPosting.publish`, `JobPostingApprovalService.history`, `JobPostingEventRepository.findHistory`, `JobPostingHistoryEntry`.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md). Gồm:

- không có trạng thái Từ chối; người duyệt dùng "yêu cầu chỉnh sửa";
- duyệt và xuất bản là hai bước, cùng cần `JOB_POSTINGS_WRITE_ALL`, nên ADMIN cũng làm được;
- người đã soạn hoặc sửa nội dung tin (kể cả HR sửa sau khi yêu cầu chỉnh sửa) và người gửi duyệt lần gần nhất không được duyệt tin đó; họ vẫn được yêu cầu chỉnh sửa và xuất bản sau khi người khác đã duyệt; người đã sửa thì không bao giờ duyệt được tin đó nữa, nên có thể cần thêm một tài khoản `JOB_POSTINGS_WRITE_ALL`; quy tắc này chặt hơn yêu cầu tuyển dụng (câu hỏi 15);
- tin đã gửi duyệt, đã duyệt hoặc đã xuất bản không sửa được; HR yêu cầu chỉnh sửa từ chờ duyệt hoặc đã duyệt; tin đã xuất bản chưa sửa, gỡ hay đóng được (gỡ tin khi đóng yêu cầu là task 292);
- gửi duyệt mới bắt buộc địa điểm, hình thức làm việc, hạn nộp và kiểm lại văn bản đã lưu;
- hạn nộp đã qua thì duyệt và xuất bản bị chặn (409), yêu cầu chỉnh sửa vẫn được;
- giá trị danh mục đã ngừng dùng chặn gửi duyệt nhưng không chặn duyệt, xuất bản và không gỡ tin;
- tin đã xuất bản tự ẩn khỏi cổng khi qua hạn nộp (từ 00:00 ngày sau ngày hạn, giờ Việt Nam) hoặc khi yêu cầu không còn đã duyệt, nhưng vẫn ở trạng thái đã xuất bản (task 309);
- tên chức danh, phòng ban, địa điểm và hình thức làm việc trên bản xem trước và tin công khai được đọc trực tiếp: đổi tên sau khi duyệt hoặc sau khi xuất bản hiện ngay trên cổng mà không cần duyệt lại (task 309, câu hỏi 16);
- cổng công khai hiện tiêu đề, chức danh, phòng ban, số lượng, địa điểm, hình thức làm việc, hạn nộp, lương nếu chọn, mô tả công việc, yêu cầu ứng viên, quyền lợi; không hiện ngày đăng và người xuất bản (task 310, câu hỏi 16);
- gọi API công khai kèm Bearer hỏng vẫn bị 401; cổng tuyển dụng gọi không kèm token (task 309, câu hỏi 16).
