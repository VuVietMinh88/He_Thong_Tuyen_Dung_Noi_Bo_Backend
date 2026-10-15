# Tạm dừng, đóng và hủy yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-293 (lịch sử trạng thái và lý do), TKNHTTDNB1-290 (kiểm quyền, trạng thái và lý do), TKNHTTDNB1-292 (tự gỡ tin tuyển dụng khi đóng yêu cầu) và TKNHTTDNB1-291 (kiểm tra ứng viên đang xử lý trước khi đóng yêu cầu), story TKNHTTDNB1-36 (S3-07). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`. Task 293 thêm ba trạng thái, xếp chúng vào các nhóm trạng thái và lưu mỗi lần đổi trạng thái vào lịch sử. Task 290 thêm API đổi trạng thái `POST /requisitions/{id}/status-changes` (mục "Đổi trạng thái"); lịch sử đọc qua [tiến độ duyệt](requisition-approvals.md). Task 292 đóng tin tuyển dụng của yêu cầu đã tuyển đủ hoặc bị hủy (mục "Tự gỡ tin tuyển dụng khi đóng yêu cầu (task 292)"). Task 291 thêm API xem ứng viên còn trong quy trình và bước xác nhận khi đóng yêu cầu (mục "Ứng viên còn trong quy trình (task 291)").

Tiêu chí của story S3-07:

- "Ba trạng thái kết thúc có lý do riêng: Đã tuyển đủ, Tạm dừng, Huỷ": đã có (290, 293). Task 293 thêm ba trạng thái và lưu người thao tác, thời điểm, trạng thái trước/sau và lý do; task 290 thêm API, chỉ cho Trưởng phòng Nhân sự, bắt lý do khi tạm dừng, đóng hay hủy.
- "Đóng yêu cầu tự động gỡ tin tuyển dụng đang đăng": đã có (292). Đã tuyển đủ hoặc hủy chuyển mọi tin chưa đóng của yêu cầu sang `CLOSED` (Đã đóng) trong cùng transaction: tin rời cổng tuyển dụng ngay, không sửa, duyệt hay xuất bản được nữa, và lịch sử tin ghi người đóng, thời điểm và lý do. Tạm dừng chỉ ẩn tin (task 309), không đóng.
- "Ứng viên còn đang trong pipeline được cảnh báo cần xử lý trước khi đóng": đã có API và bước xác nhận (291, mục "Ứng viên còn trong quy trình (task 291)"); nguồn ứng viên chờ module ứng viên: hiện số luôn là 0 (nghĩa là backend chưa có dữ liệu ứng viên, không phải đã kiểm và không còn ai) và đóng yêu cầu không bị chặn; backend không trả số ứng viên giả. Tiêu chí này chỉ đạt đủ khi có module ứng viên.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions/{id}/status-changes`|`REQUISITIONS_WRITE_ALL` ở matcher và kiểm lại sau mỗi khóa, **và** xem được yêu cầu đó theo phạm vi phòng ban (như `GET /requisitions/{id}`, không gồm người tham gia duyệt và recruiter được phân công)|HR_MANAGER, ADMIN|
|Đọc lịch sử (`GET /requisitions/{id}/approvals`)|Như `GET /requisitions/{id}` (xem [tiến độ duyệt](requisition-approvals.md))|Như xem yêu cầu|
|`GET /requisitions/{id}/active-pipeline` (task 291)|`REQUISITIONS_WRITE_ALL` ở matcher và kiểm lại trong service, **và** quyền xem theo phạm vi phòng ban như đổi trạng thái|HR_MANAGER, ADMIN|

- Tạm dừng, mở lại, đóng vì đã tuyển đủ và hủy là quyết định của Trưởng phòng Nhân sự, giống phân công recruiter (mục 7.13 của [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)). Trưởng bộ phận có `REQUISITIONS_WRITE_SCOPED` nhưng bị 403, kể cả với bản nháp của chính mình; recruiter được phân công và người duyệt cũng vậy. Không thêm mã quyền mới.
- Service kiểm lại đúng mã `REQUISITIONS_WRITE_ALL` sau mỗi lần chờ khóa, không chỉ "có quyền ghi": nếu quyền của HR bị thu hẹp thành `REQUISITIONS_WRITE_SCOPED` trong lúc lệnh đang chờ, lệnh vẫn bị 403.
- Quyền ghi không bao gồm quyền đọc. Response trả cả yêu cầu, nên người gọi còn phải có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`; thiếu cả hai thì 403 ngay, kể cả với `{id}` không tồn tại (403 trước 404). Người chỉ có `REQUISITIONS_READ_SCOPED` phải phụ trách phòng ban của yêu cầu. Theo seed hiện tại HR_MANAGER và ADMIN có `REQUISITIONS_READ_ALL`, nên hai điều này chỉ có tác dụng khi ma trận quyền đổi.
- Ứng viên đang xử lý (task 291) chỉ dành cho hộp thoại đóng yêu cầu của người đóng: trưởng bộ phận và recruiter được phân công (task 285) xem được yêu cầu nhưng nhận 403 ở `GET /requisitions/{id}/active-pipeline`. API này dùng đúng các bước kiểm quyền của đổi trạng thái, không khóa gì.

## Trạng thái

