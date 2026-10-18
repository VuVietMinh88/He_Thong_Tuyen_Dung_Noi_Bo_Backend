# Tạm dừng, đóng và hủy yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-293 (lịch sử trạng thái và lý do), story TKNHTTDNB1-36 (S3-07). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`. Task 293 thêm ba trạng thái, xếp chúng vào các nhóm trạng thái và lưu mỗi lần đổi trạng thái vào lịch sử; API đổi trạng thái là task 290, nên hiện chỉ **đọc** được lịch sử, qua [tiến độ duyệt](requisition-approvals.md).

Tiêu chí của story S3-07:

- "Ba trạng thái kết thúc có lý do riêng: Đã tuyển đủ, Tạm dừng, Huỷ": một phần. Task 293 thêm ba trạng thái và lưu người thao tác, thời điểm, trạng thái trước/sau và lý do; API đổi trạng thái thuộc task 290.
- "Đóng yêu cầu tự động gỡ tin tuyển dụng đang đăng": chưa có (task 292).
- "Ứng viên còn đang trong pipeline được cảnh báo cần xử lý trước khi đóng": thuộc task 291, chưa làm vì chưa có module ứng viên; backend không trả số ứng viên giả.

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

Các thay đổi Trưởng phòng Nhân sự được làm, ngoài luồng duyệt (`RequisitionStatusChange`; API ở task 290):

| Thay đổi | Từ | Sang | Dòng lịch sử (`action`) | Lý do |
|---|---|---|---|---|
|Tạm dừng|`APPROVED`|`PAUSED`|`PAUSED`|Bắt buộc|
|Mở lại|`PAUSED`|`APPROVED`|`RESUMED`|Không bắt buộc; có thì lưu|
|Đã tuyển đủ|`APPROVED`, `PAUSED`|`FILLED`|`FILLED`|Bắt buộc|
|Hủy|`DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`|`CANCELLED`|`CANCELLED`|Bắt buộc|

Mọi cặp khác không được phép: bấm lặp lại, đổi trạng thái của yêu cầu `REJECTED`, `FILLED` hay `CANCELLED`, tạm dừng một bản nháp, v.v. Database cũng chặn chúng (CHECK `valid_requisition_workflow_event_transition` của V24).

- Hủy được cả trước khi duyệt, vì bản nháp đã giữ chỗ trong định biên và không có API xóa yêu cầu.
- Mở lại không cần duyệt lại: yêu cầu tạm dừng không sửa được, nên nội dung vẫn là nội dung đã duyệt.
- Có thao tác mở lại để yêu cầu `PAUSED` không thành ngõ cụt cứ giữ chỗ trong định biên mãi.

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

## Lịch sử trạng thái và lý do

Mỗi lần đổi trạng thái được ghi thêm một dòng vào lịch sử luồng duyệt (`requisition_workflow_events`, V19), cùng transaction với trạng thái mới, khi đang giữ khóa dòng yêu cầu (`RequisitionApprovalRepository.appendStatusChange`). Dòng đó ghi:

- người thao tác (`actor_id`) và thời điểm (`occurred_at`, đến micro giây);
- loại thay đổi (`PAUSED`, `RESUMED`, `FILLED`, `CANCELLED`) cùng trạng thái trước và sau;
- lý do, ở cột `comment`, giữ nguyên xuống dòng; tối đa 2.000 ký tự, không toàn khoảng trắng; bắt buộc khi tạm dừng, đã tuyển đủ và hủy (database cũng kiểm);
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

## Database và phạm vi

V24 (task 293) không đổi dòng nào, không thêm quyền, không thêm route:

- CHECK `valid_requisition_status` của `recruitment_requisitions` có tám trạng thái;
- `requisition_workflow_events`: CHECK `valid_requisition_workflow_event_type` thêm `PAUSED`, `RESUMED`, `FILLED`, `CANCELLED`; `valid_requisition_workflow_event_transition` thêm bốn bước của bảng "Chuyển trạng thái"; `requisition_workflow_event_comment_required` bắt lý do ở `PAUSED`, `FILLED`, `CANCELLED`;
- chỉ mục `requisition_workflow_events_one_end_idx`.

Các CHECK `_round`, `_level`, `_snapshot` của V19 không phải sửa: chúng có dạng "loại của luồng duyệt = cột có giá trị", nên đã buộc dòng đổi trạng thái để trống lượt, cấp và bản chụp. `_comment` và hai khóa ngoại của V19 cũng giữ nguyên. Ràng buộc mới là DDL, không chạy trigger của V20. Test của migration: `RequisitionStatusChangeMigrationTest`; với hai CHECK bị xóa rồi tạo lại (`_transition`, `comment_required`), test kiểm ở V24 cả điều kiện cũ của V19 (bước gửi duyệt và quyết định sai trạng thái trước/sau, từ chối hay yêu cầu bổ sung không có nhận xét đều bị từ chối).

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, chờ BA/PO xác nhận. Gồm:

- mã `FILLED` cho "Đã tuyển đủ" (frontend đang gửi `CLOSED`), `PAUSED` cho "Tạm dừng", `CANCELLED` cho "Đã hủy";
- tạm dừng chỉ từ đã duyệt; mở lại từ tạm dừng về đã duyệt, không duyệt lại (nội dung không sửa được khi tạm dừng); đã tuyển đủ từ đã duyệt hoặc tạm dừng; hủy từ nháp, chờ duyệt, cần bổ sung, đã duyệt, tạm dừng; đã tuyển đủ và đã hủy là kết thúc, muốn tuyển lại thì sao chép;
- lý do là văn bản tự do, bắt buộc khi tạm dừng, đóng vì đã tuyển đủ và hủy (tối đa 2.000 ký tự), không bắt buộc khi mở lại; không có danh mục mã lý do. Nếu cần danh mục lý do thì thêm một cột mới mà không đổi dòng cũ;
- định biên: tạm dừng và đã tuyển đủ vẫn giữ chỗ; hủy trả lại chỗ;
- vẫn đổi được recruiter khi tạm dừng; không đổi khi đã tuyển đủ hoặc đã hủy;
- tạm dừng ẩn tin tuyển dụng khỏi cổng và không cho sửa, duyệt hay xuất bản tin;
- hủy khi đang chờ duyệt kết thúc lượt duyệt; người duyệt của cấp đang chờ vẫn xem được yêu cầu và tiến độ, bấm quyết định trên trang cũ thì nhận 409 để tải lại; người của cấp chưa tới không xem được; cấp đang chờ hiện "Chưa đến lượt", chưa có trạng thái riêng cho cấp bị dừng;
- cảnh báo ứng viên còn trong quy trình (task 291) chưa làm vì chưa có module ứng viên.
