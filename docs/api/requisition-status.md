# Tạm dừng, đóng và hủy yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-293 (lịch sử trạng thái và lý do) và TKNHTTDNB1-290 (kiểm quyền, trạng thái và lý do), story TKNHTTDNB1-36 (S3-07). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`. Task 293 thêm ba trạng thái, xếp chúng vào các nhóm trạng thái và lưu mỗi lần đổi trạng thái vào lịch sử. Task 290 thêm API đổi trạng thái `POST /requisitions/{id}/status-changes` (mục "Đổi trạng thái"); lịch sử đọc qua [tiến độ duyệt](requisition-approvals.md).

Tiêu chí của story S3-07:

- "Ba trạng thái kết thúc có lý do riêng: Đã tuyển đủ, Tạm dừng, Huỷ": đã có (290, 293). Task 293 thêm ba trạng thái và lưu người thao tác, thời điểm, trạng thái trước/sau và lý do; task 290 thêm API, chỉ cho Trưởng phòng Nhân sự, bắt lý do khi tạm dừng, đóng hay hủy.
- "Đóng yêu cầu tự động gỡ tin tuyển dụng đang đăng": chưa có (task 292).
- "Ứng viên còn đang trong pipeline được cảnh báo cần xử lý trước khi đóng": thuộc task 291, chưa làm vì chưa có module ứng viên; backend không trả số ứng viên giả.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions/{id}/status-changes`|`REQUISITIONS_WRITE_ALL` ở matcher và kiểm lại sau mỗi khóa, **và** xem được yêu cầu đó theo phạm vi phòng ban (như `GET /requisitions/{id}`, không gồm người tham gia duyệt)|HR_MANAGER, ADMIN|
|Đọc lịch sử (`GET /requisitions/{id}/approvals`)|Như `GET /requisitions/{id}` (xem [tiến độ duyệt](requisition-approvals.md))|Như xem yêu cầu|

- Tạm dừng, mở lại, đóng vì đã tuyển đủ và hủy là quyết định của Trưởng phòng Nhân sự, giống phân công recruiter (mục 7.13 của [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)). Trưởng bộ phận có `REQUISITIONS_WRITE_SCOPED` nhưng bị 403, kể cả với bản nháp của chính mình; recruiter được phân công và người duyệt cũng vậy. Không thêm mã quyền mới.
- Service kiểm lại đúng mã `REQUISITIONS_WRITE_ALL` sau mỗi lần chờ khóa, không chỉ "có quyền ghi": nếu quyền của HR bị thu hẹp thành `REQUISITIONS_WRITE_SCOPED` trong lúc lệnh đang chờ, lệnh vẫn bị 403.
- Quyền ghi không bao gồm quyền đọc. Response trả cả yêu cầu, nên người gọi còn phải có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`; thiếu cả hai thì 403 ngay, kể cả với `{id}` không tồn tại (403 trước 404). Người chỉ có `REQUISITIONS_READ_SCOPED` phải phụ trách phòng ban của yêu cầu. Theo seed hiện tại HR_MANAGER và ADMIN có `REQUISITIONS_READ_ALL`, nên hai điều này chỉ có tác dụng khi ma trận quyền đổi.

## Trạng thái

| Mã | Tên | Ý nghĩa | Từ |
|---|---|---|---|
|`DRAFT`|Nháp|Đã lưu, chưa gửi duyệt (hoặc mới sao chép)|V13|
|`PENDING_APPROVAL`|Chờ duyệt|Đang chờ cấp hiện tại|V19|
|`RETURNED`|Cần bổ sung|Người duyệt yêu cầu bổ sung thông tin|V19|
|`APPROVED`|Đã duyệt|Đang tuyển; [tin tuyển dụng](job-postings.md) được tạo từ yêu cầu ở trạng thái này|V19|
|`REJECTED`|Bị từ chối|Kết thúc|V19|
|`PAUSED`|Tạm dừng|Trưởng phòng Nhân sự tạm ngừng tuyển. Vẫn giữ chỗ trong định biên và nhóm recruiter; tin tuyển dụng bị ẩn khỏi cổng và không sửa, duyệt hay xuất bản được; mở lại thì về `APPROVED`|V24 (task 293)|
|`FILLED`|Đã tuyển đủ|Kết thúc; người đã tuyển vẫn tính vào định biên|V24 (task 293)|
|`CANCELLED`|Đã hủy|Kết thúc; trả lại chỗ trong định biên|V24 (task 293)|