| Mã | Tên | Ý nghĩa | Từ |
|---|---|---|---|
|`DRAFT`|Nháp|Đã lưu, chưa gửi duyệt (hoặc mới sao chép)|V13|
|`PENDING_APPROVAL`|Chờ duyệt|Đang chờ cấp hiện tại|V19|
|`RETURNED`|Cần bổ sung|Người duyệt yêu cầu bổ sung thông tin|V19|
|`APPROVED`|Đã duyệt|Đang tuyển; [tin tuyển dụng](job-postings.md) được tạo từ yêu cầu ở trạng thái này|V19|
|`REJECTED`|Bị từ chối|Kết thúc|V19|
|`PAUSED`|Tạm dừng|Trưởng phòng Nhân sự tạm ngừng tuyển. Vẫn giữ chỗ trong định biên và nhóm recruiter; tin tuyển dụng bị ẩn khỏi cổng và không sửa, duyệt hay xuất bản được; mở lại thì về `APPROVED`|V24 (task 293)|
|`FILLED`|Đã tuyển đủ|Kết thúc; người đã tuyển vẫn tính vào định biên; tin tuyển dụng của nó bị đóng (task 292)|V24 (task 293)|
|`CANCELLED`|Đã hủy|Kết thúc; trả lại chỗ trong định biên; tin tuyển dụng của nó bị đóng (task 292)|V24 (task 293)|

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
- Đóng khi còn ứng viên, sau khi đã xác nhận (task 291): `{"status": "FILLED", "reason": "Đã tuyển đủ 2 người.", "expectedRevision": 2, "confirmActiveCandidates": true}`

| Trường | Quy tắc |
|---|---|
|status|Bắt buộc. Trạng thái mới, đúng chữ hoa: `PAUSED` (tạm dừng), `APPROVED` (mở lại sau khi tạm dừng), `FILLED` (đã tuyển đủ) hoặc `CANCELLED` (hủy). Giá trị khác, kể cả `CLOSED` của frontend, trả 400 `VALIDATION_ERROR`|
|reason|Lý do, giữ đúng tên trường frontend đang gửi. **Bắt buộc** khi tạm dừng, đã tuyển đủ và hủy; không bắt buộc khi mở lại. Tối đa 2.000 ký tự, không chứa ký tự NUL; để trống hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B) coi như không có, như nhận xét của luồng duyệt; lý do có ký tự nhìn thấy được lưu đúng như gửi, giữ nguyên xuống dòng. Lưu vào `comment` của dòng lịch sử|
|expectedRevision|Bắt buộc, số nguyên ≥ 0: `revision` của `GET /requisitions/{id}/approvals` mà người gọi đang xem (0 khi yêu cầu chưa từng gửi duyệt). Lệch với lịch sử hiện tại thì 409 `REQUISITION_STATE_CHANGED`|
|confirmActiveCandidates|Không bắt buộc (task 291). `true` nghĩa là người gọi đã thấy ứng viên còn trong quy trình và vẫn đóng. Không gửi hoặc `null` coi như `false`. Bỏ qua khi thay đổi không cần xác nhận (tạm dừng, mở lại) hoặc khi không còn ai trong quy trình. Không được lưu. Giá trị không phải đúng/sai như `"yes"`, `[]`, `{}` trả 400 `INVALID_JSON`; Jackson tự đổi `"true"` và `1` thành `true` (như `showSalary` của tin tuyển dụng), nhưng frontend nên gửi đúng `true` hoặc `false`|

- Body chỉ có bốn trường trên. Gửi thêm trường khác, ví dụ `comment`, `reasonCode`, `actorId`, `occurredAt`, `statusBefore`, trả 400 `INVALID_JSON`: người thao tác lấy từ token, thời điểm lấy từ đồng hồ server, trạng thái trước lấy từ database.
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
8. Người chỉ có `REQUISITIONS_READ_SCOPED` phụ trách phòng ban của yêu cầu (403); người tham gia duyệt và recruiter được phân công (task 285) không được tính.
9. Thay đổi được phép từ trạng thái hiện tại (409 `REQUISITION_STATUS_CHANGE_NOT_ALLOWED`).
10. `expectedRevision` bằng số của sự kiện mới nhất trong lịch sử (409 `REQUISITION_STATE_CHANGED`).
11. Đã tuyển đủ hoặc hủy mà không có `confirmActiveCandidates: true`: hỏi nguồn ứng viên, còn ứng viên thì 409 `REQUISITION_HAS_ACTIVE_CANDIDATES` (task 291, mục "Ứng viên còn trong quy trình (task 291)").
12. Chỗ nối `beforeChange` cho module khác: lỗi của nó được trả nguyên, không ghi gì.
13. Ghi dòng lịch sử, đổi trạng thái, rồi chỗ nối `afterChange` (task 292): lỗi của nó làm hủy toàn bộ thay đổi.

Lỗi trạng thái (9) đứng trước lỗi phiên bản (10), như khi quyết định duyệt: bấm hai lần trên cùng trang nhận "không thể chuyển" thay vì "dữ liệu đã cũ". Bước 11 đứng sau lỗi trạng thái và phiên bản: trang cũ phải tải lại trước khi được hỏi xác nhận.

## Ứng viên còn trong quy trình (task 291)

Tiêu chí "Ứng viên còn đang trong pipeline được cảnh báo cần xử lý trước khi đóng" của story S3-07: trước khi đóng yêu cầu, Trưởng phòng Nhân sự thấy ứng viên còn đang được xử lý và phải xác nhận nếu vẫn đóng. Task 291 thêm API đọc và bước xác nhận; nguồn ứng viên chờ module ứng viên (mục "Nguồn hiện tại").

### Xem ứng viên đang xử lý

```
GET /api/v1/requisitions/5d0e6a1c-2f3b-4c5d-8e9f-0a1b2c3d4e5f/active-pipeline
Authorization: Bearer <accessToken>
```

200, `Cache-Control: no-store`. Hiện mọi yêu cầu đều trả:

```json
{ "count": 0, "stages": [] }
```

Khi có module ứng viên, ví dụ:

```json
{ "count": 3,
  "stages": [ { "stage": "SCREENING", "name": "Sàng lọc", "count": 1 },
              { "stage": "INTERVIEW", "name": "Phỏng vấn", "count": 2 } ] }
```

