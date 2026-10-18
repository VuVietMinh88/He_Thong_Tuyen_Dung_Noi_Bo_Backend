# API duyệt và xuất bản tin tuyển dụng

Phạm vi TKNHTTDNB1-308 (API duyệt và xuất bản tin tuyển dụng), story TKNHTTDNB1-39 (S3-10). URL dùng tiền tố `/api/v1`, header `Authorization: Bearer …`; mọi response JSON thành công và các lỗi `JOB_POSTING_*` dùng `Cache-Control: no-store`. Soạn và lưu bản nháp nằm ở [API tin tuyển dụng](job-postings.md) (story S3-09).

Kết quả Jira của task 308: "Kiểm tra quyền và chặn xuất bản tin chưa được duyệt."

Tiêu chí của story S3-10:

- "Tin phải qua duyệt trước khi hiển thị công khai": đã có (308). Chỉ tin `APPROVED` mới xuất bản được (mục "Xuất bản"); tin ở mọi trạng thái khác trả 409. Cổng tuyển dụng công khai (task 309) chỉ hiện tin `PUBLISHED`.
- "Xem trước đúng như giao diện công khai trước khi xuất bản": một phần (308). Từ lúc gửi duyệt, nội dung tin bị khóa (không `PUT` được) và mọi bước duyệt, xuất bản đều mang `expectedRevision`, nên tin được xuất bản đúng là nội dung đã duyệt lần cuối. API xem trước dùng chung nội dung với tin công khai là task 309.
- "Ghi lại người xuất bản và thời điểm": một phần (308). Mỗi lần xuất bản ghi sự kiện `PUBLISHED` với người làm và thời điểm vào lịch sử `job_posting_events`. Cột người và thời điểm xuất bản trên tin, API lịch sử và việc chặn sửa lịch sử là task 310 (mục "Xuất bản ở 308, dấu vết ở 310").

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
- Mỗi cột "Có" là một nhóm trong code: `JobPostingService.EDITABLE_STATUSES` (sửa), `JobPostingService.SUBMITTABLE_STATUSES` (gửi duyệt), `JobPostingDecision.from()` (duyệt, yêu cầu chỉnh sửa), `JobPostingApprovalService.PUBLISHABLE_STATUSES` (xuất bản). `JobPostingStatusTest` và `JobPostingDecisionTest` không biên dịch được cho tới khi một trạng thái mới được xếp vào từng nhóm.
- Mỗi bước (gửi duyệt, duyệt, yêu cầu chỉnh sửa, xuất bản) tăng `revision` thêm 1, như một lần lưu, nên client đang xem trạng thái cũ bị từ chối (409 `JOB_POSTING_CHANGED`). `updatedAt` vẫn là lần sửa nội dung gần nhất, các bước này không đổi nó.
- Tin đã duyệt không sửa được: HR yêu cầu chỉnh sửa, người soạn sửa và gửi lại, rồi cần một lần duyệt mới. Vì vậy thứ được xuất bản luôn là nội dung được duyệt lần cuối.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /job-postings/{id}/submit`|`JOB_POSTINGS_WRITE_ALL` **hoặc** `JOB_POSTINGS_WRITE_SCOPED`, **và** quyền đọc `JOB_POSTINGS_READ_*` (như sửa tin)|ADMIN, HR_MANAGER (mọi tin); RECRUITER (tin của yêu cầu được phân công)|
|`POST /job-postings/{id}/approvals`|`JOB_POSTINGS_WRITE_ALL` (ở matcher và kiểm lại trong service), **và** quyền đọc tin|ADMIN, HR_MANAGER, trừ người đã soạn hoặc sửa tin và người gửi duyệt lần gần nhất khi **duyệt** (mục "Người soạn không tự duyệt")|
|`POST /job-postings/{id}/publish`|Như dòng trên|ADMIN, HR_MANAGER, kể cả người vừa duyệt tin|

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

`expectedRevision` như khi gửi duyệt; trường khác (ví dụ `status`, `note`, `channels`, `publishedAt`) trả 400 `INVALID_JSON`: người xuất bản và thời điểm do server quyết định, không có kênh đăng. Response **200** là tin với `status: "PUBLISHED"`. Mọi người có `JOB_POSTINGS_WRITE_ALL` xuất bản được, kể cả người vừa duyệt.

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
11. Chuyển sang `PUBLISHED` (`revision` tăng 1) và ghi sự kiện `PUBLISHED`: người làm lấy từ token, thời điểm lấy từ đồng hồ server (UTC, micro giây), không có nhận xét.

## Xuất bản ở 308, dấu vết ở 310

Jira 310 ghi "Lưu dấu vết xuất bản và chuyển tin sang trạng thái có thể công khai", nhưng 308 phải "chặn xuất bản tin chưa được duyệt", nên thao tác xuất bản phải có từ 308. Ba task chia việc như sau:

- **308** thêm `POST /job-postings/{id}/publish`: chỉ nhận tin `APPROVED`, chuyển sang `PUBLISHED`, ghi sự kiện `PUBLISHED` (người làm từ token, thời điểm từ đồng hồ server).
- **309** cho tin `PUBLISHED` hiện trên `/api/v1/public/job-postings` và `/api/v1/public/job-postings/{id}` ("trạng thái có thể công khai").
- **310** làm dấu vết xuất bản thành dữ liệu chính thức của tin: cột `published_by`, `published_at` có ràng buộc trong database (lấy lại từ sự kiện của 308 khi nâng cấp), trả ở mọi response nội bộ, API lịch sử duyệt và xuất bản, chặn sửa và xóa lịch sử, danh sách công khai sắp theo thời điểm xuất bản.

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
|actions.canApprove|Người xem có `JOB_POSTINGS_WRITE_ALL`, tin ở `PENDING_APPROVAL`, và người xem không phải người soạn hay người gửi gần nhất|
|actions.canRequestRevision|Người xem có `JOB_POSTINGS_WRITE_ALL` và tin ở `PENDING_APPROVAL` hoặc `APPROVED`|
|actions.canPublish|Người xem có `JOB_POSTINGS_WRITE_ALL` và tin ở `APPROVED`|
|lastReview|Quyết định gần nhất của HR (`APPROVED` hoặc `REVISION_REQUESTED`): nhận xét, người duyệt (id và họ tên, không email), thời điểm; `null` khi chưa có quyết định nào|

Hạn nộp không nằm trong các cờ: tin quá hạn vẫn có `canApprove`/`canPublish`, và server trả 409 `JOB_POSTING_DEADLINE_PASSED` để giải thích.

Mỗi bước được ghi một dòng vào `job_posting_events` (V22) trong cùng transaction với việc đổi trạng thái: số phiên bản của tin sau bước đó (`revision`), loại (`SUBMITTED`, `APPROVED`, `REVISION_REQUESTED`, `PUBLISHED`), trạng thái trước/sau, người làm, thời điểm, nhận xét. Tạo tin và lưu nội dung không phải bước duyệt nên không có sự kiện; vì vậy `revision` của lịch sử có khoảng trống. API đọc lịch sử là task 310.

## Khóa và đồng thời

- Mọi bước khóa theo cùng thứ tự như lưu tin: tài khoản người gọi, phiên, dòng yêu cầu tuyển dụng (`FOR NO KEY UPDATE`), rồi dòng tin. Gửi duyệt còn khóa giá trị danh mục `FOR SHARE` như khi lưu. Người gọi được kiểm lại sau mỗi khóa: token hết hạn, phiên bị thu hồi hay tài khoản bị khóa trả 401 `SESSION_INVALID`; bị gỡ quyền, bị hạ xuống `WRITE_SCOPED` hay bị bỏ khỏi nhóm recruiter trả 403. Không lưu gì.
- Hai người duyệt cùng một tin cùng lúc chạy lần lượt nhờ khóa: người sau nhận 409 `JOB_POSTING_NOT_UNDER_REVIEW`, chỉ một sự kiện `APPROVED` được ghi. Hai lần xuất bản cùng lúc: người sau nhận 409 `JOB_POSTING_ALREADY_PUBLISHED`. Chỉ mục unique `job_posting_events_one_publication_idx` là lớp chặn cuối trong database.
- Một thay đổi commit trong lúc request đang chờ khóa tin (người khác yêu cầu chỉnh sửa, lưu, gửi lại) được thấy và trả 409.
- Người soạn và người gửi gần nhất được đọc sau khi khóa dòng tin, nên quy tắc "người soạn không tự duyệt" không bị vượt qua bằng một lần lưu chen vào giữa.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|Body: thiếu hoặc sai `expectedRevision`, thiếu hoặc sai `action`, `comment` quá 2.000 ký tự hoặc có ký tự NUL; `{id}` không phải UUID|
|400|`INVALID_JSON`|JSON sai hoặc thiếu body; trường ngoài hợp đồng (`status`, `note`, `reason`, `feedback`, `channels`, `publishedAt`, `approvedAt`…)|
|400|`JOB_POSTING_INCOMPLETE`|Gửi duyệt tin thiếu địa điểm, hình thức làm việc, hạn nộp, hoặc có văn bản đã lưu không còn hợp lệ ("Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt."); mọi trường trong `fieldErrors`|
|400|`JOB_POSTING_DEADLINE_IN_PAST`|Gửi duyệt tin có hạn nộp trước hôm nay theo giờ Việt Nam; `fieldErrors.applicationDeadline`|
|400|`JOB_POSTING_WORK_LOCATION_INACTIVE`, `JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE`|Gửi duyệt tin giữ giá trị danh mục đã ngừng dùng|
|400|`JOB_POSTING_COMMENT_REQUIRED`|`REQUEST_REVISION` không có nhận xét ("Vui lòng nhập nhận xét khi yêu cầu chỉnh sửa tin tuyển dụng."); `fieldErrors.comment`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn, phiên bị thu hồi, tài khoản bị khóa, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền; duyệt hoặc xuất bản không có `JOB_POSTINGS_WRITE_ALL`; người soạn/sửa tin hoặc người gửi duyệt lần gần nhất bấm duyệt; recruiter gửi duyệt tin của yêu cầu không được phân công; route không khai báo (ví dụ `PUT /job-postings/{id}/publish`, `DELETE /job-postings/{id}/approvals`, `PATCH /job-postings/{id}/submit`)|
|404|`JOB_POSTING_NOT_FOUND`|Không có tin với `{id}`|
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
|Trang `/job-postings/:id/review` bỏ qua `:id` và dùng dữ liệu mẫu|Đọc `:id` (`useParams`); `GET /job-postings/{id}` lấy `status`, `actions`, `lastReview`; danh sách chờ duyệt là `GET /job-postings?status=PENDING_APPROVAL`. Bản xem trước `GET /job-postings/{id}/preview` có từ task 309 (task 306)|
|`/approve {note, status: 'PUBLISHED', approvedAt}`, `/request-revision {feedback, …}`, `/reject {reason, …}`|`POST /job-postings/{id}/approvals {action: "APPROVE" \| "REQUEST_REVISION", comment, expectedRevision}`; duyệt xong tin là **`APPROVED`**, chưa công khai; không có từ chối; nhận xét bắt buộc khi yêu cầu chỉnh sửa, tối đa 2.000 ký tự (task 306)|
|`/publish {note, channels, status, publishedAt}`, chưa được gọi|`POST /job-postings/{id}/publish {expectedRevision}`; hiện nút khi `actions.canPublish`; hiện lỗi 409 `JOB_POSTING_NOT_APPROVED` ("lỗi chưa được duyệt"), `JOB_POSTING_CHANGED` (tải lại), `JOB_POSTING_DEADLINE_PASSED` (task 307)|
|`/audit-logs` với `actor {name, email, role}`, `metadata`|Lịch sử `GET /job-postings/{id}/history` là task 310; hiện có `lastReview` (quyết định gần nhất) trên tin (task 307)|
|Nút dựa vào `REJECTED`/`PUBLISHED`; `APPROVED` hiện "Đã duyệt xuất bản"|Ẩn hiện nút theo `actions.*` (nút duyệt ẩn với người đã tạo hoặc sửa tin và người gửi gần nhất, nếu không sẽ 403 và bị đưa sang `/unauthorized`); nhãn `DRAFT` Nháp, `PENDING_APPROVAL` Chờ duyệt, `REVISION_REQUESTED` Cần chỉnh sửa, `APPROVED` Đã duyệt, `PUBLISHED` Đã xuất bản (task 306, 307)|
|Tên người cứng ("Nguyễn Văn Minh"), khối `catch` giả thành công|Bỏ; hiện `message` của lỗi, gắn `fieldErrors` (thêm `expectedRevision`, `comment`, `action`, và các trường của `JOB_POSTING_INCOMPLETE`) vào ô tương ứng; dùng `createdByName`, `lastReview.actorName`|
|Màn duyệt và xuất bản dựa vào `REQUISITIONS_READ_*` hoặc `can('view','job_posting')`|Duyệt và xuất bản: `JOB_POSTINGS_WRITE_ALL`; gửi duyệt: như sửa tin (`can('edit','job_posting')`)|

Gặp 403 khi bấm duyệt thường do người xem đã soạn, sửa hoặc vừa gửi tin; nên giải thích "người đã soạn/sửa tin không được duyệt" thay vì chuyển trang. Hiển thị nhận xét dạng chữ, giữ xuống dòng.

QA cần chuẩn bị: HR phải tạo giá trị `WORK_LOCATION` và `EMPLOYMENT_TYPE` (V10 không tạo sẵn) và phân công recruiter trước; cần **hai** tài khoản có `JOB_POSTINGS_WRITE_ALL` (HR và ADMIN, hoặc hai HR), vì người đã tạo, sửa hoặc gửi tin không duyệt được nó. HR đã sửa tin sau khi yêu cầu chỉnh sửa thì không duyệt được tin đó nữa, nên một tài khoản `JOB_POSTINGS_WRITE_ALL` khác phải duyệt.

## Database và phạm vi

V22 (task 308) mở CHECK trạng thái của `job_postings`, thêm CHECK `job_posting_details_required` (tin đã rời trạng thái sửa được phải có địa điểm, hình thức làm việc, hạn nộp) và tạo bảng `job_posting_events`, xem [database](../database/README.md). Không thêm mã quyền: dùng `JOB_POSTINGS_WRITE_ALL` có sẵn từ V3. Code nằm ở gói `vn.ttcs.recruitment.jobposting`: `JobPostingService.submit` (gửi duyệt), `JobPostingApprovalService` (`decide`, `publish`), `JobPostingReviewRule` (người soạn không tự duyệt), `JobPostingEventRepository` (ghi lịch sử).

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md). Gồm:

- không có trạng thái Từ chối; người duyệt dùng "yêu cầu chỉnh sửa";
- duyệt và xuất bản là hai bước, cùng cần `JOB_POSTINGS_WRITE_ALL`, nên ADMIN cũng làm được;
- người đã soạn hoặc sửa nội dung tin (kể cả HR sửa sau khi yêu cầu chỉnh sửa) và người gửi duyệt lần gần nhất không được duyệt tin đó; họ vẫn được yêu cầu chỉnh sửa và xuất bản sau khi người khác đã duyệt; người đã sửa thì không bao giờ duyệt được tin đó nữa, nên có thể cần thêm một tài khoản `JOB_POSTINGS_WRITE_ALL`; quy tắc này chặt hơn yêu cầu tuyển dụng (câu hỏi 15);
- tin đã gửi duyệt, đã duyệt hoặc đã xuất bản không sửa được; HR yêu cầu chỉnh sửa từ chờ duyệt hoặc đã duyệt; tin đã xuất bản chưa sửa, gỡ hay đóng được (gỡ tin khi đóng yêu cầu là task 292);
- gửi duyệt mới bắt buộc địa điểm, hình thức làm việc, hạn nộp và kiểm lại văn bản đã lưu;
- hạn nộp đã qua thì duyệt và xuất bản bị chặn (409), yêu cầu chỉnh sửa vẫn được;
- giá trị danh mục đã ngừng dùng chặn gửi duyệt nhưng không chặn duyệt, xuất bản và không gỡ tin.