`FILLED` là "Đã tuyển đủ" mà frontend đang gửi là `CLOSED`. Muốn tuyển lại một yêu cầu đã kết thúc (`REJECTED`, `FILLED`, `CANCELLED`) thì sao chép nó (`POST /requisitions/{id}/copy`, task 278): bản sao là nháp mới, không mang trạng thái hay lịch sử của bản gốc, và giữ chỗ trong định biên như mọi bản nháp.

## Chuyển trạng thái

Các thay đổi Trưởng phòng Nhân sự được làm, ngoài luồng duyệt (`RequisitionStatusChange`; API ở task 290, mục "Đổi trạng thái"):

| Thay đổi | `status` gửi lên | Từ | Sang | Dòng lịch sử (`action`) | Lý do |
|---|---|---|---|---|---|
|Tạm dừng|`PAUSED`|`APPROVED`|`PAUSED`|`PAUSED`|Bắt buộc|
|Mở lại|`APPROVED`|`PAUSED`|`APPROVED`|`RESUMED`|Không bắt buộc; có thì lưu|
|Đã tuyển đủ|`FILLED`|`APPROVED`, `PAUSED`|`FILLED`|`FILLED`|Bắt buộc|
|Hủy|`CANCELLED`|`DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`|`CANCELLED`|`CANCELLED`|Bắt buộc|

Mọi cặp khác không được phép (409 `REQUISITION_STATUS_CHANGE_NOT_ALLOWED`): bấm lặp lại, đổi trạng thái của yêu cầu `REJECTED`, `FILLED` hay `CANCELLED`, tạm dừng một bản nháp, v.v. Database cũng chặn chúng (CHECK `valid_requisition_workflow_event_transition` của V24).

- Hủy được cả trước khi duyệt, vì bản nháp đã giữ chỗ trong định biên và không có API xóa yêu cầu.
- Mở lại không cần duyệt lại: yêu cầu tạm dừng không sửa được, nên nội dung vẫn là nội dung đã duyệt.
- Có thao tác mở lại để yêu cầu `PAUSED` không thành ngõ cụt cứ giữ chỗ trong định biên mãi.

## Đổi trạng thái

`POST /requisitions/{id}/status-changes` (task 290). Dùng `POST` như các thao tác khác của yêu cầu (CORS không cho `PATCH`); mỗi lần gọi thành công thêm đúng một dòng lịch sử.

```json
POST /api/v1/requisitions/5d0e6a1c-2f3b-4c5d-8e9f-0a1b2c3d4e5f/status-changes
{ "status": "FILLED", "reason": "Đã tuyển đủ 2 người qua đợt tháng 10.", "expectedRevision": 2 }
```

Thêm ví dụ:

- Tạm dừng: `{"status": "PAUSED", "reason": "Chờ ngân sách quý 4", "expectedRevision": 2}`
- Mở lại: `{"status": "APPROVED", "expectedRevision": 3}` (lý do không bắt buộc)
- Hủy một bản nháp chưa gửi duyệt: `{"status": "CANCELLED", "reason": "Trùng yêu cầu khác", "expectedRevision": 0}`

| Trường | Quy tắc |
|---|---|
|status|Bắt buộc. Trạng thái mới, đúng chữ hoa: `PAUSED` (tạm dừng), `APPROVED` (mở lại sau khi tạm dừng), `FILLED` (đã tuyển đủ) hoặc `CANCELLED` (hủy). Giá trị khác, kể cả `CLOSED` của frontend, trả 400 `VALIDATION_ERROR`|
|reason|Lý do, giữ đúng tên trường frontend đang gửi. **Bắt buộc** khi tạm dừng, đã tuyển đủ và hủy; không bắt buộc khi mở lại. Tối đa 2.000 ký tự, không chứa ký tự NUL; để trống hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B) coi như không có, như nhận xét của luồng duyệt; lý do có ký tự nhìn thấy được lưu đúng như gửi, giữ nguyên xuống dòng. Lưu vào `comment` của dòng lịch sử|
|expectedRevision|Bắt buộc, số nguyên ≥ 0: `revision` của `GET /requisitions/{id}/approvals` mà người gọi đang xem (0 khi yêu cầu chưa từng gửi duyệt). Lệch với lịch sử hiện tại thì 409 `REQUISITION_STATE_CHANGED`|

