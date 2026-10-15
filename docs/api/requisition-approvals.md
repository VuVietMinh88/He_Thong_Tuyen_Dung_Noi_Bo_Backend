# API duyệt yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-256 (chọn cấp duyệt theo phòng ban và hạn mức) và TKNHTTDNB1-257 (giữ phiên bản phê duyệt cho yêu cầu đang chạy), story TKNHTTDNB1-30 (S3-01); TKNHTTDNB1-261 (lưu quyết định và nhận xét của từng cấp duyệt) TKNHTTDNB1-262 (API quyết định phê duyệt) TKNHTTDNB1-263 (chuyển cấp và hoàn tất phê duyệt) và TKNHTTDNB1-264 (trả yêu cầu bổ sung cho người tạo), story TKNHTTDNB1-31 (S3-02); TKNHTTDNB1-267 (API tiến độ và lịch sử phê duyệt) và TKNHTTDNB1-268 (bảo vệ lịch sử phê duyệt không bị sửa), story TKNHTTDNB1-32 (S3-03). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`. Cấu hình luồng phê duyệt (task 254, 255) nằm ở [API cấu hình luồng phê duyệt](approval-flows.md).

Tiêu chí của story S3-01:

- "Khai báo chuỗi cấp duyệt theo phòng ban và theo mức lương đề xuất": đã có (254, 255).
- "Yêu cầu vượt hạn mức tự động thêm cấp duyệt cao hơn": đã có (256, mục "Chọn cấp duyệt").
- "Cấu hình sai (không có cấp duyệt nào khớp) bị chặn lưu": đã có. Cấp 1 không được có ngưỡng (255), nên mọi mức lương đều khớp ít nhất cấp 1.
- "Luồng đang chạy không bị ảnh hưởng khi cấu hình thay đổi": đã có (257, mục "Bản chụp chuỗi duyệt").

Task 256 chọn cấp duyệt, task 257 thêm `POST /requisitions/{id}/submit` (gửi duyệt) và chụp chuỗi duyệt lúc gửi.

## Trạng thái của yêu cầu tuyển dụng

| Mã | Tên | Ý nghĩa |
|---|---|---|
|`DRAFT`|Nháp|Đã lưu, chưa gửi duyệt (hoặc mới sao chép)|
|`PENDING_APPROVAL`|Chờ duyệt|Đã gửi duyệt, đang chờ cấp hiện tại|
|`RETURNED`|Cần bổ sung|Người duyệt yêu cầu bổ sung thông tin|
|`APPROVED`|Đã duyệt|Mọi cấp của lượt duyệt đã duyệt; [tin tuyển dụng](job-postings.md) được tạo từ yêu cầu ở trạng thái này|
|`REJECTED`|Bị từ chối|Kết thúc; muốn thử lại thì sao chép yêu cầu|
|`PAUSED`|Tạm dừng|Trưởng phòng Nhân sự tạm ngừng tuyển; mở lại thì về `APPROVED` (task 293)|
|`FILLED`|Đã tuyển đủ|Kết thúc (task 293)|
|`CANCELLED`|Đã hủy|Kết thúc (task 293)|

V19 thêm bốn trạng thái mới cùng lúc. Task 257 chỉ dùng `DRAFT → PENDING_APPROVAL`; các bước còn lại đến từ quyết định duyệt.

V24 (task 293, story S3-07) thêm `PAUSED`, `FILLED`, `CANCELLED`. Chúng không đến từ luồng duyệt mà từ thay đổi trạng thái của Trưởng phòng Nhân sự (API ở task 290); bảng chuyển trạng thái và lý do nằm ở [Tạm dừng, đóng và hủy yêu cầu tuyển dụng](requisition-status.md).

Mỗi trạng thái được xếp sẵn vào các nhóm sau. `RequisitionStatus` ghi danh sách các nhóm, và test không biên dịch được cho tới khi một trạng thái mới được xếp nhóm. Từ task 303, nhóm "Tạo tin tuyển dụng" là `JobPostingService.SOURCE_STATUSES`: tin chỉ được tạo, và chỉ được sửa tiếp, khi yêu cầu ở một trạng thái trong nhóm này. Từ task 297, nhóm "Trễ hạn" là `RequisitionListItemView.OVERDUE_STATUSES`: chỉ yêu cầu ở các trạng thái này mới có `overdue` trong [danh sách](requisitions.md).