- `count`: số ứng viên còn trong quy trình, giữ tên trường frontend đang đọc.
- `stages`: số theo từng bước, theo thứ tự quy trình, khi nguồn có số chi tiết; mảng rỗng khi nguồn chỉ có tổng. Khi không rỗng, tổng các bước đúng bằng `count`, và bước không có ai không được liệt kê. `stage` và `name` là mã và tên do module ứng viên đặt; module yêu cầu tuyển dụng không có danh sách bước riêng. Frontend không được giả định `stages` có phần tử khi `count > 0`.
- Trả lời ở mọi trạng thái, kể cả đã tuyển đủ và đã hủy (ứng viên còn lại sau một lần đóng có xác nhận).
- Thứ tự kiểm: matcher (chưa đăng nhập 401; thiếu `REQUISITIONS_WRITE_ALL` 403); `{id}` không phải UUID 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ."; token, phiên và tài khoản (401); mã `REQUISITIONS_WRITE_ALL` đọc lại (403); có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED` (403, trước khi biết yêu cầu có tồn tại hay không); yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`); người chỉ có `REQUISITIONS_READ_SCOPED` phụ trách phòng ban của yêu cầu (403; người tham gia duyệt và recruiter được phân công không được tính); rồi hỏi nguồn ứng viên. Một snapshot `REPEATABLE_READ` chỉ đọc, không khóa gì.

### Nguồn hiện tại: chưa có module ứng viên

Backend chưa có module ứng viên (V1–V25 không có bảng ứng viên hay hồ sơ ứng tuyển), nên nguồn mặc định `NoCandidateModulePipelineSource` trả `{count: 0, stages: []}` cho mọi yêu cầu. Số 0 này nghĩa là **backend chưa có dữ liệu ứng viên**, không phải đã kiểm và không còn ai; vì vậy hiện đóng yêu cầu không bao giờ bị chặn. Backend không trả số ứng viên giả. Response không có cờ riêng cho "chưa có dữ liệu" (hình dạng đã thống nhất); tài liệu này và ghi chú cho frontend nói rõ điều đó. Tiêu chí của story chỉ đạt đủ khi có module ứng viên.

### Xác nhận khi đóng

| Thay đổi | `status` | Cần xác nhận khi còn ứng viên | Vì sao |
|---|---|---|---|
|Tạm dừng|`PAUSED`|Không|Mở lại được mà không duyệt lại, giữ định biên và nhóm recruiter, chỉ ẩn tin; tiêu chí nói "trước khi đóng". Frontend vẫn có thể hiện cảnh báo thông tin từ API đọc (câu hỏi 20.2 của ma trận)|
|Mở lại|`APPROVED`|Không|Mở lại yêu cầu|
|Đã tuyển đủ|`FILLED`|**Có**, từ đã duyệt hoặc tạm dừng|Kết thúc; đóng tin tuyển dụng (task 292)|
|Hủy|`CANCELLED`|**Có**, từ mọi trạng thái được hủy|Kết thúc. Quy tắc theo trạng thái đích, nên cũng chạy khi hủy từ nháp, chờ duyệt hay cần bổ sung: module ứng viên có thể nhận hồ sơ qua kênh khác ngoài tin tuyển dụng, nên quy tắc không phụ thuộc điều đó|

Chủ của bảng: `RequisitionStatusChange.checksActivePipeline` (ghim bởi `RequisitionStatusChangeTest` và `JobPostingClosingStatusTest`: đúng những thay đổi đóng tin tuyển dụng).

Đã tuyển đủ hoặc hủy mà không có `confirmActiveCandidates: true` (không gửi, `false` hoặc `null`) khi nguồn báo còn ứng viên thì nhận:

```
409  Cache-Control: no-store
{ "code": "REQUISITION_HAS_ACTIVE_CANDIDATES",
  "message": "Yêu cầu tuyển dụng còn 3 ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý các ứng viên này trước, hoặc xác nhận vẫn đóng yêu cầu vì đã tuyển đủ.",
  "fieldErrors": {},
  "activePipeline": { "count": 3, "stages": [ … ] } }
```

- Khi hủy, câu thông báo kết thúc bằng "… hoặc xác nhận vẫn hủy yêu cầu.".
- Khi chỉ còn 1 ứng viên, câu thông báo nói "Vui lòng xử lý ứng viên này trước" (không có "các", vốn chỉ số nhiều).
- `activePipeline` giống hệt response của API đọc, nên hộp thoại hiện được ngay mà không phải gọi lại. Đây là lỗi duy nhất có khóa thứ tư; mọi lỗi khác vẫn chỉ có `code`, `message`, `fieldErrors`.
- Lần từ chối **không ghi gì**: không thêm dòng lịch sử, không đổi trạng thái, không tăng `revision`, không đóng tin. Người gọi gửi lại đúng body đó với `confirmActiveCandidates: true` và **cùng** `expectedRevision`.
- Có `confirmActiveCandidates: true` thì không hỏi nguồn và đóng như bình thường (vẫn cần lý do, vẫn đóng tin tuyển dụng). Xác nhận không được lưu (không thêm cột, không migration).
- Giới hạn đã biết: xác nhận là cờ đúng/sai, không gắn với số ứng viên người gọi đã thấy; ứng viên vào quy trình sau khi hộp thoại mở cũng bị đóng theo (câu hỏi 20.7). Nếu cần xác nhận theo đúng số đã thấy thì thêm một trường mới, không phá hợp đồng này.

### Dành cho module ứng viên

Module ứng viên thay `NoCandidateModulePipelineSource` bằng `RequisitionPipelineSource` của mình (xóa lớp mặc định: hai bean cùng loại làm ứng dụng không khởi động được) và tuân theo:

- "Đang trong quy trình" là hồ sơ ứng tuyển vào yêu cầu chưa ở trạng thái cuối; module tự định nghĩa các bước.
- Chạy trong transaction của người gọi: khi đổi trạng thái là dưới khóa dòng yêu cầu (`FOR NO KEY UPDATE`, READ COMMITTED), khi đọc là snapshot `REPEATABLE_READ` chỉ đọc. Nguồn chỉ đọc và không khóa gì, nên sau khóa dòng yêu cầu không có gì phải chờ.
- Trả `ActivePipeline.NONE` cho yêu cầu không biết và không ném lỗi cho yêu cầu đó.
- Mọi thao tác ghi đưa một hồ sơ vào quy trình phải khóa dòng yêu cầu `FOR SHARE` hoặc `FOR NO KEY UPDATE` trước (`FOR KEY SHARE` không xung đột với đổi trạng thái), theo thứ tự tài khoản, phiên, yêu cầu rồi tới dòng của mình, và từ chối yêu cầu đã tuyển đủ hoặc đã hủy, như các thao tác ghi tin tuyển dụng. Nếu không, hồ sơ thêm vào sau khi số đã được đọc có thể lọt qua bước kiểm.

Bước kiểm hỏi nguồn **sau** khóa dòng yêu cầu ở READ COMMITTED, nên với một nguồn thật tuân theo hợp đồng trên, hồ sơ được commit trong lúc HR chờ khóa cũng được đếm. Test hiện tại (`RequisitionActivePipelineIntegrationTest`, với nguồn giả trong bộ nhớ) chỉ ghim rằng nguồn được hỏi sau khi có khóa, dưới khóa, sau mọi bước kiểm khác và trước khi ghi gì; việc đếm đúng ở mức database do test của module ứng viên kiểm.

## Nhóm trạng thái

Mỗi trạng thái được xếp vào mọi nhóm sau. `RequisitionStatus` liệt kê các nhóm, và các test có `switch` đủ mọi trạng thái không biên dịch được cho tới khi một trạng thái mới được xếp nhóm.