- Body chỉ có ba trường trên. Gửi thêm trường khác, ví dụ `comment`, `reasonCode`, `actorId`, `occurredAt`, `statusBefore`, trả 400 `INVALID_JSON`: người thao tác lấy từ token, thời điểm lấy từ đồng hồ server, trạng thái trước lấy từ database.
- Thiếu lý do khi cần trả 400 `REQUISITION_STATUS_REASON_REQUIRED`, `fieldErrors.reason` có cùng câu thông báo. Lỗi này được kiểm **trước** mọi khóa và mọi tra cứu, nên `{id}` không tồn tại vẫn nhận 400 chứ không phải 404, và lệnh không phải chờ khi yêu cầu đang bị người khác giữ.
- Thành công trả 200 với yêu cầu (15 trường như `GET /requisitions/{id}`), `status` mới; `updatedAt` không đổi vì đó là thời điểm sửa nội dung gần nhất.

### Thứ tự kiểm

Lỗi đầu tiên gặp phải được trả về:

1. Quyền ở `SecurityConfiguration`: chưa đăng nhập 401 (`UNAUTHORIZED`), thiếu `REQUISITIONS_WRITE_ALL` 403 (`FORBIDDEN`). `{id}` không phải UUID trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ.".
2. Body: 400 `INVALID_JSON` (JSON sai, trường không được phép), rồi 400 `VALIDATION_ERROR` với `fieldErrors`.
3. Lý do khi thay đổi cần lý do (400 `REQUISITION_STATUS_REASON_REQUIRED`), trước mọi khóa.
4. Khóa tài khoản người gọi rồi phiên; kiểm lại hạn token, phiên và mã `REQUISITIONS_WRITE_ALL` (401 `SESSION_INVALID` / 403).
5. Khóa dòng yêu cầu, rồi kiểm lại người gọi lần nữa (401 / 403).
6. Người gọi phải có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED` (403, trước khi biết yêu cầu có tồn tại hay không).
7. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
8. Người chỉ có `REQUISITIONS_READ_SCOPED` phụ trách phòng ban của yêu cầu (403); người tham gia duyệt không được tính.
9. Thay đổi được phép từ trạng thái hiện tại (409 `REQUISITION_STATUS_CHANGE_NOT_ALLOWED`).
10. `expectedRevision` bằng số của sự kiện mới nhất trong lịch sử (409 `REQUISITION_STATE_CHANGED`).
11. Chỗ nối `beforeChange` (task 291 sau này): lỗi của nó được trả nguyên, không ghi gì.
12. Ghi dòng lịch sử, đổi trạng thái, rồi chỗ nối `afterChange` (task 292): lỗi của nó làm hủy toàn bộ thay đổi.

Lỗi trạng thái (9) đứng trước lỗi phiên bản (10), như khi quyết định duyệt: bấm hai lần trên cùng trang nhận "không thể chuyển" thay vì "dữ liệu đã cũ".

## Nhóm trạng thái

Mỗi trạng thái được xếp vào mọi nhóm sau. `RequisitionStatus` liệt kê các nhóm, và các test có `switch` đủ mọi trạng thái không biên dịch được cho tới khi một trạng thái mới được xếp nhóm.

| Mã | Sửa (`PUT`) / gửi duyệt | Phân công recruiter | Tính vào định biên | Chặn xóa phòng ban (còn mở) | Trễ hạn (298) | Tạo tin tuyển dụng (303) | Sao chép | Đổi trạng thái từ đây |
|---|---|---|---|---|---|---|---|---|
|`DRAFT`|Có|Có|Có|Có|Có|Không|Có|Hủy|
|`PENDING_APPROVAL`|Không|Có|Có|Có|Có|Không|Có|Hủy|
|`RETURNED`|Có|Có|Có|Có|Có|Không|Có|Hủy|
|`APPROVED`|Không|Có|Có|Có|Có|**Có**|Có|Tạm dừng, đã tuyển đủ, hủy|
|`REJECTED`|Không|Không|Không|Không (khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Không|Không|Có|–|
|`PAUSED`|Không|**Có**|**Có**|**Có**|**Không**|Không (tin bị ẩn, ghi trả 409)|Có|Mở lại, đã tuyển đủ, hủy|
|`FILLED`|Không|Không|**Có**|Không (khóa ngoại)|Không|Không|Có|–|
|`CANCELLED`|Không|Không|**Không**|Không (khóa ngoại)|Không|Không|Có|–|

Chủ của từng nhóm: `RequisitionService.EDITABLE_STATUSES`/`SUBMITTABLE_STATUSES`, `RequisitionRecruiterService.ASSIGNABLE_STATUSES`, `HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES`, `DepartmentRepository.OPEN_REQUISITION_STATUSES`, `RequisitionListItemView.OVERDUE_STATUSES`, `JobPostingService.SOURCE_STATUSES`.

- Yêu cầu tạm dừng vẫn cần người: giữ chỗ trong định biên, vẫn chặn xóa phòng ban, vẫn bàn giao recruiter được. Nó không bao giờ trễ hạn trong danh sách, vì đã được dừng có chủ ý. Tin tuyển dụng của nó không hiện trên cổng (cổng chỉ hiện tin của yêu cầu `APPROVED`), và mọi thao tác ghi tin bị từ chối: tạo tin nhận 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`; sửa, gửi duyệt, duyệt và xuất bản kiểm trạng thái của chính tin trước yêu cầu nguồn, nên nhận mã đó khi trạng thái của tin cho phép bước này, còn không thì nhận 409 về trạng thái của tin (ví dụ sửa tin đã xuất bản: `JOB_POSTING_NOT_EDITABLE`; quyết định trên tin không chờ duyệt: `JOB_POSTING_NOT_UNDER_REVIEW`). Thứ tự kiểm đầy đủ ở [tin tuyển dụng](job-postings.md) và [duyệt, xuất bản tin](job-posting-publication.md).
- Yêu cầu đã tuyển đủ vẫn tính vào định biên (người đã được tuyển); yêu cầu đã hủy trả lại chỗ.