| Mã | Sửa (`PUT`) | Gửi duyệt | Phân công recruiter | Tính vào định biên | Chặn xóa phòng ban (còn mở) | Trễ hạn (298) | Sao chép | Tạo tin tuyển dụng |
|---|---|---|---|---|---|---|---|---|
|`DRAFT`|Có|Có|Có|Có|Có|Có|Có|Không|
|`PENDING_APPROVAL`|Không|Không|Có|Có|Có|Có|Có|Không|
|`RETURNED`|Có|Có|Có|Có|Có|Có|Có|Không|
|`APPROVED`|Không|Không|Có|Có|Có|Có|Có|Có|
|`REJECTED`|Không|Không|Không|Không|Không (vẫn chặn bằng khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Không|Có|Không|
|`PAUSED`|Không|Không|Có|Có|Có|Không|Có|Không|
|`FILLED`|Không|Không|Không|Có|Không (khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Không|Có|Không|
|`CANCELLED`|Không|Không|Không|Không|Không (khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Không|Có|Không|

- Sửa yêu cầu không ở trạng thái sửa được trả 409 `REQUISITION_NOT_DRAFT`. Từ task 264, yêu cầu `RETURNED` được sửa và gửi lại như bản nháp.
- Yêu cầu bị từ chối hoặc đã hủy trả lại chỗ trong định biên; yêu cầu tạm dừng hoặc đã tuyển đủ vẫn giữ chỗ (task 293). Không có bước nào, kể cả thay đổi trạng thái của Trưởng phòng Nhân sự, đưa yêu cầu từ trạng thái không tính về trạng thái tính, nên gửi duyệt, quyết định duyệt và đổi trạng thái không phải kiểm định biên lại.
- Bản sao luôn là `DRAFT` và **không** có lịch sử duyệt: lịch sử gắn với `id` của yêu cầu.
- Gửi duyệt và quyết định duyệt không đổi `updatedAt`, vốn là thời điểm sửa nội dung gần nhất.

## Gửi duyệt

`POST /requisitions/{id}/submit` gửi yêu cầu đã lưu đi duyệt. Body không bắt buộc:

```json
{ "comment": "Cần gấp cho dự án mới" }
```

`comment` là ghi chú cho người duyệt, tối đa 2.000 ký tự, không chứa ký tự NUL; để trống, hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả khoảng trắng không ngắt U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B), thì lưu `null`. Ghi chú có ký tự nhìn thấy được lưu đúng như gửi, giữ xuống dòng. Trường khác (ví dụ `status`) trả `INVALID_JSON`. Muốn đổi nội dung thì `PUT` trước rồi mới gửi.

Response 200 là yêu cầu tuyển dụng (cùng dạng `GET /requisitions/{id}`) với `status: "PENDING_APPROVAL"`.

Quyền: `REQUISITIONS_WRITE_ALL` hoặc `REQUISITIONS_WRITE_SCOPED`, theo cùng phạm vi như sửa yêu cầu. Vì response trả cả yêu cầu, người gọi còn phải **xem** được yêu cầu đó (giống sao chép): người chỉ có quyền ghi bị 403, kể cả với `{id}` không tồn tại.

Yêu cầu phải đủ thông tin; thiếu thì trả 400 `REQUISITION_INCOMPLETE` với `fieldErrors` gồm **mọi** trường còn thiếu:

| Trường | Thông báo |
|---|---|
|`proposedSalaryMin`|Vui lòng nhập lương đề xuất tối thiểu trước khi gửi duyệt.|
|`proposedSalaryMax`|Vui lòng nhập lương đề xuất tối đa trước khi gửi duyệt.|
|`neededBy`|Vui lòng chọn ngày cần người trước khi gửi duyệt.|
|`jobDescription`|Vui lòng nhập mô tả công việc trước khi gửi duyệt.|
|`candidateRequirements`|Vui lòng nhập yêu cầu ứng viên trước khi gửi duyệt.|

Rồi các kiểm tra của lần lưu chạy lại với giá trị hiện tại: ngày cần người không ở quá khứ, chức danh và phòng ban còn áp dụng, giải trình khi lương đề xuất ngoài dải chuẩn **hiện tại** của chức danh.

### Thứ tự kiểm

1. Quyền ở `SecurityConfiguration` (401/403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors.comment`.
3. Khóa tài khoản người gọi, rồi phiên; kiểm lại hạn token, phiên và quyền ghi (401 `SESSION_INVALID` / 403).
4. Khóa dòng yêu cầu, kiểm lại người gọi. Không có quyền xem yêu cầu nào thì 403 ngay.
5. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
6. Phòng ban của yêu cầu nằm trong phạm vi ghi và xem của người gọi (403).
7. Trạng thái cho phép gửi duyệt: `DRAFT` hoặc `RETURNED` (409 `REQUISITION_NOT_SUBMITTABLE`). Bấm gửi hai lần thì lần sau nhận lỗi này.
8. Đủ thông tin (400 `REQUISITION_INCOMPLETE`).
9. Ngày cần người không ở quá khứ (400 `NEEDED_BY_IN_PAST`).
10. Khóa `FOR SHARE` chức danh rồi phòng ban: tồn tại và còn áp dụng (400 `INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`). Kiểm lại người gọi và phạm vi.
11. Giải trình khi ngoài dải chuẩn (400 `SALARY_JUSTIFICATION_REQUIRED`).
12. Chọn chuỗi duyệt (409 `APPROVAL_FLOW_NOT_CONFIGURED`, 409 `APPROVAL_LEVEL_UNAVAILABLE`).
13. Ghi lượt duyệt mới và chuyển yêu cầu sang `PENDING_APPROVAL`.

Định biên không được kiểm lại: yêu cầu đã được tính từ khi lưu và không xin thêm gì.

## Bản chụp chuỗi duyệt

Mỗi lần gửi duyệt mở một **lượt duyệt** (round). Lượt đầu là 1. Backend ghi một dòng `SUBMITTED` vào lịch sử duyệt (bảng `requisition_workflow_events`, V19) gồm:

- phiên bản cấu hình được dùng (`flow_id`, `flow_version`);
- số cấp áp dụng (`level_count`): lượt duyệt gồm các cấp 1 đến `level_count` của phiên bản đó;
- lương đề xuất tối đa lúc gửi (`salary_basis`), con số đã quyết định số cấp;
- người gửi, thời điểm gửi và ghi chú.

Phiên bản cấu hình đã niêm phong không bao giờ đổi hay bị xóa (V18), và dòng lịch sử tham chiếu tới nó bằng khóa ngoại, nên dòng này **chính là** bản chụp chuỗi duyệt. HR lưu phiên bản cấu hình mới thì các lượt duyệt đang chạy vẫn theo phiên bản cũ; chỉ lần gửi duyệt sau mới dùng phiên bản mới. Trong lúc chờ duyệt, yêu cầu không sửa được, nên lương và phòng ban đã quyết định số cấp cũng không đổi.

## Quyết định của cấp duyệt

`POST /requisitions/{id}/approvals` (task 262) là quyết định của cấp đang chờ:

```json
{ "action": "REJECT", "comment": "Mức lương vượt kế hoạch quý IV", "expectedRevision": 1 }
```

| Trường | Quy tắc |
|---|---|
|action|Bắt buộc, đúng chữ hoa: `APPROVE` (duyệt, task 263), `REJECT` (từ chối, task 262) hoặc `REQUEST_INFO` (yêu cầu bổ sung, task 264)|
|comment|Nhận xét. Bắt buộc khi từ chối hoặc yêu cầu bổ sung; tối đa 2.000 ký tự, không chứa ký tự NUL; để trống hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B) coi như không có; nhận xét có ký tự nhìn thấy được giữ nguyên xuống dòng|
|expectedRevision|Bắt buộc, số nguyên dương: `revision` của tiến độ duyệt người duyệt đang xem, tức số thứ tự của sự kiện mới nhất trong lịch sử duyệt (1 ngay sau lần gửi duyệt đầu tiên, tăng 1 sau mỗi lần gửi duyệt, quyết định hoặc thay đổi trạng thái (task 293); task 267 trả số này ở `GET /requisitions/{id}/approvals`). Lệch với lịch sử hiện tại (bấm hai lần, người khác vừa quyết định) thì trả 409 để tải lại|

Trường khác (ví dụ `reason`) trả `INVALID_JSON`. Response 200 là yêu cầu tuyển dụng với trạng thái mới.

Quyền ở matcher: `REQUISITIONS_WRITE_ALL` hoặc `REQUISITIONS_WRITE_SCOPED`, vì quyết định là thao tác ghi đổi trạng thái. Ai được quyết định một cấp do mục "Ai được duyệt một cấp" quyết định, **không** theo phạm vi phòng ban.

### Thứ tự kiểm khi quyết định

1. Quyền ở `SecurityConfiguration` (401/403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors`.
3. Thiếu nhận xét khi bắt buộc: 400 `APPROVAL_COMMENT_REQUIRED` với `fieldErrors.comment`.
4. Khóa tài khoản người gọi rồi phiên; kiểm lại hạn token, phiên và quyền ghi (401 `SESSION_INVALID` / 403).
5. Khóa dòng yêu cầu, kiểm lại người gọi. Không có quyền xem yêu cầu nào thì 403 ngay, trước khi biết yêu cầu có tồn tại không.
6. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
7. Khóa `FOR SHARE` phòng ban của yêu cầu, kiểm lại người gọi.
8. Người gọi xem được yêu cầu (mục "Ai được xem yêu cầu"), nếu không thì 403 (recruiter được phân công không được tính, task 285).
9. Yêu cầu đang chờ duyệt (409 `REQUISITION_NOT_PENDING_APPROVAL`).
10. `expectedRevision` đúng `revision` mới nhất (409 `APPROVAL_STATE_CHANGED`).
11. Người gọi được quyết định cấp hiện tại (403).
12. Ghi quyết định vào lịch sử và đổi trạng thái của yêu cầu.

Hai lỗi 409 đứng trước lỗi 403 ở bước 11 có chủ ý: hai người cùng giữ vai trò của một cấp cùng bấm thì người sau nhận thông báo tải lại, không phải lỗi quyền (giao diện chuyển mọi 403 sang `/unauthorized`).

Khóa dòng phòng ban làm việc đổi người phụ trách hoặc phòng ban cha của phòng ban đó phải chờ quyết định này, hoặc quyết định thấy thay đổi đã lưu. Tài khoản người gọi được đọc khi đang khóa, nên vai trò bị gỡ trong lúc chờ cũng được thấy. Hai quyết định cùng lúc cho một cấp chạy lần lượt trên dòng yêu cầu: chỉ một quyết định được ghi. Database còn chặn hai quyết định cho cùng một cấp của một lượt.

### Chuyển cấp và hoàn tất

| Hành động | Cấp hiện tại | Kết quả |
|---|---|---|
|`APPROVE`|Chưa phải cấp cuối của lượt|Yêu cầu vẫn `PENDING_APPROVAL`, cấp kế tiếp chờ duyệt|
|`APPROVE`|Cấp cuối|Yêu cầu sang `APPROVED` (Đã duyệt)|
|`REJECT`|Bất kỳ|Yêu cầu sang `REJECTED`, lượt duyệt kết thúc|
|`REQUEST_INFO`|Bất kỳ|Yêu cầu sang `RETURNED`, lượt duyệt kết thúc; bên yêu cầu sửa rồi gửi lại|

Task 263: duyệt xong một cấp thì tự chuyển sang cấp kế tiếp, cấp cuối duyệt thì yêu cầu sang `APPROVED`. Mỗi người chỉ duyệt một cấp trong một lượt, nên người đã duyệt cấp 1 không duyệt tiếp cấp 2 dù giữ cả vai trò của cấp 2. Lượt duyệt đang chạy giữ các cấp của phiên bản cấu hình lúc gửi: HR đổi người duyệt trong phiên bản mới thì người mới chỉ duyệt các lượt sau. Yêu cầu đã duyệt vẫn được tính vào định biên và vẫn được phân công recruiter; không sửa, không gửi lại.

### Yêu cầu bổ sung và gửi lại

Task 264 ("Yêu cầu bổ sung trả hồ sơ về cho người tạo và giữ nguyên lịch sử"):

1. Người duyệt cấp hiện tại gửi `REQUEST_INFO` kèm nhận xét (bắt buộc) nói cần bổ sung gì. Yêu cầu sang `RETURNED`. Các quyết định trước đó của lượt (ví dụ cấp 1 đã duyệt) và mọi lượt cũ vẫn nằm nguyên trong lịch sử.
2. Bên yêu cầu sửa bằng `PUT /requisitions/{id}`, với đúng các kiểm tra của bản nháp: phạm vi phòng ban, chức danh và phòng ban còn áp dụng, giải trình lương, ngày cần người, và định biên (vượt định biên thì cần HR xác nhận kèm lý do như khi lưu nháp). Có thể chuyển yêu cầu sang phòng ban khác trong phạm vi; lượt sau dùng cấu hình của phòng ban mới.
3. Gửi lại bằng `POST /requisitions/{id}/submit`. Đây là **lượt duyệt mới**, bắt đầu từ cấp 1, với phiên bản cấu hình và lương đề xuất tối đa **hiện tại**: lương tăng quá ngưỡng thì có thêm cấp. Người đã duyệt ở lượt trước được duyệt lại ở lượt mới; người gửi lại thì không.

"Trả về người tạo" nghĩa là trả về phía bên yêu cầu: ai sửa được yêu cầu (quyền ghi theo phòng ban) đều sửa và gửi lại được, kể cả người tạo nếu vẫn phụ trách phòng ban. Tiến độ duyệt ghi người tạo là người cần xử lý. Yêu cầu `RETURNED` vẫn giữ chỗ trong định biên.

## Ai được xem yêu cầu

Người có `REQUISITIONS_READ_SCOPED` vốn chỉ xem yêu cầu của phòng ban mình phụ trách. Từ task 262, người **tham gia duyệt** một yêu cầu cũng xem được nó, kể cả khi không phụ trách phòng ban nào (ví dụ vai trò APPROVER):

- người đã quyết định một cấp của yêu cầu, ở bất kỳ lượt nào, mãi về sau;
- người mà một cấp được chuyển tới (tài khoản được chỉ định, hoặc người giữ vai trò theo bảng ở mục "Ai được duyệt một cấp"), khi lượt duyệt đã **tới** cấp đó. Cấp chưa tới thì chưa mở gì. Cấp đang chờ khi Trưởng phòng Nhân sự hủy yêu cầu vẫn tính là đã tới (task 293): người được chuyển tới vẫn xem được yêu cầu, và nếu bấm quyết định trên trang cũ thì nhận 409 `REQUISITION_NOT_PENDING_APPROVAL` để tải lại, không phải 403.

Quy tắc này áp dụng cho `GET /requisitions/{id}`, `GET /requisitions/{id}/assignment`, `GET /requisitions/{id}/assignment-history`, `GET /requisitions/{id}/approvals` và quyết định duyệt. Danh sách `GET /requisitions` không gồm yêu cầu mà người gọi chỉ tham gia duyệt; `GET /requisitions/{id}/headcount-overrides` và sao chép vẫn chỉ theo phòng ban. Quyền ghi nội dung (sửa, gửi duyệt, sao chép, phân công) không được mở rộng.

Từ task 285, recruiter đang được phân công (chính hoặc hỗ trợ) cũng xem được yêu cầu, phân công, lịch sử phân công và tiến độ duyệt (`actions.canDecide` vẫn theo người duyệt), và yêu cầu đó có trong danh sách của họ; quyết định duyệt không tính phân công (vẫn 403, không phải 409). Xem mục "Recruiter được phân công" của [API yêu cầu tuyển dụng](requisitions.md).

## Lịch sử quyết định

Task 261: mỗi quyết định của một cấp được ghi thêm một dòng vào lịch sử duyệt (`requisition_workflow_events`), cùng transaction với trạng thái mới của yêu cầu. Mỗi dòng ghi:

- người quyết định (`actor_id`) và thời điểm (`occurred_at`, đến micro giây);
- kết quả: `APPROVED` (duyệt), `REJECTED` (từ chối) hoặc `INFO_REQUESTED` (yêu cầu bổ sung), cùng trạng thái của yêu cầu trước và sau;
- lượt duyệt (`round_number`) và cấp (`level_number`);
- nhận xét, giữ nguyên xuống dòng. Từ chối và yêu cầu bổ sung bắt buộc có nhận xét (database cũng kiểm).

Mỗi cấp của một lượt chỉ có một quyết định (`requisition_workflow_events_one_decision_idx`). Tiến độ của lượt (cấp nào đã duyệt, đang chờ cấp nào) không lưu riêng mà tính từ các dòng này: các cấp duyệt lần lượt, lượt còn mở khi mọi quyết định đến giờ đều là duyệt và chưa đủ số cấp.

## Tiến độ và lịch sử duyệt

`GET /requisitions/{id}/approvals` (task 267) trả tiến độ của lượt duyệt mới nhất và toàn bộ lịch sử. Từ task 293, lịch sử có cả các lần Trưởng phòng Nhân sự đổi trạng thái (tạm dừng, mở lại, đã tuyển đủ, hủy), xem [Tạm dừng, đóng và hủy yêu cầu tuyển dụng](requisition-status.md). Quyền như `GET /requisitions/{id}` (mục "Ai được xem yêu cầu"): 401/403, 404, rồi 403 khi ngoài phạm vi.

```json
{
  "requisitionId": "6f0c1f9e-8f3e-4b67-9a39-2c1d3f4e5a6b",
  "status": "PENDING_APPROVAL",
  "createdById": "00000000-0000-0000-0000-000000000021",
  "createdByName": "Trưởng IT",
  "revision": 2,
  "round": 1,
  "flow": {"id": "0d7e…", "version": 1, "name": "Duyệt tuyển dụng phòng kỹ thuật"},
  "levelCount": 3,
  "currentLevel": 2,
  "submittedById": "00000000-0000-0000-0000-000000000021",
  "submittedByName": "Trưởng IT",
  "submittedAt": "2026-10-11T03:00:00Z",
  "levels": [
    {"level": 1, "stepName": "Cấp 1 · Trưởng phòng Nhân sự", "approverType": "ROLE", "approverUserId": null,
     "approverRole": "HR_MANAGER", "approverRoleName": "Trưởng phòng Nhân sự", "addedBySalaryLimit": false,
     "status": "APPROVED", "approverName": "Nguyễn Thị Hoa", "decidedById": "…", "comment": "Đồng ý",
     "actionDate": "2026-10-11T03:00:30Z"},
    {"level": 2, "stepName": "Cấp 2 · Giám đốc A", "approverType": "USER", "approverUserId": "…",
     "approverRole": null, "approverRoleName": null, "addedBySalaryLimit": true, "status": "PENDING",
     "approverName": "Giám đốc A", "decidedById": null, "comment": null, "actionDate": null},
    {"level": 3, "stepName": "Cấp 3 · Người duyệt", "approverType": "ROLE", "approverUserId": null,
     "approverRole": "APPROVER", "approverRoleName": "Người duyệt", "addedBySalaryLimit": true,
     "status": "NOT_REACHED", "approverName": "Người duyệt", "decidedById": null, "comment": null, "actionDate": null}
  ],
  "waitingOn": {"kind": "APPROVAL_LEVEL", "level": 2, "label": "Cấp 2 · Giám đốc A", "hasEligibleApprover": true,
                "people": [{"userId": "…", "fullName": "Giám đốc A"}]},
  "actions": {"canDecide": false},
  "history": [
    {"id": "…", "revision": 2, "round": 1, "level": 1, "action": "APPROVED", "statusBefore": "PENDING_APPROVAL",
     "statusAfter": "PENDING_APPROVAL", "actorId": "…", "actorName": "Nguyễn Thị Hoa",
     "occurredAt": "2026-10-11T03:00:30Z", "comment": "Đồng ý", "flowVersion": null, "levelCount": null,
     "salaryBasis": null},
    {"id": "…", "revision": 1, "round": 1, "level": null, "action": "SUBMITTED", "statusBefore": "DRAFT",
     "statusAfter": "PENDING_APPROVAL", "actorId": "…", "actorName": "Trưởng IT",
     "occurredAt": "2026-10-11T03:00:00Z", "comment": null, "flowVersion": 1, "levelCount": 3,
     "salaryBasis": 60000000}
  ]
}
```

- `revision` là số thứ tự của sự kiện mới nhất (gửi duyệt, quyết định hoặc thay đổi trạng thái, task 293); gửi lại đúng số này trong `expectedRevision` khi quyết định. Chưa gửi duyệt lần nào thì `revision` là 0, `round`, `flow`, `levelCount`, `currentLevel`, `submitted*`, `waitingOn` là `null`, `levels` và `history` là mảng rỗng.
- `levels` là các cấp của lượt mới nhất. `status`: `APPROVED`, `REJECTED`, `INFO_REQUESTED` khi cấp đã quyết định; `PENDING` với cấp đang chờ; `NOT_REACHED` với cấp chưa tới, hoặc cấp đang chờ khi yêu cầu bị hủy (task 293; không ai quyết định cấp đó). `approverName` là người đã quyết định, hoặc người/vai trò được chuyển tới khi chưa quyết định.
- `currentLevel` chỉ có khi đang chờ duyệt.
- `waitingOn` cho biết đang chờ ai: `APPROVAL_LEVEL` kèm những người được quyết định cấp đó lúc này (`people`); `hasEligibleApprover: false` nghĩa là hiện không ai quyết định được (ví dụ người duyệt bị khóa sau khi gửi). `REQUESTER` khi đang chờ bên yêu cầu bổ sung (`people` là người tạo). Đã duyệt, bị từ chối, tạm dừng, đã tuyển đủ hay đã hủy thì `null`.
- `actions.canDecide` cho biết người gọi có được quyết định cấp hiện tại không.
- `history` gồm mọi sự kiện của mọi lượt, mới nhất trước, xếp theo `revision` (hai sự kiện có thể cùng thời điểm). `flowVersion`, `levelCount`, `salaryBasis` chỉ có ở `SUBMITTED`. Dòng thay đổi trạng thái (task 293) có `action` là `PAUSED`, `RESUMED`, `FILLED` hoặc `CANCELLED`, `round` và `level` là `null`, `comment` là lý do.
- Tên người được đọc lúc trả, nên tài khoản đổi tên thì hiện tên mới. Ngưỡng lương của cấu hình **không bao giờ** được trả; chỉ có `addedBySalaryLimit`.

## Hộp chờ duyệt

`GET /requisitions/approval-inbox?page=0&size=20` (task 267) trả các yêu cầu đang chờ một cấp mà người gọi **được quyết định lúc này** (cùng quy tắc với quyết định), chờ lâu nhất trước, cùng dạng trang với `GET /requisitions`: `{items, page, size, totalElements, totalPages}`; mỗi item là 15 trường của yêu cầu, không có `daysOpen`, `daysUntilNeededBy`, `overdue` của danh sách (task 298). `page` từ 0, `size` từ 1 đến 100 và `page × size` không quá 2.147.483.647, sai thì 400 `VALIDATION_ERROR`. Người không có quyền ghi yêu cầu tuyển dụng nhận trang rỗng. Quyền ở matcher: `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`. Thông báo cho người duyệt thuộc Sprint 8, nên hộp chờ duyệt là cách người duyệt tìm việc.

## Lịch sử không sửa, không xóa

Task 268 ("Lịch sử không sửa và không xoá được"):

- Không có API sửa hay xóa quyết định, nhận xét hay lượt duyệt. `PUT`, `PATCH`, `DELETE` trên các đường dẫn duyệt trả 403 với mọi vai trò, kể cả ADMIN; test ma trận quyền kiểm không có route nào như vậy.
- Backend chỉ thêm và đọc dòng lịch sử (`RequisitionApprovalRepository`), không có entity JPA nên không thể vô tình ghi đè.
- Database chặn cả SQL viết tay: trigger `requisition_workflow_events_append_only` (V20) từ chối mọi `UPDATE` và `DELETE` trên bảng lịch sử.
- Khóa ngoại `RESTRICT` giữ yêu cầu, tài khoản và phiên bản cấu hình mà lịch sử tham chiếu; phiên bản cấu hình đã niêm phong cũng không sửa được (V18).
- Ngoại lệ có chủ ý: `TRUNCATE` (chỉ chủ bảng chạy được, test dùng để dọn dữ liệu) không chạy trigger theo dòng, giống V18. Muốn chặn cả việc này thì ứng dụng phải chạy bằng tài khoản database không phải chủ bảng.

## Khóa và đồng thời

- Gửi duyệt khóa theo thứ tự như sửa yêu cầu: tài khoản người gọi, phiên, dòng yêu cầu, rồi `FOR SHARE` chức danh và phòng ban. Người gọi được kiểm lại sau mỗi lần chờ khóa: token hết hạn trả 401, bị gỡ quyền trả 403, bị Admin khóa hay đăng xuất trả 401.
- Lưu phiên bản cấu hình mới khóa dòng phòng ban `FOR UPDATE`. Vì vậy một lần gửi duyệt hoặc dùng phiên bản đã có trước đó, hoặc chờ tới khi phiên bản mới lưu xong rồi dùng phiên bản mới; không bao giờ dùng phiên bản đang lưu dở.
- Hai lần gửi duyệt cùng lúc chạy lần lượt trên dòng yêu cầu; lần sau trả 409 `REQUISITION_NOT_SUBMITTABLE`. Database có lớp chặn cuối: mỗi lượt duyệt chỉ có một dòng `SUBMITTED`, và số thứ tự (`revision`) không trùng trong một yêu cầu.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`comment` quá 2.000 ký tự hoặc chứa ký tự NUL; `{id}` không phải UUID; khi quyết định: `action` thiếu hoặc không phải thao tác hợp lệ, `expectedRevision` thiếu hoặc nhỏ hơn 1 (task 262); hộp chờ duyệt: `page` âm, `size` ngoài 1–100 hoặc `page × size` quá 2.147.483.647 (task 267)|
|400|`INVALID_JSON`|JSON sai hoặc có trường không được phép|
|400|`REQUISITION_INCOMPLETE`|Gửi duyệt khi còn thiếu thông tin (`fieldErrors` liệt kê đủ)|
|400|`NEEDED_BY_IN_PAST`, `INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`, `SALARY_JUSTIFICATION_REQUIRED`|Như khi lưu yêu cầu|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn hoặc phiên bị thu hồi, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền (kể cả bị gỡ quyền trong lúc chờ); không có quyền xem; yêu cầu ngoài phạm vi|
|404|`REQUISITION_NOT_FOUND`|Không có yêu cầu với `{id}`|
|409|`REQUISITION_NOT_SUBMITTABLE`|Yêu cầu không ở trạng thái nháp hoặc cần bổ sung (đã gửi rồi chẳng hạn)|
|409|`APPROVAL_FLOW_NOT_CONFIGURED`|Phòng ban chưa có cấu hình luồng duyệt|
|409|`APPROVAL_LEVEL_UNAVAILABLE`|Có cấp không có người duyệt hợp lệ, hoặc không đủ người khác nhau cho các cấp|
|400|`APPROVAL_COMMENT_REQUIRED`|Từ chối hoặc yêu cầu bổ sung mà không có nhận xét|
|403|`FORBIDDEN`|Quyết định: không xem được yêu cầu, hoặc không được quyết định cấp hiện tại|
|409|`REQUISITION_NOT_PENDING_APPROVAL`|Quyết định khi yêu cầu không còn chờ duyệt|
|409|`APPROVAL_STATE_CHANGED`|`expectedRevision` không còn là `revision` mới nhất (bấm lại, người khác vừa quyết định)|

## Chọn cấp duyệt

`ApprovalChainSelector` chọn chuỗi duyệt cho một yêu cầu:

1. Lấy cấu hình luồng duyệt của **đúng** phòng ban của yêu cầu, ở phiên bản hiện hành. Không lấy cấu hình của phòng ban cấp trên, không có cấu hình mặc định cho cả công ty. Phòng ban chưa có cấu hình thì trả 409 `APPROVAL_FLOW_NOT_CONFIGURED`.
2. So `proposedSalaryMax` của yêu cầu (lương tháng tối đa của một người, VND) với ngưỡng của từng cấp:
   - cấp không có ngưỡng luôn áp dụng;
   - cấp có ngưỡng chỉ áp dụng khi lương **lớn hơn** ngưỡng; bằng ngưỡng thì không thêm cấp.

   Vì cấp 1 không có ngưỡng và các ngưỡng sau không giảm, các cấp được chọn luôn là cấp 1 đến cấp k. Lương càng cao thì càng thêm cấp cao hơn. Quy tắc này dùng chung với `POST /approval-flows/preview` (`ApprovalChain`).
3. Kiểm mỗi cấp được chọn có người duyệt được (mục "Ai được duyệt một cấp"), và mỗi cấp có **một người khác nhau**, vì một người chỉ duyệt một cấp trong một lượt duyệt. Nếu không thì trả 409 `APPROVAL_LEVEL_UNAVAILABLE`, kèm tên các cấp không duyệt được, ví dụ "cấp 1 (Trưởng bộ phận)". Nếu không đủ người duyệt thì yêu cầu sẽ kẹt mãi ở một cấp, nên hệ thống chặn ngay lúc gửi.

Phần kiểm ở bước 3 đọc tài khoản mà không khóa. Mỗi lần duyệt, người duyệt được kiểm lại trên tài khoản đã khóa.

## Ai được duyệt một cấp

Mỗi cấp của cấu hình chọn **một tài khoản** (`approverUserId`) hoặc **một vai trò** (`approverRole`).

| Cấp chỉ định | Ai được duyệt |
|---|---|
|Một tài khoản|Chỉ tài khoản đó|
|Vai trò `APPROVER`, hoặc vai trò có `REQUISITIONS_WRITE_ALL` (theo seed: `HR_MANAGER`, `ADMIN`)|Bất kỳ ai giữ vai trò đó, với yêu cầu của mọi phòng ban|
|Vai trò khác (theo seed: `HIRING_MANAGER`, `RECRUITER`)|Người giữ vai trò đó **và** phụ trách phòng ban của yêu cầu hoặc một phòng ban cấp trên|

Ngoài ra người duyệt phải:

- được phép đăng nhập: đã kích hoạt, không bị quản trị khóa. Khóa tạm vì nhập sai mật khẩu không tính;
- có quyền ghi (`REQUISITIONS_WRITE_ALL` hoặc `_SCOPED`) **và** quyền xem (`REQUISITIONS_READ_ALL` hoặc `_SCOPED`) yêu cầu tuyển dụng. Quyền ghi không bao gồm quyền đọc;
- không phải người tạo yêu cầu, không phải người gửi duyệt lượt này;
- chưa duyệt cấp khác trong cùng lượt duyệt;
- với cấp chỉ định vai trò: không phải người được một cấp khác của cùng chuỗi chỉ định đích danh. Nhờ vậy cấp chỉ định đích danh luôn còn người duyệt.

`HR_MANAGER` và `ADMIN` không có quyền duyệt thay. Họ chỉ duyệt cấp chỉ định vai trò của họ hoặc tài khoản của họ.

## Ghi chú cho frontend

Các màn hình duyệt ở nhánh `UI` (`ApprovalActionUI.tsx`, `ApprovalHistoryTimeline.tsx`, `ApprovalWorkflowConfigUI.tsx`) chưa được gắn route, nên đổi theo hợp đồng này được ngay:

| Frontend hiện tại | Backend |
|---|---|
|`POST /requisitions/{id}/submit` (danh sách gửi backend)|Đúng đường dẫn; body không bắt buộc `{comment}`; trả yêu cầu với `PENDING_APPROVAL`|
|`POST /requisitions/{id}/approve`, `/reject`; `POST /approvals/{id}/action {action, comment}`|`POST /requisitions/{id}/approvals {action, comment, expectedRevision}`, `action` là `APPROVE`, `REJECT` hoặc `REQUEST_INFO`. Trường lý do tên là `comment`, gửi `reason` sẽ bị `INVALID_JSON`|
|`ApprovalActionUI.tsx` bắt nhận xét cho mọi thao tác|Nhận xét chỉ bắt buộc khi từ chối và yêu cầu bổ sung|
|Chưa có nút "Yêu cầu bổ sung"|`action: "REQUEST_INFO"`|
|`GET /approvals/{id}/history` (mảng `{stepName, approverName, status, comment, actionDate}`)|`GET /requisitions/{id}/approvals`: hiển thị `.levels` (đủ năm trường đó), `status` thêm `INFO_REQUESTED` ("Yêu cầu bổ sung") và `NOT_REACHED` ("Chưa đến lượt"); dùng `.history` cho dòng thời gian qua các lượt; lấy `expectedRevision` từ `.revision`|
|Trạng thái `PENDING` trong `Requisition.status` và bộ lọc|`PENDING_APPROVAL`; thêm `RETURNED`. `PENDING`, `OPEN`, `CLOSED` trả 400; `PAUSED`, `FILLED` (frontend gọi `CLOSED`), `CANCELLED` có từ V24 (task 293)|
|Chưa có hộp chờ duyệt|`GET /requisitions/approval-inbox`, cùng dạng trang với danh sách|
|Nút thao tác|Hiện nút quyết định theo `actions.canDecide`. Gặp 409 `APPROVAL_STATE_CHANGED` hoặc `REQUISITION_NOT_PENDING_APPROVAL` thì báo và tải lại|
|Cấu hình luồng duyệt (`/approval-workflows/config`)|Dùng [API cấu hình luồng phê duyệt](approval-flows.md): `departmentId`, `salaryThreshold`, `approverUserId` hoặc `approverRole`, `expectedVersion`, UUID thật|

Nhãn trạng thái: `DRAFT` Nháp; `PENDING_APPROVAL` Chờ duyệt; `RETURNED` Cần bổ sung; `APPROVED` Đã duyệt; `REJECTED` Bị từ chối; `PAUSED` Tạm dừng; `FILLED` Đã tuyển đủ; `CANCELLED` Đã hủy.

Tạm dừng, mở lại, đóng vì đã tuyển đủ và hủy yêu cầu (task 290) nằm ở [Tạm dừng, đóng và hủy yêu cầu tuyển dụng](requisition-status.md), mục "Ghi chú cho frontend". Sau khi yêu cầu bị hủy lúc đang chờ duyệt, người duyệt bấm quyết định trên trang cũ nhận 409 `REQUISITION_NOT_PENDING_APPROVAL` (tải lại), không bị chuyển sang /unauthorized.

Người duyệt chỉ xem yêu cầu qua quyền tham gia duyệt, và recruiter chỉ xem qua phân công (task 285), thì vẫn nhận 403 ở `GET /requisitions/{id}/headcount-overrides` và `GET /headcount-plans/remaining` của phòng ban khác. Màn hình chi tiết không nên gọi hai API này khi người xem không phụ trách phòng ban, hoặc phải tự xử lý 403 của chúng thay vì chuyển sang `/unauthorized`.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend. Gồm:

- so ngưỡng bằng `proposedSalaryMax`, lớn hơn hẳn ngưỡng; không so số lượng người hay chi phí cả năm;
- phòng ban chưa có cấu hình thì không gửi duyệt được, không dùng cấu hình của phòng ban cấp trên;
- các cấp duyệt lần lượt, mỗi cấp một người quyết định; tối đa 20 cấp như cấu hình cho phép, chưa giới hạn 3 cấp như giả định của PO;
- người duyệt theo vai trò như bảng trên; `HR_MANAGER` và `ADMIN` không duyệt thay;
- người tạo và người gửi duyệt không tự duyệt, mỗi người chỉ duyệt một cấp trong một lượt;
- người sửa nội dung (`PUT`) khi yêu cầu là nháp hoặc cần bổ sung, kể cả người duyệt vừa yêu cầu bổ sung, vẫn được duyệt lượt sau nếu không phải người gửi duyệt lượt đó, vì hệ thống chưa lưu người sửa gần nhất;
- ai sửa được yêu cầu thì gửi duyệt được (người có quyền ghi theo phạm vi phòng ban), không chỉ người tạo;
- khi gửi duyệt bắt buộc có cả hai mức lương, ngày cần người, mô tả công việc và yêu cầu ứng viên;
- không kiểm định biên lại khi gửi duyệt hay khi duyệt, vì yêu cầu đã được tính từ lúc lưu;
- recruiter vẫn được phân công khi yêu cầu chờ duyệt, cần bổ sung, đã duyệt hoặc tạm dừng; không phân công khi đã bị từ chối, đã tuyển đủ hoặc đã hủy;
- gửi duyệt và duyệt không đổi `updatedAt`; bản sao không mang trạng thái hay lịch sử duyệt;
- người tham gia duyệt xem được yêu cầu, phân công và lịch sử phân công của nó; danh sách và ngoại lệ định biên vẫn theo phòng ban;
- từ task 285, recruiter đang được phân công cũng xem được yêu cầu, phân công, lịch sử phân công và tiến độ duyệt, và yêu cầu có trong danh sách của họ; quyết định duyệt không tính phân công (câu hỏi 19 trong ma trận vai trò và quyền);
- `HR_MANAGER` và `ADMIN` không duyệt thay; một cấp mà người duyệt bị khóa hoặc mất quyền sau khi gửi thì chờ tới khi có người đủ điều kiện.
