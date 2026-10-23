# Email ứng viên, nhật ký gửi, thông báo trong ứng dụng và SLA

Tài liệu của lane messaging (Sprint 7–8 và S5-10): mẫu email (story S8-01), nhật ký gửi email (S8-02), gửi thư từ chối
hàng loạt (S7-09), thông báo trong ứng dụng (S8-03), tùy chọn kênh thông báo (S8-04) và cảnh báo quá hạn SLA (S5-10).
Mọi API trả `Cache-Control: no-store`, lỗi theo dạng `{code, message, fieldErrors}` (thông báo tiếng Việt), từ chối
trường JSON lạ (`INVALID_JSON`). 401 khi token/phiên không còn hiệu lực, 403 trước 404.

## Mẫu email theo loại (task 531)

Migration `V66__create_email_templates.sql` tạo bảng `email_templates`, một dòng cho mỗi loại thư gửi ứng viên:

| `template_type` | Thư | Biến được hỗ trợ | Gửi tự động mặc định |
|---|---|---|---|
| `APPLICATION_RECEIPT` | Xác nhận đã nhận hồ sơ | `candidateName`, `positionTitle`, `companyName` | Bật |
| `INTERVIEW_INVITATION` | Mời phỏng vấn | `candidateName`, `positionTitle`, `interviewTime`, `companyName` | Bật |
| `INTERVIEW_THANKS` | Cảm ơn sau phỏng vấn | `candidateName`, `positionTitle`, `interviewTime`, `companyName` | Tắt |
| `REJECTION` | Thư từ chối | `candidateName`, `positionTitle`, `companyName` | Tắt |
| `OFFER` | Thư offer | `candidateName`, `positionTitle`, `companyName` | Bật |

Cột: `subject` (một dòng, tối đa 200 ký tự), `body` (văn bản thuần, tối đa 5.000 ký tự, dòng trống tách đoạn),
`supported_variables` (danh sách cố định của loại, API không sửa), `auto_send`, `version` (tăng mỗi lần lưu),
`updated_by` (`ON DELETE SET NULL`), `updated_at`. CHECK của database chặn tiêu đề trống hoặc có ký tự điều khiển (kể
cả CR/LF, nên mẫu không thể chèn header thư), nội dung có ký tự điều khiển ngoài xuống dòng/tab, loại lạ và danh sách
biến rỗng.

Quy tắc văn bản (`TemplateText`, dùng chung cho API, bản xem trước và mọi luồng gửi):

- Biến viết `{{tenBien}}`, tên chỉ gồm chữ cái và phải thuộc danh sách của loại. `{{ candidateName }}`, `{{salary}}`,
  `{{` hoặc `}}` thừa đều bị từ chối, không bao giờ gửi nguyên văn.
- Không dùng thẻ HTML (`<b>`…); `<` đứng riêng như "lương < 20 triệu" vẫn hợp lệ.
- Giá trị chèn vào luôn nằm trên một dòng: ký tự điều khiển, ký tự định dạng hoặc phân cách dòng trong tên ứng viên
  thành khoảng trắng.
- Phần bắt buộc (mã tra cứu, liên kết tra cứu/rút hồ sơ, chi tiết và liên kết phản hồi lời mời phỏng vấn, điều khoản
  và liên kết phản hồi offer) **không** nằm trong mẫu: hệ thống luôn thêm sau phần mẫu, nên sửa mẫu không làm mất các
  nội dung này.

Mặc định của V66 tái tạo đúng thư đang gửi trước đây; hai thư mới (cảm ơn sau phỏng vấn, từ chối) mặc định tắt gửi tự
động để triển khai bản này không gửi thêm thư nào cho tới khi HR bật.

Câu hỏi BA/PO: mặc định bật/tắt của hai thư mới; có cần lưu lịch sử từng phiên bản mẫu (hiện chỉ giữ người sửa cuối và
`version`).

## API quản lý mẫu và xem trước (task 532)

| Method | Endpoint | Quyền (đọc lại từ database ở service) |
|---|---|---|
| GET | `/api/v1/messaging/templates` | `NOTIFICATIONS_READ_ALL` |
| GET | `/api/v1/messaging/templates/{type}` | `NOTIFICATIONS_READ_ALL` |
| PUT | `/api/v1/messaging/templates/{type}` | `NOTIFICATIONS_READ_ALL` + `NOTIFICATIONS_WRITE_ALL` |
| POST | `/api/v1/messaging/templates/{type}/preview` | `NOTIFICATIONS_READ_ALL` + `CANDIDATES_READ_ALL`/`CANDIDATES_READ_SCOPED` và phạm vi của hồ sơ |

Theo seed hiện tại: ADMIN, HR_MANAGER, RECRUITER đọc và sửa được mẫu (đều có `NOTIFICATIONS_*_ALL`); HIRING_MANAGER,
INTERVIEWER (chỉ `NOTIFICATIONS_READ_SCOPED`) và APPROVER bị 403. `{type}` phải viết hoa đúng tên loại, sai thì 404
`EMAIL_TEMPLATE_NOT_FOUND`.

Phản hồi một mẫu (GET danh sách trả mảng các phần tử như dưới):