Ba quy tắc giữ các nhóm nhất quán, mỗi quy tắc có test riêng:

1. Không thay đổi nào đưa yêu cầu từ trạng thái không tính vào định biên (`REJECTED`, `CANCELLED`, đều là kết thúc) sang trạng thái có tính, nên đổi trạng thái không bao giờ phải khóa và kiểm định biên lại (`CountedRequisitionStatusChangeTest`).
2. Không thay đổi nào đưa yêu cầu đã đóng (`REJECTED`, `FILLED`, `CANCELLED`) về trạng thái còn mở, nên một lần xóa phòng ban đã thấy không còn yêu cầu mở vẫn đúng (`OpenRequisitionStatusChangeTest`).
3. `PAUSED` không sửa được, nên yêu cầu tạm dừng vẫn có nội dung đã duyệt và mở lại không cần duyệt lại (`RequisitionWorkflowStatusTest`).

Bảng chuyển trạng thái được ghim bởi `RequisitionStatusChangeTest` (một `switch` đủ mọi trạng thái).

## Ảnh hưởng tới phần khác

Task 290 không thêm code nào cho các ảnh hưởng dưới đây; chúng đến từ các nhóm trạng thái ở trên và được kiểm trong `RequisitionStatusChangeIntegrationTest.effectsOnTheRestOfTheSystem`:

- Định biên: hủy trả lại chỗ (`headcountUsed` của `GET /headcount-plans/remaining` giảm); tạm dừng và đã tuyển đủ không đổi.
- Phân công recruiter: yêu cầu tạm dừng vẫn phân công và bàn giao được; yêu cầu đã tuyển đủ hoặc đã hủy trả 409 `REQUISITION_NOT_ASSIGNABLE` ở `/assign` và `/unassign`.
- Sửa và gửi duyệt: yêu cầu tạm dừng, đã tuyển đủ, đã hủy trả 409 `REQUISITION_NOT_DRAFT` (`PUT`) và `REQUISITION_NOT_SUBMITTABLE` (gửi duyệt).
- Hủy khi đang chờ duyệt: yêu cầu rời hộp chờ duyệt; người duyệt bấm quyết định trên trang cũ nhận 409 `REQUISITION_NOT_PENDING_APPROVAL`, kể cả APPROVER của cấp đang chờ không phụ trách phòng ban nào (mục "Hủy khi đang chờ duyệt").
- Tin tuyển dụng khi yêu cầu tạm dừng: sửa, gửi duyệt, duyệt, yêu cầu chỉnh sửa và xuất bản trả 409 `JOB_POSTING_REQUISITION_NOT_APPROVED` khi trạng thái của tin cho phép bước đó; mọi cờ `actions` của tin là `false` (từ task 290, cả `canApprove`, `canRequestRevision`, `canPublish` cũng cần yêu cầu còn `APPROVED`); tin đã xuất bản biến khỏi cổng tuyển dụng (404) và hiện lại khi mở lại yêu cầu, nếu hạn nộp chưa qua.
- Danh sách: yêu cầu tạm dừng, đã tuyển đủ, đã hủy quá ngày cần người vẫn có `overdue` là `false`.
- Sao chép yêu cầu đã tuyển đủ hoặc đã hủy trả 201 với một bản nháp mới.