| Mã | Sửa (`PUT`) / gửi duyệt | Phân công recruiter | Tính vào định biên | Chặn xóa phòng ban (còn mở) | Trễ hạn (298) | Tạo tin tuyển dụng (303) | Đóng tin tuyển dụng (292) | Sao chép | Đổi trạng thái từ đây |
|---|---|---|---|---|---|---|---|---|---|
|`DRAFT`|Có|Có|Có|Có|Có|Không|Không|Có|Hủy|
|`PENDING_APPROVAL`|Không|Có|Có|Có|Có|Không|Không|Có|Hủy|
|`RETURNED`|Có|Có|Có|Có|Có|Không|Không|Có|Hủy|
|`APPROVED`|Không|Có|Có|Có|Có|**Có**|Không|Có|Tạm dừng, đã tuyển đủ, hủy|
|`REJECTED`|Không|Không|Không|Không (khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Không|Không|Không|Có|–|
|`PAUSED`|Không|**Có**|**Có**|**Có**|**Không**|Không (tin bị ẩn, ghi trả 409)|Không|Có|Mở lại, đã tuyển đủ, hủy|
|`FILLED`|Không|Không|**Có**|Không (khóa ngoại)|Không|Không|**Có**|Có|–|
|`CANCELLED`|Không|Không|**Không**|Không (khóa ngoại)|Không|Không|**Có**|Có|–|

Chủ của từng nhóm: `RequisitionService.EDITABLE_STATUSES`/`SUBMITTABLE_STATUSES`, `RequisitionRecruiterService.ASSIGNABLE_STATUSES`, `HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES`, `DepartmentRepository.OPEN_REQUISITION_STATUSES`, `RequisitionListItemView.OVERDUE_STATUSES`, `JobPostingService.SOURCE_STATUSES`, `JobPostingRequisitionSync.CLOSING_STATUSES` (task 292).

- Yêu cầu tạm dừng vẫn cần người: giữ chỗ trong định biên, vẫn chặn xóa phòng ban, vẫn bàn giao recruiter được. Nó không bao giờ trễ hạn trong danh sách, vì đã được dừng có chủ ý. Tin tuyển dụng của nó không hiện trên cổng (cổng chỉ hiện tin của yêu cầu `APPROVED`), và mọi thao tác ghi tin bị từ chối: tạo tin nhận 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`; sửa, gửi duyệt, duyệt và xuất bản kiểm trạng thái của chính tin trước yêu cầu nguồn, nên nhận mã đó khi trạng thái của tin cho phép bước này, còn không thì nhận 409 về trạng thái của tin (ví dụ sửa tin đã xuất bản: `JOB_POSTING_NOT_EDITABLE`; quyết định trên tin không chờ duyệt: `JOB_POSTING_NOT_UNDER_REVIEW`). Thứ tự kiểm đầy đủ ở [tin tuyển dụng](job-postings.md) và [duyệt, xuất bản tin](job-posting-publication.md).
- Yêu cầu đã tuyển đủ vẫn tính vào định biên (người đã được tuyển); yêu cầu đã hủy trả lại chỗ.

Bốn quy tắc giữ các nhóm nhất quán, mỗi quy tắc có test riêng:

1. Không thay đổi nào đưa yêu cầu từ trạng thái không tính vào định biên (`REJECTED`, `CANCELLED`, đều là kết thúc) sang trạng thái có tính, nên đổi trạng thái không bao giờ phải khóa và kiểm định biên lại (`CountedRequisitionStatusChangeTest`).
2. Không thay đổi nào đưa yêu cầu đã đóng (`REJECTED`, `FILLED`, `CANCELLED`) về trạng thái còn mở, nên một lần xóa phòng ban đã thấy không còn yêu cầu mở vẫn đúng (`OpenRequisitionStatusChangeTest`).
3. `PAUSED` không sửa được, nên yêu cầu tạm dừng vẫn có nội dung đã duyệt và mở lại không cần duyệt lại (`RequisitionWorkflowStatusTest`).
4. Trạng thái đóng tin (`FILLED`, `CANCELLED`) không bao giờ là trạng thái tạo và hiện tin (`APPROVED`), nên tin đã đóng không thuộc yêu cầu nào còn dùng được nó (`JobPostingClosingStatusTest`, task 292).

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

Riêng tin tuyển dụng của yêu cầu đã tuyển đủ hoặc bị hủy thì có code riêng của task 292: tin bị đóng hẳn (mục "Tự gỡ tin tuyển dụng khi đóng yêu cầu (task 292)").

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
- Nguồn ứng viên (task 291) được hỏi sau khóa dòng yêu cầu và không khóa gì.
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
|Đưa hồ sơ vào quy trình (module ứng viên sau này)|HR đóng vì đã tuyển đủ hoặc hủy, không xác nhận|200; 409 `REQUISITION_HAS_ACTIVE_CANDIDATES`|

Dòng cuối chỉ đúng khi có nguồn thật mà thao tác ghi của nó khóa dòng yêu cầu như hợp đồng ở mục "Ứng viên còn trong quy trình (task 291)"; test hiện tại chỉ ghim rằng nguồn được hỏi sau khi có khóa. Xác nhận không gắn với số ứng viên đã thấy: ứng viên mới vào sau khi hộp thoại mở mà người gọi đã gửi `confirmActiveCandidates: true` vẫn bị đóng theo (câu hỏi 20 của [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)).

## Chỗ nối cho module khác (task 291, 292)

`RequisitionStatusListener` là chỗ để module khác phản ứng khi Trưởng phòng Nhân sự đổi trạng thái. Hai phương thức chạy trong cùng transaction với thay đổi, khi đang giữ khóa dòng yêu cầu, và nhận `RequisitionStatusTransition` (yêu cầu, thay đổi, trạng thái trước và sau, người thao tác, thời điểm đúng như dòng lịch sử, lý do):

- `beforeChange` chạy sau mọi bước kiểm và trước khi ghi gì; module khác vẫn có thể từ chối ở đây. Task 291 kiểm ứng viên trong chính `RequisitionStatusService` (bước 11), trước `beforeChange`; chỗ nối giữ cho module sau này.
- `afterChange` chạy khi dòng lịch sử và trạng thái mới đã được ghi. Task 292 đóng tin tuyển dụng của yêu cầu ở đây.
- Lỗi của một trong hai được trả nguyên cho người gọi và làm hủy toàn bộ thay đổi, kể cả dòng lịch sử.

Task 292 thêm listener đầu tiên, `JobPostingRequisitionSync` (mục sau). Cảnh báo ứng viên (task 291) đọc qua `GET /requisitions/{id}/active-pipeline`; `GET /job-requisitions/{id}/active-candidates-count` mà frontend đang gọi không tồn tại (403).

## Tự gỡ tin tuyển dụng khi đóng yêu cầu (task 292)

Khi Trưởng phòng Nhân sự đóng yêu cầu vì đã tuyển đủ (`FILLED`) hoặc hủy yêu cầu (`CANCELLED`), mọi tin tuyển dụng chưa đóng của yêu cầu chuyển sang `CLOSED` (Đã đóng) trong **cùng transaction** với thay đổi trạng thái (`JobPostingRequisitionSync`, chỗ nối `afterChange`). Không có API riêng để đóng tin: tin chỉ đóng cùng yêu cầu, và `POST /job-postings/{id}/close` trả 403 như mọi route không khai báo.

| Thay đổi của yêu cầu | Tin tuyển dụng của nó |
|---|---|
|Tạm dừng (`PAUSED`)|Không đổi: giữ trạng thái, `revision` và lịch sử; chỉ bị ẩn khỏi cổng (404) và không ghi được (409 về trạng thái của tin, ví dụ `JOB_POSTING_NOT_EDITABLE` khi sửa tin đã xuất bản, hoặc `JOB_POSTING_REQUISITION_NOT_APPROVED` khi trạng thái của tin cho phép bước đó; xem [Nhóm trạng thái](#nhóm-trạng-thái))|
|Mở lại (`APPROVED`)|Không đổi; tin `PUBLISHED` còn hạn nộp hiện lại trên cổng|
|Đã tuyển đủ (`FILLED`), hủy (`CANCELLED`)|Mọi tin `DRAFT`, `PENDING_APPROVAL`, `REVISION_REQUESTED`, `APPROVED`, `PUBLISHED` chuyển `CLOSED` vĩnh viễn (`JobPostingRequisitionSync.CLOSABLE_STATUSES`)|

Tạm dừng không đóng tin vì `CLOSED` là trạng thái cuối và mỗi yêu cầu chỉ có một tin (`job_postings_requisition_id_key`): đóng khi tạm dừng thì mở lại cũng không đăng tin được nữa. Tin chưa xuất bản (nháp, chờ duyệt, cần chỉnh sửa, đã duyệt) cũng bị đóng, vì chúng không bao giờ đi tiếp được và nếu để lại sẽ nằm mãi trong danh sách chờ duyệt của HR.

Tin sau khi đóng:

- `status` là `CLOSED`, `revision` tăng 1. `updatedAt` (lần sửa nội dung gần nhất) và dấu vết xuất bản (`publishedById`, `publishedByName`, `publishedAt`) giữ nguyên; tin chưa từng xuất bản vẫn không có dấu vết.
- Không bao giờ công khai: `GET /public/job-postings/{id}` trả 404 `JOB_POSTING_NOT_FOUND` giống hệt tin không tồn tại, và tin biến khỏi `GET /public/job-postings` (cả `totalElements`).
- Mọi cờ `actions` là `false`. Sửa, gửi duyệt, duyệt, yêu cầu chỉnh sửa và xuất bản trả 409 `JOB_POSTING_CLOSED` "Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không thể sửa, gửi duyệt, duyệt hay xuất bản nữa.". Đây là bước kiểm trạng thái **đầu tiên** sau phạm vi (403), nên người duyệt dù không phải người soạn cũng nhận 409 này, không phải 403 hay "không còn ở bước duyệt". Tạo tin mới cho yêu cầu đã đóng trả 409 `JOB_POSTING_REQUISITION_NOT_APPROVED` (yêu cầu được kiểm trước khi xem nó đã có tin chưa).
- Người soạn và HR vẫn đọc được tin, bản xem trước và lịch sử; `GET /job-postings?status=CLOSED` liệt kê các tin đã đóng.
- Lịch sử tin (`GET /job-postings/{id}/history`) có thêm đúng một bước `CLOSED`: người làm là người đóng yêu cầu, thời điểm đúng bằng `occurredAt` của dòng `FILLED`/`CANCELLED` trong lịch sử yêu cầu, `comment` là lý do của yêu cầu.

```json
{ "id": "…", "revision": 5, "action": "CLOSED", "statusBefore": "PUBLISHED", "statusAfter": "CLOSED",
  "actorId": "…", "actorName": "Phạm Thu Trang", "occurredAt": "2026-10-11T03:01:30.123456Z",
  "comment": "Đã tuyển đủ 2 người qua đợt tháng 10." }
```

Quyền, transaction và khóa:

- Người đóng yêu cầu **không cần** quyền `JOB_POSTINGS_*`: đóng tin là một phần quyết định về yêu cầu mà mục "Quyền" đã kiểm. Theo seed hiện tại HR_MANAGER và ADMIN đều có quyền tin tuyển dụng, nên điều này chỉ có tác dụng khi ma trận quyền đổi.
- Một transaction: nếu không đóng được tin (ví dụ lỗi database), yêu cầu cũng không đổi trạng thái, lịch sử yêu cầu không có dòng mới và tin vẫn như cũ; lỗi lúc commit cũng hủy cả hai. Việc đóng tin không bao giờ chạy trong transaction riêng hay sau khi commit.
- Khóa: tài khoản, phiên, dòng yêu cầu, rồi các tin của yêu cầu (`FOR NO KEY UPDATE`, theo id), đúng thứ tự mọi thao tác ghi tin dùng, nên không có deadlock. Tin được đọc sau khi đã khóa yêu cầu (READ COMMITTED), nên tin vừa được tạo hay vừa xuất bản ngay trước đó cũng bị đóng.

Kết quả khi một thao tác ghi tin và việc đóng yêu cầu đến cùng lúc (thao tác đến trước chạy trước):

| Trước | Sau | Kết quả |
|---|---|---|
|Xuất bản tin|Đóng yêu cầu (đã tuyển đủ)|200; 200; tin `CLOSED` từ `PUBLISHED`, giữ dấu vết xuất bản|
|Đóng yêu cầu|Xuất bản tin|200; 409 `JOB_POSTING_CLOSED`; tin `CLOSED` từ `APPROVED`, không có dấu vết|
|Duyệt, gửi duyệt hoặc lưu tin|Đóng yêu cầu|200; 200; tin `CLOSED` từ trạng thái sau thao tác đầu|
|Đóng yêu cầu|Duyệt, gửi duyệt hoặc lưu tin|200; 409 `JOB_POSTING_CLOSED`; nội dung tin không đổi|
|Tạo tin|Hủy yêu cầu|201; 200; bản nháp vừa tạo bị đóng|
|Hủy yêu cầu|Tạo tin|200; 409 `JOB_POSTING_REQUISITION_NOT_APPROVED`; không có tin|

Trong mọi trường hợp tin có đúng một bước `CLOSED` và không còn trên cổng. V25 (mục "Database và phạm vi") thêm trạng thái và bước `CLOSED`, và đóng tin của các yêu cầu đã tuyển đủ hoặc bị hủy trước khi V25 chạy.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`{id}` không phải UUID ("Tham số đường dẫn hoặc bộ lọc không hợp lệ."); thiếu hoặc sai `status`, `reason` quá 2.000 ký tự hoặc chứa ký tự NUL, thiếu `expectedRevision` hoặc nhỏ hơn 0 (`fieldErrors`)|
|400|`INVALID_JSON`|JSON sai, thiếu body, trường không được phép (`comment`, `reasonCode`, `actorId`, `occurredAt`, `statusBefore`...), `confirmActiveCandidates` không phải true/false|
|400|`REQUISITION_STATUS_REASON_REQUIRED`|Tạm dừng, đã tuyển đủ hoặc hủy mà không có lý do (kể cả lý do không có ký tự nhìn thấy được, ví dụ chỉ gồm khoảng trắng, U+00A0 hoặc U+200B); `fieldErrors.reason`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn hoặc phiên bị thu hồi, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu `REQUISITIONS_WRITE_ALL` (kể cả bị gỡ hoặc thu hẹp trong lúc chờ); không có quyền xem yêu cầu nào; người `SCOPED` ngoài phạm vi phòng ban. Cả `GET /requisitions/{id}/active-pipeline`|
|404|`REQUISITION_NOT_FOUND`|Không có yêu cầu với `{id}`|
|409|`REQUISITION_STATUS_CHANGE_NOT_ALLOWED`|Thay đổi không được phép từ trạng thái hiện tại ("Không thể chuyển yêu cầu tuyển dụng từ trạng thái hiện tại sang trạng thái đã chọn. Vui lòng tải lại để xem trạng thái mới nhất.")|
|409|`REQUISITION_STATE_CHANGED`|`expectedRevision` không còn là phiên bản mới nhất ("Yêu cầu tuyển dụng vừa có thay đổi khác (có thể người khác vừa xử lý). Vui lòng tải lại trước khi thao tác.")|
|409|`REQUISITION_HAS_ACTIVE_CANDIDATES`|Đã tuyển đủ hoặc hủy khi còn ứng viên trong quy trình mà không gửi `confirmActiveCandidates: true` ("Yêu cầu tuyển dụng còn {n} ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý các ứng viên này trước, hoặc xác nhận vẫn đóng yêu cầu vì đã tuyển đủ." / "… hoặc xác nhận vẫn hủy yêu cầu."; khi chỉ còn 1 ứng viên, "các ứng viên này" thành "ứng viên này"); `fieldErrors` rỗng; body có thêm `activePipeline` (task 291)|

Các 409 có sẵn mà nay gặp được với yêu cầu tạm dừng, đã tuyển đủ hoặc đã hủy: `REQUISITION_NOT_ASSIGNABLE`, `REQUISITION_NOT_DRAFT`, `REQUISITION_NOT_SUBMITTABLE`, `REQUISITION_NOT_PENDING_APPROVAL`, `JOB_POSTING_REQUISITION_NOT_APPROVED` (mục "Ảnh hưởng tới phần khác"). Task 292 thêm 409 `JOB_POSTING_CLOSED` ở mọi thao tác ghi tin đã đóng cùng yêu cầu (mục "Tự gỡ tin tuyển dụng khi đóng yêu cầu (task 292)"). Lỗi bất ngờ khi đóng tin (lỗi database) trả 500 và hủy toàn bộ thay đổi.

## Database và phạm vi

V24 (task 293) không đổi dòng nào, không thêm quyền, không thêm route:

- CHECK `valid_requisition_status` của `recruitment_requisitions` có tám trạng thái;
- `requisition_workflow_events`: CHECK `valid_requisition_workflow_event_type` thêm `PAUSED`, `RESUMED`, `FILLED`, `CANCELLED`; `valid_requisition_workflow_event_transition` thêm bốn bước của bảng "Chuyển trạng thái"; `requisition_workflow_event_comment_required` bắt lý do ở `PAUSED`, `FILLED`, `CANCELLED`;
- chỉ mục `requisition_workflow_events_one_end_idx`.

Các CHECK `_round`, `_level`, `_snapshot` của V19 không phải sửa: chúng có dạng "loại của luồng duyệt = cột có giá trị", nên đã buộc dòng đổi trạng thái để trống lượt, cấp và bản chụp. `_comment` và hai khóa ngoại của V19 cũng giữ nguyên. Ràng buộc mới là DDL, không chạy trigger của V20. Test của migration: `RequisitionStatusChangeMigrationTest`; với hai CHECK bị xóa rồi tạo lại (`_transition`, `comment_required`), test kiểm ở V24 cả điều kiện cũ của V19 (bước gửi duyệt và quyết định sai trạng thái trước/sau, từ chối hay yêu cầu bổ sung không có nhận xét đều bị từ chối).

Task 290 và 291 không thêm migration và không thêm mã quyền: các route mới dùng `REQUISITIONS_WRITE_ALL` sẵn có; xác nhận của task 291 không được lưu.

V25 (task 292) chỉ đổi bảng tin tuyển dụng, không thêm quyền, không thêm route:

- `job_postings`: CHECK `valid_job_posting_status` thêm `CLOSED`; `job_posting_details_required` cho tin `CLOSED` chưa từng xuất bản được thiếu địa điểm, hình thức làm việc, hạn nộp (bản nháp viết dở cũng bị đóng), còn tin đóng sau khi xuất bản vẫn phải có đủ. Hai CHECK dấu vết của V23 không liệt kê `CLOSED`, nên dấu vết xuất bản được giữ;
- `job_posting_events`: `valid_job_posting_event_type` thêm `CLOSED`; `valid_job_posting_event_transition` thêm bước đóng từ năm trạng thái kia sang `CLOSED` (không bước nào rời `CLOSED`); `job_posting_event_comment_required` bắt lý do ở `CLOSED`; chỉ mục `job_posting_events_one_closure_idx` cho mỗi tin tối đa một dòng `CLOSED`. Trigger chỉ thêm của V23 áp dụng cho cả dòng này;
- nâng cấp: tin chưa đóng của yêu cầu đã có dòng `FILLED` hoặc `CANCELLED` (đóng bằng API của task 290 trước V25) được đóng ngay, cùng người, thời điểm và lý do của dòng đó.

Test của migration: `JobPostingClosureMigrationTest`; với hai CHECK bị xóa rồi tạo lại (`valid_job_posting_event_transition`, `job_posting_event_comment_required`), test kiểm ở V25 cả điều kiện cũ của V22 (bước gửi duyệt, duyệt, yêu cầu chỉnh sửa, xuất bản sai trạng thái trước/sau, yêu cầu chỉnh sửa không có nhận xét đều bị từ chối). Code: `JobPostingRequisitionSync`, `JobPostingRepository.findByRequisitionIdForUpdate`, `JobPostingService.requireNotClosed`.

## Ghi chú cho frontend

Màn hình đổi trạng thái ở nhánh `UI` (`jobReqStatusService.ts`, `JobReqStatusActionsUI.tsx`) chưa được gắn vào trang nào, nên đổi theo hợp đồng này được ngay:

| Frontend hiện tại | Backend | Cần sửa |
|---|---|---|
|`` axiosClient.patch(`/job-requisitions/${id}/status`, data) ``|`POST /requisitions/{id}/status-changes`. CORS không cho `PATCH`; `/job-requisitions` không có trong bảng quyền nên trả 403, và giao diện chuyển mọi 403 sang `/unauthorized`|`` axiosClient.post(`/requisitions/${id}/status-changes`, data) ``|
|Body `{status: 'PAUSED' \| 'CLOSED' \| 'CANCELLED', reason?}`|`{status: 'PAUSED' \| 'APPROVED' \| 'FILLED' \| 'CANCELLED', reason, expectedRevision}`. `reason` giữ tên. `CLOSED` là `FILLED` (chờ BA/PO chốt mã). `APPROVED` là mở lại yêu cầu tạm dừng|Đổi `CLOSED` thành `FILLED`; thêm `expectedRevision`|
|Không gửi phiên bản|`expectedRevision` là `.revision` của `GET /requisitions/{id}/approvals`; gặp 409 `REQUISITION_STATE_CHANGED` thì tải lại|Đọc `.revision` trước khi mở hộp thoại|
|`getActiveCandidatesCount`: `` axiosClient.get(`/job-requisitions/${id}/active-candidates-count`) `` → `{count}`, gọi trước mọi thao tác (cả tạm dừng), lỗi thì không mở hộp thoại|`GET /requisitions/{id}/active-pipeline` → `{count, stages}` (task 291; chỉ `REQUISITIONS_WRITE_ALL`, đường cũ vẫn 403 và bị chuyển sang `/unauthorized`). Trả lời ở mọi trạng thái; hiện luôn là `{count: 0, stages: []}` vì chưa có module ứng viên (0 là chưa có dữ liệu)|Đổi URL, giữ `data.count`; có thể liệt kê `stages` (`name`, `count`), mảng này có thể rỗng kể cả khi `count > 0`. Giữ khối cảnh báo: nó hiện khi có module ứng viên. Đổi cùng lúc với việc gắn `JobReqStatusActionsUI` vào trang chi tiết (chỉ khi có `REQUISITIONS_WRITE_ALL`)|
|Gửi đổi trạng thái không kèm xác nhận|`confirmActiveCandidates: true` cho đã tuyển đủ hoặc hủy đi qua khi còn ứng viên; bị bỏ qua khi tạm dừng hay mở lại|Gửi `confirmActiveCandidates: true` khi thao tác là `FILLED` hoặc `CANCELLED` và hộp thoại đã hiện số lớn hơn 0|
|–|409 `REQUISITION_HAS_ACTIVE_CANDIDATES` (có người vào quy trình sau khi hộp thoại mở); body có thêm `activePipeline`|Lấy số từ `err.response.data.activePipeline.count`, giữ hộp thoại mở với cảnh báo và `message`; lần bấm sau gửi lại với `true` và cùng `expectedRevision`|
|Lý do "(Tùy chọn)", chỉ gửi khi có nội dung|Bắt buộc khi tạm dừng, đã tuyển đủ, hủy (tối đa 2.000 ký tự); thiếu thì 400 `REQUISITION_STATUS_REASON_REQUIRED`, hiện bằng `apiError` sẵn có|Nhãn "bắt buộc"|
|Câu mô tả `CLOSED` "tuyển đủ người hoặc không còn nhu cầu… mở lại sẽ cần duyệt lại"|`FILLED` là đã tuyển đủ; "không còn nhu cầu" là `CANCELLED`; muốn tuyển lại thì sao chép (`POST /requisitions/{id}/copy`)|Sửa câu chữ|
|Luôn hiện ba nút, không có nút mở lại|Chỉ gắn khi `permissions.includes('REQUISITIONS_WRITE_ALL')`. `APPROVED`: Tạm dừng / Đã tuyển đủ / Hủy; `PAUSED`: Mở lại / Đã tuyển đủ / Hủy; `DRAFT`, `PENDING_APPROVAL`, `RETURNED`: Hủy; trạng thái khác: không có nút|Thêm props và điều kiện hiện|
|Dòng thời gian lịch sử|`.history[].action` thêm `PAUSED` "Tạm dừng", `RESUMED` "Mở lại", `FILLED` "Đã tuyển đủ", `CANCELLED` "Hủy"; `comment` là lý do; `round`, `level` là `null`|Thêm nhãn|
|Huy hiệu và lịch sử tin tuyển dụng|Tin của yêu cầu đã tuyển đủ hoặc bị hủy có `status` `CLOSED` "Đã đóng" (task 292); nhãn bước "Đóng tin tuyển dụng" đã có trong `JobPostingAuditHistory.tsx`; mọi cờ `actions` là `false`; gặp 409 `JOB_POSTING_CLOSED` thì hiện `message`|Thêm nhãn `CLOSED` "Đã đóng" nếu chưa có|
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
- tạm dừng chỉ ẩn tin tuyển dụng khỏi cổng (tin vẫn "đã xuất bản", hiện lại khi mở lại nếu còn hạn nộp) và không cho sửa, duyệt hay xuất bản tin;
- đã tuyển đủ hoặc hủy: mọi tin chưa đóng của yêu cầu (kể cả nháp, chờ duyệt) chuyển "Đã đóng" vĩnh viễn, giữ người và thời điểm xuất bản; lý do của yêu cầu được chép vào lịch sử tin (task 292);
- người đóng yêu cầu không cần quyền tin tuyển dụng (task 292);
- hủy khi đang chờ duyệt kết thúc lượt duyệt; người duyệt của cấp đang chờ vẫn xem được yêu cầu và tiến độ, bấm quyết định trên trang cũ thì nhận 409 để tải lại; người của cấp chưa tới không xem được; cấp đang chờ hiện "Chưa đến lượt", chưa có trạng thái riêng cho cấp bị dừng;
- ứng viên đang xử lý (task 291): "đang trong quy trình" là hồ sơ ứng tuyển chưa ở trạng thái cuối, do module ứng viên định nghĩa; chỉ đã tuyển đủ và hủy cần xác nhận, tạm dừng không; xác nhận không được lưu, lý do đóng vẫn bắt buộc; chỉ Trưởng phòng Nhân sự và ADMIN (`REQUISITIONS_WRITE_ALL`) xem số ứng viên đang xử lý, trưởng bộ phận và recruiter được phân công thì không; ứng viên còn lại sau khi đóng có xác nhận chưa được xử lý gì; hiện nguồn luôn trả 0 (chưa có dữ liệu ứng viên) nên đóng yêu cầu không bị chặn cho tới khi có module ứng viên; xác nhận là cờ đúng/sai, không gắn với số ứng viên đã thấy (câu hỏi 20 của [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)).