```json
{"type":"INTERVIEW_THANKS","displayName":"Cảm ơn sau phỏng vấn","subject":"Cảm ơn bạn đã tham gia phỏng vấn",
 "body":"Xin chào {{candidateName}},\n\nCảm ơn bạn ...","autoSend":false,
 "variables":[{"name":"candidateName","label":"Tên ứng viên"},{"name":"positionTitle","label":"Tên vị trí ứng tuyển"},
              {"name":"interviewTime","label":"Thời gian phỏng vấn"},{"name":"companyName","label":"Tên công ty"}],
 "mandatoryContent":"Không có nội dung bắt buộc ngoài phần chân email.","version":0,
 "updatedAt":"2026-10-20T00:00:00Z","updatedBy":null,"updatedByName":null}
```

PUT thay toàn bộ phần sửa được:

```json
{"expectedVersion":0,"subject":"Kết quả ứng tuyển {{positionTitle}}","body":"Chào {{candidateName}},\n\nRất tiếc ...","autoSend":true}
```

- 400 `VALIDATION_ERROR`: thiếu trường, quá dài, tiêu đề có CR/LF hoặc ký tự điều khiển, có thẻ HTML (`fieldErrors`
  nêu trường). CRLF trong nội dung được lưu thành `\n`; CR đứng riêng bị từ chối.
- 400 `EMAIL_TEMPLATE_INVALID`: biến lạ hoặc sai dạng (`fieldErrors.subject`/`fieldErrors.body` liệt kê biến lạ và biến
  hợp lệ). 400 `INVALID_JSON`: trường lạ (kể cả `supportedVariables`), `autoSend` không phải `true`/`false`,
  `expectedVersion` không phải số nguyên.
- 409 `EMAIL_TEMPLATE_VERSION_CONFLICT`: người khác vừa lưu; tải lại. 503 `EMAIL_TEMPLATE_BUSY`: hết thời gian chờ khóa.

Lưu theo thứ tự khóa của hệ thống: tài khoản → phiên → dòng mẫu `FOR UPDATE`; quyền được đọc lại sau mỗi lần chờ khóa.

POST preview — xem trước với dữ liệu thật (mẫu đã lưu hoặc bản nháp chưa lưu):

```json
{"applicationId":"<uuid>","interviewId":"<uuid, tùy chọn>","subject":"<nháp, tùy chọn>","body":"<nháp, tùy chọn>"}
```

```json
{"type":"REJECTION","applicationId":"...","interviewId":null,"draft":true,"autoSend":false,"templateVersion":0,
 "subject":"Kết quả vị trí Java","text":"Chào Nguyễn Văn A,\n\n...","html":"<!DOCTYPE html>..."}
```

- Thứ tự kiểm: quyền (401/403) → loại mẫu (404) → nháp hợp lệ (400 `EMAIL_TEMPLATE_INVALID`) → hồ sơ (404
  `APPLICATION_NOT_FOUND`) → phạm vi hồ sơ (403, ghi nhật ký truy cập `DENIED`) → lịch phỏng vấn cho
  `INTERVIEW_INVITATION`/`INTERVIEW_THANKS`: `interviewId` phải thuộc hồ sơ (404 `INTERVIEW_NOT_FOUND`), không gửi thì
  lấy lịch sắp tới gần nhất, nếu không có thì lịch gần nhất; hồ sơ chưa có lịch nào → 409
  `EMAIL_PREVIEW_INTERVIEW_REQUIRED`.
- Bản xem trước được ghi nhật ký `candidate_access_events` (`PROFILE`, `GRANTED`), không gửi thư, không lưu nháp.
- `text` là phần text/plain, `html` là phần HTML (null với `APPLICATION_RECEIPT` vì thư xác nhận là văn bản thuần).
- Phần bắt buộc giữ nguyên như thư thật, nhưng mã tra cứu, liên kết tra cứu/rút hồ sơ, liên kết phản hồi, liên kết
  phòng họp và điều khoản offer được thay bằng chú thích trong ngoặc vuông: bản xem trước không bao giờ chứa bí mật hay
  mức lương.

Ghi chú cho frontend: màn hình sửa mẫu hiển thị danh sách `variables` để chèn, `mandatoryContent` để người dùng biết phần
nào hệ thống tự thêm; gửi lại `version` đang có trong `expectedVersion`; xem trước cần chọn một hồ sơ ứng tuyển (và
lịch phỏng vấn với hai loại phỏng vấn); hiển thị `html` trong iframe sandbox hoặc chỉ hiển thị `text`.

Câu hỏi BA/PO: RECRUITER có được sửa mẫu dùng chung toàn công ty không (seed V3 hiện cho phép vì có
`NOTIFICATIONS_WRITE_ALL`; code không đổi quyền này). Hệ quả cụ thể: **một recruiter bất kỳ** có thể sửa nội dung mọi thư
gửi ứng viên của toàn công ty và tắt "gửi tự động" của mẫu Thư offer; khi đó mọi lần gửi offer của HR trên toàn công ty
bị chặn với 409 `OFFER_EMAIL_DISABLED` (task 533) cho tới khi có người bật lại.