## Lịch sử trạng thái và lý do

Mỗi lần đổi trạng thái được ghi thêm một dòng vào lịch sử luồng duyệt (`requisition_workflow_events`, V19), cùng transaction với trạng thái mới, khi đang giữ khóa dòng yêu cầu (`RequisitionApprovalRepository.appendStatusChange`). Dòng đó ghi:

- người thao tác (`actor_id`) và thời điểm (`occurred_at`, đến micro giây);
- loại thay đổi (`PAUSED`, `RESUMED`, `FILLED`, `CANCELLED`) cùng trạng thái trước và sau;
- lý do, ở cột `comment`, giữ nguyên xuống dòng; tối đa 2.000 ký tự, có ít nhất một ký tự nhìn thấy được (API coi lý do không có ký tự nhìn thấy được là không có; database từ chối lý do toàn khoảng trắng); bắt buộc khi tạm dừng, đã tuyển đủ và hủy (database cũng kiểm);
- **không** có lượt duyệt, cấp hay bản chụp chuỗi duyệt.

Lịch sử được đọc ở `GET /requisitions/{id}/approvals` (task 267), mảng `history`, mới nhất trước, cùng quyền như `GET /requisitions/{id}`:

```json
{"id": "…", "revision": 3, "round": null, "level": null, "action": "PAUSED", "statusBefore": "APPROVED",
 "statusAfter": "PAUSED", "actorId": "…", "actorName": "Nguyễn Thị Hoa", "occurredAt": "2026-10-11T03:01:30.123456Z",
 "comment": "Chờ ngân sách\nquý 4", "flowVersion": null, "levelCount": null, "salaryBasis": null}
```

- `revision` dùng **một** bộ đếm với gửi duyệt và quyết định: mỗi lần đổi trạng thái cũng tăng 1. `revision` ở đầu tiến độ duyệt là số của sự kiện mới nhất, nên thao tác gửi số cũ (trang chưa tải lại) bị từ chối.
- Dòng lịch sử chỉ có tên người thao tác (`actorName`, đọc lúc trả), không có email.
- Tạm dừng, tuyển đủ hay hủy sau khi lượt duyệt đã hoàn tất không đổi tiến độ: mọi cấp vẫn `APPROVED`, `waitingOn` là `null`.

### Hủy khi đang chờ duyệt

Hủy một yêu cầu `PENDING_APPROVAL` kết thúc lượt duyệt đang chạy:

- cấp đang chờ hiện `NOT_REACHED` ("Chưa đến lượt") vì không ai quyết định cấp đó; `currentLevel` và `waitingOn` là `null`, `actions.canDecide` là `false`; yêu cầu rời hộp chờ duyệt;
- người được chuyển tới cấp đang chờ (ví dụ một APPROVER không phụ trách phòng ban nào) **vẫn xem được** yêu cầu và tiến độ, như người của mọi cấp đã tới: cấp đó tính là đã tới (`ApprovalRound.interrupted`). Nếu người đó bấm quyết định trên trang cũ thì nhận 409 `REQUISITION_NOT_PENDING_APPROVAL` để tải lại, không phải 403 (giao diện chuyển mọi 403 sang `/unauthorized`);
- người của cấp chưa tới vẫn không xem được (403).

## Lịch sử không sửa, không xóa

- Trigger `requisition_workflow_events_append_only` (V20) áp dụng cho cả dòng đổi trạng thái: mọi `UPDATE` và `DELETE` bị từ chối, kể cả bằng SQL viết tay. `TRUNCATE` (chỉ chủ bảng, test dùng để dọn dữ liệu) vẫn chạy, như ở V20.
- Mỗi yêu cầu có tối đa **một** dòng kết thúc (`FILLED` hoặc `CANCELLED`): chỉ mục unique có điều kiện `requisition_workflow_events_one_end_idx`, lớp chặn cuối nếu có code ghi mà bỏ qua khóa dòng yêu cầu. Tạm dừng và mở lại có thể lặp lại nhiều lần.
- Khóa ngoại `ON DELETE RESTRICT` giữ yêu cầu và tài khoản mà lịch sử tham chiếu, như V19.
- Không có route sửa hay xóa một lần đổi trạng thái (mặc định từ chối; `ApiAuthorizationMatrixIntegrationTest` kiểm cả `PATCH /job-requisitions/{id}/status` mà frontend đang gọi và `PATCH /requisitions/{id}/status`, cả hai đều trả 403).

