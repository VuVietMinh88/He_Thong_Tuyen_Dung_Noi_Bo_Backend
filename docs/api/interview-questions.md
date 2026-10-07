# API câu hỏi phỏng vấn

Phạm vi TKNHTTDNB1-221 "Xây dựng API quản lý câu hỏi phỏng vấn", story TKNHTTDNB1-26 (S2-07). Kết quả mong đợi: tạo, sửa và đọc câu hỏi gắn với tiêu chí hợp lệ trong khung năng lực. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi nghiệp vụ của nhóm này (`INTERVIEW_QUESTION_*`, `INVALID_COMPETENCY_CRITERION`) dùng `Cache-Control: no-store`.

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi, service khóa tài khoản người gọi rồi phiên, kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền, sau đó mới khóa câu hỏi (khi sửa) và khung năng lực chứa tiêu chí.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /interview-questions/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ (kể cả INTERVIEWER, vì người phỏng vấn dùng các câu hỏi này)|
|`POST /interview-questions`, `PUT /interview-questions/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|

Câu hỏi thuộc khung năng lực nên dùng chung quyền ORGANIZATION với [API khung năng lực](competency-frameworks.md); không có mã quyền riêng. Người không có quyền ghi luôn nhận 403, kể cả khi body sai, vì quyền được kiểm tra trước dữ liệu.

## Khái niệm

- **Ngân hàng câu hỏi**: mỗi câu hỏi gắn với **đúng một tiêu chí** (`criterionId`) của một khung năng lực. Câu hỏi không gắn trực tiếp với chức danh: chức danh trỏ tới khung (`positions.competency_framework_id`), khung có các tiêu chí, tiêu chí có các câu hỏi. Vì vậy các chức danh dùng chung một khung thấy cùng một bộ câu hỏi, không có bản sao. Tìm kiếm và lọc câu hỏi theo chức danh, tiêu chí thuộc task 223.
- **Mức độ khó** (`difficulty`): `EASY`, `MEDIUM` hoặc `HARD`.
- **Gợi ý câu trả lời tốt** (`answerHint`): điều người phỏng vấn nên nghe thấy trong một câu trả lời tốt; tùy chọn.
- **Đang dùng** (`active`): câu hỏi không dùng nữa được chuyển `active = false` thay vì xóa, để giữ lịch sử. Không có API xóa câu hỏi.

## Tạo và sửa

`POST /interview-questions` tạo câu hỏi, trả **201**. `PUT /interview-questions/{id}` thay thế toàn bộ câu hỏi có UUID tương ứng, trả **200**.

```json
{
  "criterionId": "00000000-0000-0000-0000-000000000011",
  "content": "Kể về một lần bạn bất đồng với đồng nghiệp.\nBạn đã xử lý thế nào?",
  "difficulty": "MEDIUM",
  "answerHint": "Nêu tình huống cụ thể, cách lắng nghe và kết quả."
}
```

| Trường | Quy tắc |
|---|---|
|criterionId|Bắt buộc, UUID của một tiêu chí đang có trong một khung năng lực (khung `DRAFT` hay `ACTIVE` đều được). Không phải UUID trả `INVALID_JSON`; UUID không phải tiêu chí nào (kể cả UUID của khung) trả 400 `INVALID_COMPETENCY_CRITERION`|
|content|Bắt buộc, tối đa **2000** ký tự sau khi bỏ khoảng trắng ở đầu/cuối (xem ghi chú dưới bảng); xuống dòng ở giữa được giữ, nên câu hỏi viết được nhiều dòng|
|difficulty|Bắt buộc, chuỗi `"EASY"`, `"MEDIUM"` hoặc `"HARD"` (viết hoa đúng như vậy). Chuỗi khác như `"easy"`, `"VERY_HARD"` trả `INVALID_JSON`|
|answerHint|Tùy chọn, tối đa **4000** ký tự sau khi bỏ khoảng trắng đầu/cuối như `content`; xuống dòng ở giữa được giữ. Bỏ trường, `null`, chuỗi rỗng hoặc chỉ có khoảng trắng đều được lưu là `null`|
|active|Tùy chọn, `true` hoặc `false`. Bỏ trường hoặc `null`: POST tạo câu hỏi đang dùng (`true`), PUT giữ giá trị hiện có. Gửi `false` để ngừng dùng câu hỏi mà vẫn giữ lại; gửi `true` để dùng lại|

"Khoảng trắng" ở đây là mọi ký tự PostgreSQL coi là `[[:space:]]` trong CHECK của V9: dấu cách, tab, xuống dòng và cả các khoảng trắng Unicode như khoảng trắng không ngắt (U+00A0, U+2007, U+202F) hay gặp khi dán chữ từ Word hoặc trang web. `String.strip()` của Java không bỏ các ký tự không ngắt này, nên `InterviewQuestionRequest` dùng hàm riêng. Khoảng trắng không ngắt nằm giữa câu được giữ nguyên. Nội dung chỉ gồm các ký tự này bị coi là rỗng (`VALIDATION_ERROR`).

Trường ngoài hợp đồng, như `id`, `createdAt` hay `criterion`, bị từ chối với HTTP 400 `INVALID_JSON`.

PUT thay thế toàn bộ câu hỏi: gửi đủ `criterionId`, `content`, `difficulty`; bỏ `answerHint` hoặc gửi `null` nghĩa là xóa gợi ý cũ. Riêng `active` được giữ nguyên khi không gửi. PUT có thể **chuyển câu hỏi sang tiêu chí khác**, kể cả tiêu chí của khung khác; `id` và `createdAt` của câu hỏi giữ nguyên, `updatedAt` là thời điểm sửa. Đây cũng là cách gỡ câu hỏi khỏi một tiêu chí trước khi bỏ tiêu chí đó khỏi khung, vì [PUT khung](competency-frameworks.md#put-thay-thế-danh-sách-tiêu-chí-như-thế-nào) không xóa tiêu chí còn câu hỏi (409 `COMPETENCY_CRITERION_IN_USE`, kể cả câu hỏi `active = false`).

**Quyết định tạm thời của task 221** (cần BA/PO xác nhận):

- Database (V9) lưu `content` và `answerHint` bằng `TEXT` không giới hạn; API đặt giới hạn 2000 và 4000 ký tự để chặn dữ liệu quá lớn.
- Câu hỏi gắn được với tiêu chí của khung `DRAFT`, để HR soạn câu hỏi trong lúc khung còn là bản nháp.
- Chưa chặn hai câu hỏi trùng nội dung.

Kiểm tra chi tiết hơn về tiêu chí được tham chiếu, mức độ khó và các trường của câu hỏi thuộc task 222.

Response của tạo/sửa và `GET /interview-questions/{id}`:

```json
{
  "id": "00000000-0000-0000-0000-000000000021",
  "criterion": {
    "id": "00000000-0000-0000-0000-000000000011",
    "name": "Giao tiếp"
  },
  "framework": {
    "id": "00000000-0000-0000-0000-000000000010",
    "code": "DEV_CORE",
    "name": "Năng lực lập trình viên"
  },
  "content": "Kể về một lần bạn bất đồng với đồng nghiệp.\nBạn đã xử lý thế nào?",
  "difficulty": "MEDIUM",
  "answerHint": "Nêu tình huống cụ thể, cách lắng nghe và kết quả.",
  "active": true,
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa.

| Trường | Ý nghĩa |
|---|---|
|criterion|Tiêu chí câu hỏi gắn với: `id` và **tên hiện tại**. HR đổi tên tiêu chí bằng PUT khung (giữ `id`) thì lần đọc sau thấy tên mới, câu hỏi vẫn đi theo tiêu chí|
|framework|Khung năng lực chứa tiêu chí: `id`, `code`, `name`|
|answerHint|`null` khi không có gợi ý|
|createdAt, updatedAt|UTC, độ chính xác micro giây như PostgreSQL lưu; `createdAt` giữ nguyên khi sửa|

## Đọc một câu hỏi

`GET /interview-questions/{id}`, trong đó `{id}` là UUID của câu hỏi. Không có body hay tham số. Trả **200** với response như trên, kể cả câu hỏi `active = false`. Câu hỏi, tiêu chí và khung được đọc trong cùng một snapshot (`REPEATABLE_READ`, chỉ đọc), nên luôn khớp nhau; lần đọc không chờ khi HR đang sửa dở mà trả bản đã commit gần nhất.

Chưa có danh sách, tìm kiếm và lọc câu hỏi (task 223): `GET /interview-questions` hiện bị từ chối 403 theo quy tắc mặc định.

## Thứ tự kiểm tra và đồng thời

1. Token và quyền (401/403), rồi từng trường riêng lẻ (`VALIDATION_ERROR`, mọi trường sai được trả cùng lúc trong `fieldErrors`) hoặc JSON sai (`INVALID_JSON`).
2. PUT: câu hỏi phải tồn tại (404 `INTERVIEW_QUESTION_NOT_FOUND`). Service khóa dòng câu hỏi (`SELECT ... FOR UPDATE`), nên hai người sửa cùng một câu hỏi được xử lý lần lượt. Không có kiểm tra phiên bản (optimistic lock): người lưu sau ghi đè thay đổi của người lưu trước.
3. Tiêu chí phải tồn tại (400 `INVALID_COMPETENCY_CRITERION`). Service tìm khung của tiêu chí, khóa dòng khung bằng `SELECT ... FOR SHARE`, rồi đọc lại tiêu chí.
4. Ghi câu hỏi trong cùng transaction.

Khóa `FOR SHARE` trên dòng khung (giống khi gán khung cho chức danh, task 214) giữ cho tiêu chí không bị xóa giữa lúc kiểm và lúc lưu:

- Nếu HR đang sửa khung (PUT khung giữ khóa `FOR UPDATE`), lần ghi câu hỏi chờ PUT khung commit rồi mới đọc lại tiêu chí. Tiêu chí vừa bị PUT khung xóa trả 400 `INVALID_COMPETENCY_CRITERION`, không phải 500.
- Nếu một lần ghi câu hỏi đang chạy, PUT khung chờ nó commit; sau đó PUT khung thấy câu hỏi mới và không xóa tiêu chí của nó (409 `COMPETENCY_CRITERION_IN_USE`).
- Nhiều lần ghi câu hỏi trên cùng một khung dùng chung khóa nên không chờ nhau.
- Tiêu chí bị xóa bằng cách khác (sửa trực tiếp bằng SQL, không khóa khung) vẫn bị khóa ngoại V9 chặn; service đổi lỗi này thành cùng mã 400.

Mọi lỗi đều không thay đổi dữ liệu.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu `criterionId`, `content` hoặc `difficulty`; `content` rỗng/chỉ khoảng trắng; `content` quá 2000 hoặc `answerHint` quá 4000 ký tự; UUID trên đường dẫn sai. Hiếm hơn: database dùng locale coi thêm một ký tự khác là khoảng trắng nên CHECK của V9 vẫn từ chối `content`/`answerHint` (`23514`); khi đó `fieldErrors` nêu đúng trường đó thay vì trả 500|
|400|INVALID_JSON|JSON sai, `criterionId` không phải UUID, `difficulty` không phải `"EASY"`/`"MEDIUM"`/`"HARD"`, `active` không phải true/false, hoặc có trường ngoài hợp đồng|
|400|INVALID_COMPETENCY_CRITERION|`criterionId` không phải tiêu chí nào đang có (kể cả tiêu chí vừa bị xóa trong lúc chờ); `fieldErrors.criterionId`|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Đọc thiếu `ORGANIZATION_READ_ALL`; ghi thiếu `ORGANIZATION_WRITE_ALL`; hoặc gọi URL chưa có như `DELETE /interview-questions/{id}`|
|404|INTERVIEW_QUESTION_NOT_FOUND|Không tìm thấy câu hỏi (GET, PUT)|

Ví dụ tiêu chí không tồn tại:

```json
{
  "code": "INVALID_COMPETENCY_CRITERION",
  "message": "Tiêu chí đánh giá không tồn tại.",
  "fieldErrors": {
    "criterionId": "Không tìm thấy tiêu chí này trong khung năng lực nào."
  }
}
```

## Database và phạm vi

Dùng bảng `interview_questions` của V9 (task 220), `competency_criteria` và `competency_frameworks` của V8, quyền ORGANIZATION của V3. Task 221 không thêm migration, mã quyền hay dòng cấp quyền và không cần sửa `.env`. Mã nguồn ở gói `vn.ttcs.recruitment.interviewquestion` (`InterviewQuestionController`, `InterviewQuestionService`, `InterviewQuestionRequest`, `InterviewQuestionView`).

Chưa có: danh sách, tìm kiếm và lọc câu hỏi theo chức danh và tiêu chí (task 223), kiểm tra chi tiết dữ liệu câu hỏi (task 222), xóa câu hỏi, phiếu đánh giá phỏng vấn (Sprint 6).