## Khóa và đồng thời

- Thứ tự khóa như mọi thao tác ghi yêu cầu: tài khoản người gọi, phiên, dòng yêu cầu (`FOR NO KEY UPDATE`), rồi chỉ những gì chỗ nối khóa sau đó (task 292: tin tuyển dụng của yêu cầu). Mọi thao tác ghi tin cũng khóa dòng yêu cầu trước tin, nên không có deadlock.
- Không khóa phòng ban: không quy tắc nào ở đây phụ thuộc người phụ trách phòng ban, như phân công recruiter.
- Không khóa định biên: không thay đổi nào đưa yêu cầu từ trạng thái không tính sang trạng thái có tính (quy tắc 1 ở mục "Nhóm trạng thái").
- Sau khi khóa dòng yêu cầu không còn gì phải chờ: dòng lịch sử chỉ lấy `FOR KEY SHARE` trên chính yêu cầu và tài khoản người gọi mà lệnh đã giữ.
- Người gọi được kiểm lại sau mỗi lần chờ khóa: token hết hạn trong lúc chờ trả 401, bị gỡ hoặc bị thu hẹp quyền trả 403, bị khóa tài khoản hoặc đăng xuất trả 401 `SESSION_INVALID`.

Kết quả khi hai thao tác đến cùng lúc (thao tác đến trước chạy trước):

| Trước | Sau | Kết quả |
|---|---|---|
|Cấp cuối duyệt|HR hủy với `expectedRevision` cũ|200; 409 `REQUISITION_STATE_CHANGED`|
|HR hủy|Người duyệt của cấp đang chờ quyết định|200; 409 `REQUISITION_NOT_PENDING_APPROVAL` (không phải 403)|
|Gửi duyệt|HR hủy với `expectedRevision` 0|200; 409 `REQUISITION_STATE_CHANGED`|
|HR hủy|Gửi duyệt|200; 409 `REQUISITION_NOT_SUBMITTABLE`|
|Phân công recruiter|HR đóng vì đã tuyển đủ|200; 200|
|HR đóng vì đã tuyển đủ|Phân công recruiter|200; 409 `REQUISITION_NOT_ASSIGNABLE`|
|HR tạm dừng|Lưu tin tuyển dụng|200; 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`|
|HR hủy|HR khác hủy|200; 409 `REQUISITION_STATUS_CHANGE_NOT_ALLOWED`, chỉ một dòng `CANCELLED`|

## Chỗ nối cho task 291 và 292

`RequisitionStatusListener` là chỗ để module khác phản ứng khi Trưởng phòng Nhân sự đổi trạng thái. Hai phương thức chạy trong cùng transaction với thay đổi, khi đang giữ khóa dòng yêu cầu, và nhận `RequisitionStatusTransition` (yêu cầu, thay đổi, trạng thái trước và sau, người thao tác, thời điểm đúng như dòng lịch sử, lý do):

- `beforeChange` chạy sau mọi bước kiểm và trước khi ghi gì. Task 291 sẽ từ chối đóng yêu cầu ở đây khi còn ứng viên trong quy trình, trừ khi người gọi đã xác nhận (khi đó task 291 thêm trường riêng vào body).
- `afterChange` chạy khi dòng lịch sử và trạng thái mới đã được ghi. Task 292 đóng tin tuyển dụng của yêu cầu ở đây.
- Lỗi của một trong hai được trả nguyên cho người gọi và làm hủy toàn bộ thay đổi, kể cả dòng lịch sử.

Task 290 chưa có listener nào. Chưa có cảnh báo ứng viên vì chưa có module ứng viên, và backend không trả số ứng viên giả: frontend đừng gọi `active-candidates-count` (route này chưa tồn tại, gọi sẽ nhận 403).

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`{id}` không phải UUID ("Tham số đường dẫn hoặc bộ lọc không hợp lệ."); thiếu hoặc sai `status`, `reason` quá 2.000 ký tự hoặc chứa ký tự NUL, thiếu `expectedRevision` hoặc nhỏ hơn 0 (`fieldErrors`)|
|400|`INVALID_JSON`|JSON sai, thiếu body, trường không được phép (`comment`, `reasonCode`, `actorId`, `occurredAt`, `statusBefore`...)|
|400|`REQUISITION_STATUS_REASON_REQUIRED`|Tạm dừng, đã tuyển đủ hoặc hủy mà không có lý do (kể cả lý do không có ký tự nhìn thấy được, ví dụ chỉ gồm khoảng trắng, U+00A0 hoặc U+200B); `fieldErrors.reason`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn hoặc phiên bị thu hồi, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu `REQUISITIONS_WRITE_ALL` (kể cả bị gỡ hoặc thu hẹp trong lúc chờ); không có quyền xem yêu cầu nào; người `SCOPED` ngoài phạm vi phòng ban|
|404|`REQUISITION_NOT_FOUND`|Không có yêu cầu với `{id}`|
|409|`REQUISITION_STATUS_CHANGE_NOT_ALLOWED`|Thay đổi không được phép từ trạng thái hiện tại ("Không thể chuyển yêu cầu tuyển dụng từ trạng thái hiện tại sang trạng thái đã chọn. Vui lòng tải lại để xem trạng thái mới nhất.")|
|409|`REQUISITION_STATE_CHANGED`|`expectedRevision` không còn là phiên bản mới nhất ("Yêu cầu tuyển dụng vừa có thay đổi khác (có thể người khác vừa xử lý). Vui lòng tải lại trước khi thao tác.")|

Các 409 có sẵn mà nay gặp được với yêu cầu tạm dừng, đã tuyển đủ hoặc đã hủy: `REQUISITION_NOT_ASSIGNABLE`, `REQUISITION_NOT_DRAFT`, `REQUISITION_NOT_SUBMITTABLE`, `REQUISITION_NOT_PENDING_APPROVAL`, `JOB_POSTING_REQUISITION_NOT_APPROVED` (mục "Ảnh hưởng tới phần khác").

## Database và phạm vi

V24 (task 293) không đổi dòng nào, không thêm quyền, không thêm route:

- CHECK `valid_requisition_status` của `recruitment_requisitions` có tám trạng thái;
- `requisition_workflow_events`: CHECK `valid_requisition_workflow_event_type` thêm `PAUSED`, `RESUMED`, `FILLED`, `CANCELLED`; `valid_requisition_workflow_event_transition` thêm bốn bước của bảng "Chuyển trạng thái"; `requisition_workflow_event_comment_required` bắt lý do ở `PAUSED`, `FILLED`, `CANCELLED`;
- chỉ mục `requisition_workflow_events_one_end_idx`.

Các CHECK `_round`, `_level`, `_snapshot` của V19 không phải sửa: chúng có dạng "loại của luồng duyệt = cột có giá trị", nên đã buộc dòng đổi trạng thái để trống lượt, cấp và bản chụp. `_comment` và hai khóa ngoại của V19 cũng giữ nguyên. Ràng buộc mới là DDL, không chạy trigger của V20. Test của migration: `RequisitionStatusChangeMigrationTest`; với hai CHECK bị xóa rồi tạo lại (`_transition`, `comment_required`), test kiểm ở V24 cả điều kiện cũ của V19 (bước gửi duyệt và quyết định sai trạng thái trước/sau, từ chối hay yêu cầu bổ sung không có nhận xét đều bị từ chối).

Task 290 không thêm migration và không thêm mã quyền: route mới dùng `REQUISITIONS_WRITE_ALL` sẵn có.

## Ghi chú cho frontend

Màn hình đổi trạng thái ở nhánh `UI` (`jobReqStatusService.ts`, `JobReqStatusActionsUI.tsx`) chưa được gắn vào trang nào, nên đổi theo hợp đồng này được ngay:

| Frontend hiện tại | Backend | Cần sửa |
|---|---|---|
|`` axiosClient.patch(`/job-requisitions/${id}/status`, data) ``|`POST /requisitions/{id}/status-changes`. CORS không cho `PATCH`; `/job-requisitions` không có trong bảng quyền nên trả 403, và giao diện chuyển mọi 403 sang `/unauthorized`|`` axiosClient.post(`/requisitions/${id}/status-changes`, data) ``|
|Body `{status: 'PAUSED' \| 'CLOSED' \| 'CANCELLED', reason?}`|`{status: 'PAUSED' \| 'APPROVED' \| 'FILLED' \| 'CANCELLED', reason, expectedRevision}`. `reason` giữ tên. `CLOSED` là `FILLED` (chờ BA/PO chốt mã). `APPROVED` là mở lại yêu cầu tạm dừng|Đổi `CLOSED` thành `FILLED`; thêm `expectedRevision`|
|Không gửi phiên bản|`expectedRevision` là `.revision` của `GET /requisitions/{id}/approvals`; gặp 409 `REQUISITION_STATE_CHANGED` thì tải lại|Đọc `.revision` trước khi mở hộp thoại|
|Gọi `getActiveCandidatesCount` trước, lỗi thì không mở hộp thoại|Route này chưa có cho tới task 291 (gọi sẽ nhận 403 và bị chuyển sang `/unauthorized`). Backend không trả số ứng viên giả|**Bỏ lời gọi**; mở hộp thoại với số 0; giữ khối cảnh báo cho task 291|
|Lý do "(Tùy chọn)", chỉ gửi khi có nội dung|Bắt buộc khi tạm dừng, đã tuyển đủ, hủy (tối đa 2.000 ký tự); thiếu thì 400 `REQUISITION_STATUS_REASON_REQUIRED`, hiện bằng `apiError` sẵn có|Nhãn "bắt buộc"|
|Câu mô tả `CLOSED` "tuyển đủ người hoặc không còn nhu cầu… mở lại sẽ cần duyệt lại"|`FILLED` là đã tuyển đủ; "không còn nhu cầu" là `CANCELLED`; muốn tuyển lại thì sao chép (`POST /requisitions/{id}/copy`)|Sửa câu chữ|
|Luôn hiện ba nút, không có nút mở lại|Chỉ gắn khi `permissions.includes('REQUISITIONS_WRITE_ALL')`. `APPROVED`: Tạm dừng / Đã tuyển đủ / Hủy; `PAUSED`: Mở lại / Đã tuyển đủ / Hủy; `DRAFT`, `PENDING_APPROVAL`, `RETURNED`: Hủy; trạng thái khác: không có nút|Thêm props và điều kiện hiện|
|Dòng thời gian lịch sử|`.history[].action` thêm `PAUSED` "Tạm dừng", `RESUMED` "Mở lại", `FILLED` "Đã tuyển đủ", `CANCELLED` "Hủy"; `comment` là lý do; `round`, `level` là `null`|Thêm nhãn|
|Mọi 403 không phải lỗi đăng nhập đều chuyển sang `/unauthorized`|Người duyệt bấm quyết định trên trang cũ sau khi HR hủy yêu cầu nhận 409 `REQUISITION_NOT_PENDING_APPROVAL` (tải lại), không nhận 403, và vẫn mở được yêu cầu và tiến độ. Cấp đang chờ hiện `NOT_REACHED` ("Chưa đến lượt")|Không cần sửa; hiện thông báo của 409|

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, chờ BA/PO xác nhận. Gồm:

- mã `FILLED` cho "Đã tuyển đủ" (frontend đang gửi `CLOSED`), `PAUSED` cho "Tạm dừng", `CANCELLED` cho "Đã hủy";
- chỉ Trưởng phòng Nhân sự và ADMIN (`REQUISITIONS_WRITE_ALL`) đổi trạng thái; trưởng bộ phận không tự hủy nháp của mình;
- phải gửi `expectedRevision` (lấy từ tiến độ duyệt); thao tác trên trang cũ nhận 409 `REQUISITION_STATE_CHANGED` mới;
- tạm dừng chỉ từ đã duyệt; mở lại từ tạm dừng về đã duyệt, không duyệt lại (nội dung không sửa được khi tạm dừng); đã tuyển đủ từ đã duyệt hoặc tạm dừng; hủy từ nháp, chờ duyệt, cần bổ sung, đã duyệt, tạm dừng; đã tuyển đủ và đã hủy là kết thúc, muốn tuyển lại thì sao chép;
- lý do là văn bản tự do, bắt buộc khi tạm dừng, đóng vì đã tuyển đủ và hủy (tối đa 2.000 ký tự), không bắt buộc khi mở lại; không có danh mục mã lý do. Nếu cần danh mục lý do thì thêm một cột mới mà không đổi dòng cũ;
- định biên: tạm dừng và đã tuyển đủ vẫn giữ chỗ; hủy trả lại chỗ;
- vẫn đổi được recruiter khi tạm dừng; không đổi khi đã tuyển đủ hoặc đã hủy;
- tạm dừng ẩn tin tuyển dụng khỏi cổng và không cho sửa, duyệt hay xuất bản tin;
- hủy khi đang chờ duyệt kết thúc lượt duyệt; người duyệt của cấp đang chờ vẫn xem được yêu cầu và tiến độ, bấm quyết định trên trang cũ thì nhận 409 để tải lại; người của cấp chưa tới không xem được; cấp đang chờ hiện "Chưa đến lượt", chưa có trạng thái riêng cho cấp bị dừng;
- cảnh báo ứng viên còn trong quy trình (task 291) chưa làm vì chưa có module ứng viên.
