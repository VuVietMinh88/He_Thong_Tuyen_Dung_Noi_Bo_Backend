# API câu hỏi phỏng vấn

Phạm vi TKNHTTDNB1-221 "Xây dựng API quản lý câu hỏi phỏng vấn", TKNHTTDNB1-222 "Kiểm tra tiêu chí và dữ liệu câu hỏi phỏng vấn" và TKNHTTDNB1-223 "Xây dựng API tìm kiếm và lọc câu hỏi phỏng vấn", story TKNHTTDNB1-26 (S2-07). Kết quả mong đợi: tạo, sửa và đọc câu hỏi gắn với tiêu chí hợp lệ trong khung năng lực (221); kiểm tra tiêu chí được tham chiếu, mức độ khó và các trường của câu hỏi (222); trả câu hỏi theo nội dung tìm kiếm, chức danh và tiêu chí được chọn (223). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi nghiệp vụ của nhóm này (`INTERVIEW_QUESTION_*`, `INVALID_COMPETENCY_CRITERION`, `INVALID_POSITION`, lỗi tham số tìm kiếm) dùng `Cache-Control: no-store`.

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi, service khóa tài khoản người gọi rồi phiên, kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền, sau đó mới khóa câu hỏi (khi sửa), khung năng lực chứa tiêu chí và dòng tiêu chí.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /interview-questions`, `GET /interview-questions/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ (kể cả INTERVIEWER, vì người phỏng vấn dùng các câu hỏi này)|
|`POST /interview-questions`, `PUT /interview-questions/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|

Câu hỏi thuộc khung năng lực nên dùng chung quyền ORGANIZATION với [API khung năng lực](competency-frameworks.md); không có mã quyền riêng. Người không có quyền ghi luôn nhận 403, kể cả khi body sai, vì quyền được kiểm tra trước dữ liệu.

## Khái niệm

- **Ngân hàng câu hỏi**: mỗi câu hỏi gắn với **đúng một tiêu chí** (`criterionId`) của một khung năng lực. Câu hỏi không gắn trực tiếp với chức danh: chức danh trỏ tới khung (`positions.competency_framework_id`), khung có các tiêu chí, tiêu chí có các câu hỏi. Vì vậy các chức danh dùng chung một khung thấy cùng một bộ câu hỏi, không có bản sao. Tìm kiếm và lọc câu hỏi theo chức danh, tiêu chí xem [Tìm kiếm và lọc](#tìm-kiếm-và-lọc-task-223).
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
|content|Bắt buộc, tối đa **2000** ký tự sau khi bỏ khoảng trắng ở đầu/cuối (xem ghi chú dưới bảng); xuống dòng ở giữa được giữ, nên câu hỏi viết được nhiều dòng. Không được chứa ký tự điều khiển, trừ tab và xuống dòng (task 222). Trong cùng một tiêu chí không được trùng nội dung với câu hỏi khác (409, xem [Câu hỏi trùng nội dung](#câu-hỏi-trùng-nội-dung))|
|difficulty|Bắt buộc, chuỗi `"EASY"` (dễ), `"MEDIUM"` (trung bình) hoặc `"HARD"` (khó), viết hoa đúng như vậy, không có khoảng trắng. Giá trị khác như `"easy"`, `"VERY_HARD"`, `""`, `" EASY"`, số hay `true` trả `VALIDATION_ERROR` với `fieldErrors.difficulty` nêu ba giá trị hợp lệ (task 222); mảng hoặc đối tượng JSON trả `INVALID_JSON`|
|answerHint|Tùy chọn, tối đa **4000** ký tự sau khi bỏ khoảng trắng đầu/cuối như `content`; xuống dòng ở giữa được giữ; không được chứa ký tự điều khiển trừ tab và xuống dòng. Bỏ trường, `null`, chuỗi rỗng hoặc chỉ có khoảng trắng đều được lưu là `null`|
|active|Tùy chọn, `true` hoặc `false`. Bỏ trường hoặc `null`: POST tạo câu hỏi đang dùng (`true`), PUT giữ giá trị hiện có. Gửi `false` để ngừng dùng câu hỏi mà vẫn giữ lại; gửi `true` để dùng lại|

"Khoảng trắng" ở đây là mọi ký tự PostgreSQL coi là `[[:space:]]` trong CHECK của V9: dấu cách, tab, xuống dòng và cả các khoảng trắng Unicode như khoảng trắng không ngắt (U+00A0, U+2007, U+202F) hay gặp khi dán chữ từ Word hoặc trang web. `String.strip()` của Java không bỏ các ký tự không ngắt này, nên `InterviewQuestionRequest` dùng hàm riêng. Khoảng trắng không ngắt nằm giữa câu được giữ nguyên. Nội dung chỉ gồm các ký tự này bị coi là rỗng (`VALIDATION_ERROR`).

**Ký tự điều khiển** (task 222) là nhóm `Cc` của Unicode: U+0000–U+001F và U+007F–U+009F, ví dụ NUL, chuông (U+0007), ESC (U+001B), DEL (U+007F). Chúng không nhìn thấy được khi hiển thị, và PostgreSQL không lưu được NUL (trước task 222 NUL làm request lỗi 500). Tab (U+0009), xuống dòng (U+000A) và về đầu dòng (U+000D) vẫn được dùng để trình bày câu hỏi dài. Lỗi trả `VALIDATION_ERROR`; `fieldErrors.content` là "Nội dung câu hỏi không được chứa ký tự điều khiển; chỉ dùng được xuống dòng và tab." (với `answerHint` là "Gợi ý câu trả lời không được chứa ký tự điều khiển; ...").

**Dạng chuẩn NFC** (task 222): một số trình soạn thảo gửi chữ có dấu như "ệ" thành chữ gốc cộng các dấu kết hợp (dạng NFD, 2–3 ký tự Java), số khác gửi thành một ký tự duy nhất (dạng NFC). Hai cách nhìn giống hệt nhau nhưng là hai chuỗi khác nhau. Backend chuyển `content` và `answerHint` sang NFC trước khi kiểm tra và lưu, nên cùng một câu luôn được lưu giống nhau và giới hạn 2000/4000 ký tự đếm theo chữ người dùng nhìn thấy. Response trả văn bản ở dạng NFC; nội dung hiển thị không đổi.

Trường ngoài hợp đồng, như `id`, `createdAt` hay `criterion`, bị từ chối với HTTP 400 `INVALID_JSON`.

PUT thay thế toàn bộ câu hỏi: gửi đủ `criterionId`, `content`, `difficulty`; bỏ `answerHint` hoặc gửi `null` nghĩa là xóa gợi ý cũ. Riêng `active` được giữ nguyên khi không gửi. PUT có thể **chuyển câu hỏi sang tiêu chí khác**, kể cả tiêu chí của khung khác; `id` và `createdAt` của câu hỏi giữ nguyên, `updatedAt` là thời điểm sửa. Đây cũng là cách gỡ câu hỏi khỏi một tiêu chí trước khi bỏ tiêu chí đó khỏi khung, vì [PUT khung](competency-frameworks.md#put-thay-thế-danh-sách-tiêu-chí-như-thế-nào) không xóa tiêu chí còn câu hỏi (409 `COMPETENCY_CRITERION_IN_USE`, kể cả câu hỏi `active = false`).

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

## Câu hỏi trùng nội dung

Task 222: một tiêu chí không được có hai câu hỏi cùng nội dung, kể cả khi câu hỏi cũ đang `active = false`. POST hoặc PUT tạo ra câu hỏi trùng trả **409** `INTERVIEW_QUESTION_DUPLICATE` và không thay đổi dữ liệu.

- **Thế nào là trùng**: hai nội dung được so sánh sau khi đổi về NFC, gộp mọi chuỗi khoảng trắng/tab/xuống dòng liền nhau thành một dấu cách và đổi về chữ thường. Vì vậy `Bạn xử lý xung đột trong nhóm thế nào?`, `BẠN XỬ LÝ xung đột` + xuống dòng + `trong   nhóm thế nào?` và cùng câu đó gửi ở dạng NFD là một câu. Khác dấu câu (thiếu `?`) hay khác chữ là câu khác.
- **Chỉ trong cùng tiêu chí**: cùng nội dung ở tiêu chí khác (kể cả cùng khung) vẫn được, vì một câu hỏi có thể dùng để đánh giá nhiều tiêu chí.
- **Câu hỏi đang ngừng dùng**: nếu chỉ có câu hỏi `active = false` trùng nội dung, `fieldErrors.content` gợi ý dùng lại câu hỏi đó (PUT với `"active": true`) thay vì tạo câu mới.
- **PUT câu hỏi của chính nó**: câu hỏi không bị coi là trùng với chính nó, nên sửa độ khó, gợi ý, `active` hoặc chỉ đổi chữ hoa/thường, khoảng trắng của nội dung đều được. PUT giữ nguyên tiêu chí và nội dung (theo cách so sánh trên) thì không kiểm tra trùng: dữ liệu trùng có từ trước task 222 (hoặc thêm bằng SQL) vẫn sửa được hoặc chuyển `active = false` được. Khi đã đổi sang nội dung khác, câu hỏi không lấy lại được nội dung đang có ở câu khác.
- **Chuyển tiêu chí**: PUT chuyển câu hỏi sang tiêu chí đã có câu cùng nội dung cũng trả 409.

```json
{
  "code": "INTERVIEW_QUESTION_DUPLICATE",
  "message": "Tiêu chí này đã có câu hỏi phỏng vấn cùng nội dung.",
  "fieldErrors": {
    "content": "Câu hỏi này đã có trong tiêu chí đã chọn."
  }
}
```

Khi chỉ trùng với câu hỏi đang ngừng dùng, `fieldErrors.content` là "Câu hỏi này đã có trong tiêu chí đã chọn nhưng đang ngừng dùng; hãy dùng lại câu hỏi đó (active = true)."

Database không có ràng buộc UNIQUE cho việc này (V9 cố ý không đặt, và cách so sánh trên khó viết thành chỉ mục); kiểm tra nằm ở `InterviewQuestionService`. Để hai request cùng lúc không cùng vượt qua kiểm tra, service khóa dòng tiêu chí (xem [Thứ tự kiểm tra và đồng thời](#thứ-tự-kiểm-tra-và-đồng-thời)).

## Đọc một câu hỏi

`GET /interview-questions/{id}`, trong đó `{id}` là UUID của câu hỏi. Không có body hay tham số. Trả **200** với response như trên, kể cả câu hỏi `active = false`. Câu hỏi, tiêu chí và khung được đọc trong cùng một snapshot (`REPEATABLE_READ`, chỉ đọc), nên luôn khớp nhau; lần đọc không chờ khi HR đang sửa dở mà trả bản đã commit gần nhất.

## Tìm kiếm và lọc (task 223)

`GET /interview-questions?q=xung%20đột&positionId=<uuid>&criterionId=<uuid>&difficulty=MEDIUM&active=true&page=0&size=20`

Mọi tham số đều tùy chọn. Không gửi tham số nào thì trả cả ngân hàng câu hỏi theo trang, kể cả câu hỏi đang ngừng dùng. Các bộ lọc gửi cùng lúc được kết hợp bằng "và": câu hỏi phải thỏa tất cả. Tham số có giá trị rỗng (ví dụ `difficulty=`) được coi như không gửi.

| Tham số | Ý nghĩa |
|---|---|
|q|Tìm một phần **nội dung câu hỏi** (`content`), không phân biệt chữ hoa/thường, kể cả chữ tiếng Việt có dấu (`BẠN ĐÃ XỬ LÝ` tìm thấy `bạn đã xử lý`). Không tìm trong gợi ý câu trả lời, tên tiêu chí hay tên/mã khung. `%`, `_` và `!` được hiểu là ký tự thật. Tối đa **255** ký tự sau khi chuẩn hóa (xem dưới bảng); không được chứa ký tự điều khiển. `q` rỗng hoặc chỉ có khoảng trắng nghĩa là không tìm theo nội dung|
|positionId|UUID chức danh. Chỉ trả câu hỏi thuộc các tiêu chí của khung năng lực mà chức danh **đang dùng** (`positions.competency_framework_id`), nên các chức danh dùng chung một khung nhận cùng kết quả và đổi khung của chức danh thì kết quả đổi ngay. Chức danh chưa gán khung trả trang rỗng (không phải lỗi). Chức danh đã ngừng dùng (`active = false`) vẫn lọc được. UUID không phải chức danh nào trả 400 `INVALID_POSITION`|
|criterionId|UUID tiêu chí. Chỉ trả câu hỏi gắn với tiêu chí này. UUID không phải tiêu chí nào (kể cả UUID của khung) trả 400 `INVALID_COMPETENCY_CRITERION`. Gửi cùng `positionId` mà tiêu chí không thuộc khung của chức danh thì trả trang rỗng|
|difficulty|`EASY`, `MEDIUM` hoặc `HARD`, viết hoa đúng như vậy; bỏ qua để lấy mọi mức|
|active|`true` chỉ lấy câu hỏi đang dùng, `false` chỉ lấy câu hỏi đã ngừng dùng; bỏ qua để lấy cả hai. Màn hình chọn câu hỏi để phỏng vấn nên gửi `active=true`|
|page|Từ 0, mặc định 0|
|size|Từ 1 đến 100, mặc định 20|

**Chuẩn hóa `q`.** Trước khi so sánh, backend đổi `q` sang dạng NFC (giống khi lưu câu hỏi, task 222), gộp mọi chuỗi khoảng trắng, tab, xuống dòng liền nhau thành một dấu cách và bỏ khoảng trắng ở đầu/cuối. Nội dung câu hỏi cũng được gộp khoảng trắng như vậy khi so sánh, nên `xử lý xung đột` tìm thấy câu hỏi xuống dòng giữa "xử lý" và "xung đột", và chữ có dấu gửi ở dạng NFD vẫn tìm thấy câu hỏi đã lưu ở dạng NFC. Giới hạn 255 ký tự được đếm sau bước này. Ký tự điều khiển (nhóm `Cc` của Unicode như NUL, ESC, DEL) bị từ chối; tab và xuống dòng không bị từ chối vì đã thành dấu cách.

Response **200**: `{items, page, size, totalElements, totalPages}`. Mỗi phần tử của `items` có đúng dạng response của `GET /interview-questions/{id}`, nên màn hình hiển thị được tiêu chí, khung, gợi ý câu trả lời mà không cần gọi thêm.

```json
{
  "items": [
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
      "content": "Bạn đã xử lý\nxung đột trong nhóm thế nào?",
      "difficulty": "MEDIUM",
      "answerHint": "Nêu rõ cách lắng nghe.",
      "active": true,
      "createdAt": "2026-10-07T08:00:00Z",
      "updatedAt": "2026-10-07T08:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

**Sắp xếp.** Theo mã khung (`framework.code`), rồi thứ tự tiêu chí trong khung (`sortOrder` do HR đặt), rồi câu hỏi tạo trước đứng trước (`createdAt`), cuối cùng theo UUID để hai câu hỏi tạo cùng lúc vẫn có thứ tự cố định. Câu hỏi của cùng một tiêu chí vì vậy nằm cạnh nhau, và khi dữ liệu không đổi thì các trang không trùng, không sót câu hỏi. HR đổi thứ tự tiêu chí trong khung (PUT khung) thì thứ tự danh sách đổi theo. Trang vượt quá số trang có `items` rỗng nhưng vẫn trả đúng `totalElements`, `totalPages`.

**Thứ tự kiểm tra.**

1. Token và quyền `ORGANIZATION_READ_ALL` (401/403). Người thiếu quyền luôn nhận 403, kể cả khi tham số sai.
2. Giá trị không đổi được sang kiểu cần có: `positionId`/`criterionId` không phải UUID, `difficulty` không phải `EASY`/`MEDIUM`/`HARD` (kể cả `easy`), `active` không phải `true`/`false`, `page`/`size` không phải số nguyên. Trả 400 `VALIDATION_ERROR` với message "Tham số đường dẫn hoặc bộ lọc không hợp lệ." và `fieldErrors` là đối tượng rỗng `{}` (khóa vẫn có, chỉ không nêu tham số nào sai).
3. `q`, `page`, `size` ngoài giới hạn: 400 `VALIDATION_ERROR`, mọi tham số sai được nêu cùng lúc trong `fieldErrors` (khóa `q`, `page`, `size`).
4. `positionId` không phải chức danh nào: 400 `INVALID_POSITION`.
5. `criterionId` không phải tiêu chí nào: 400 `INVALID_COMPETENCY_CRITERION`.

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Trang, số lượng hoặc từ khóa tìm kiếm câu hỏi phỏng vấn không hợp lệ.",
  "fieldErrors": {
    "q": "Từ khóa tìm kiếm tối đa 255 ký tự.",
    "page": "Số trang phải từ 0 trở lên.",
    "size": "Số câu hỏi mỗi trang phải từ 1 đến 100."
  }
}
```

`q` có ký tự điều khiển cho `fieldErrors.q` là "Từ khóa tìm kiếm không được chứa ký tự điều khiển.". Chức danh không tồn tại:

```json
{
  "code": "INVALID_POSITION",
  "message": "Chức danh không tồn tại.",
  "fieldErrors": {
    "positionId": "Không tìm thấy chức danh này."
  }
}
```

**Đồng thời.** Lần tìm chạy trong transaction chỉ đọc `REPEATABLE_READ`: kiểm tra chức danh/tiêu chí, đếm tổng và lấy trang đều từ cùng một snapshot, nên `totalElements` luôn khớp với các trang. Lần tìm không khóa dòng nào và không chờ người đang sửa khung, chức danh hay câu hỏi: nó trả bản đã commit gần nhất và không thấy thay đổi chưa lưu.

**Giới hạn đã biết.** Tìm theo `q` dùng `LIKE '%...%'` trên nội dung đã gộp khoảng trắng, nên PostgreSQL phải đọc lần lượt các câu hỏi còn lại sau các bộ lọc khác (không dùng được index); chấp nhận được với ngân hàng vài nghìn câu hỏi. Không phân biệt hoa/thường dựa vào hàm `lower()` của PostgreSQL, phụ thuộc locale của database: image `postgres:17` trong `devops/docker/compose.yaml` mặc định dùng `en_US.utf8` và PostgreSQL nhúng của test đều đổi đúng chữ tiếng Việt có dấu; một database tạo với locale `C` chỉ đổi được chữ ASCII.

## Thứ tự kiểm tra và đồng thời

1. Token và quyền (401/403), rồi từng trường riêng lẻ (`VALIDATION_ERROR`, mọi trường sai được trả cùng lúc trong `fieldErrors`) hoặc JSON sai (`INVALID_JSON`).
2. PUT: câu hỏi phải tồn tại (404 `INTERVIEW_QUESTION_NOT_FOUND`). Service khóa dòng câu hỏi (`SELECT ... FOR UPDATE`), nên hai người sửa cùng một câu hỏi được xử lý lần lượt. Không có kiểm tra phiên bản (optimistic lock): người lưu sau ghi đè thay đổi của người lưu trước.
3. Tiêu chí phải tồn tại (400 `INVALID_COMPETENCY_CRITERION`). Service tìm khung của tiêu chí, khóa dòng khung bằng `SELECT ... FOR SHARE`, rồi đọc lại tiêu chí và khóa dòng tiêu chí bằng `SELECT ... FOR UPDATE` (task 222).
4. Tiêu chí chưa có câu hỏi khác cùng nội dung (409 `INTERVIEW_QUESTION_DUPLICATE`, task 222).
5. Ghi câu hỏi trong cùng transaction.

Khóa `FOR SHARE` trên dòng khung (giống khi gán khung cho chức danh, task 214) giữ cho tiêu chí không bị xóa giữa lúc kiểm và lúc lưu:

- Nếu HR đang sửa khung (PUT khung giữ khóa `FOR UPDATE`), lần ghi câu hỏi chờ PUT khung commit rồi mới đọc lại tiêu chí. Tiêu chí vừa bị PUT khung xóa trả 400 `INVALID_COMPETENCY_CRITERION`, không phải 500.
- Nếu một lần ghi câu hỏi đang chạy, PUT khung chờ nó commit; sau đó PUT khung thấy câu hỏi mới và không xóa tiêu chí của nó (409 `COMPETENCY_CRITERION_IN_USE`).
- Nhiều lần ghi câu hỏi trên cùng một khung dùng chung khóa khung nên không chờ nhau, miễn là khác tiêu chí.
- Hai lần ghi câu hỏi trên **cùng một tiêu chí** chạy lần lượt nhờ khóa dòng tiêu chí (task 222). Lần ghi sau chờ lần trước commit rồi mới kiểm tra trùng, nên thấy câu hỏi vừa lưu: hai người cùng thêm một câu vào cùng tiêu chí thì người sau nhận 409, không có hai bản.
- Tiêu chí bị xóa bằng cách khác (sửa trực tiếp bằng SQL, không khóa khung): khóa dòng tiêu chí chờ lệnh xóa commit rồi không tìm thấy tiêu chí, trả cùng mã 400. Khóa ngoại V9 vẫn là lớp chặn cuối; nếu nó từ chối thì service cũng đổi thành 400.

Mọi lỗi đều không thay đổi dữ liệu.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu `criterionId`, `content` hoặc `difficulty`; `content` rỗng/chỉ khoảng trắng; `content` quá 2000 hoặc `answerHint` quá 4000 ký tự; `content`/`answerHint` có ký tự điều khiển (trừ tab, xuống dòng); `difficulty` không phải `"EASY"`/`"MEDIUM"`/`"HARD"`; UUID trên đường dẫn sai. Hiếm hơn: database dùng locale coi thêm một ký tự khác là khoảng trắng nên CHECK của V9 vẫn từ chối `content`/`answerHint` (`23514`); khi đó `fieldErrors` nêu đúng trường đó thay vì trả 500. Tìm kiếm (task 223): `q` quá 255 ký tự hoặc có ký tự điều khiển, `page` âm, `size` ngoài 1–100 (`fieldErrors.q`/`page`/`size`); `positionId`/`criterionId` không phải UUID, `difficulty`/`active`/`page`/`size` sai kiểu|
|400|INVALID_JSON|JSON sai, `criterionId` không phải UUID, `difficulty` là mảng hoặc đối tượng JSON, `active` không phải true/false, hoặc có trường ngoài hợp đồng|
|400|INVALID_COMPETENCY_CRITERION|`criterionId` không phải tiêu chí nào đang có (kể cả tiêu chí vừa bị xóa trong lúc chờ), ở body của POST/PUT hoặc ở tham số tìm kiếm (task 223); `fieldErrors.criterionId`|
|400|INVALID_POSITION|Tìm kiếm (task 223): `positionId` không phải chức danh nào; `fieldErrors.positionId`|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Đọc thiếu `ORGANIZATION_READ_ALL`; ghi thiếu `ORGANIZATION_WRITE_ALL`; hoặc gọi URL chưa có như `DELETE /interview-questions/{id}`|
|404|INTERVIEW_QUESTION_NOT_FOUND|Không tìm thấy câu hỏi (GET, PUT)|
|409|INTERVIEW_QUESTION_DUPLICATE|Tiêu chí đã có câu hỏi khác cùng nội dung (kể cả câu đang ngừng dùng); `fieldErrors.content`|

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

## Quyết định cần BA/PO xác nhận

Task 221:

- Database (V9) lưu `content` và `answerHint` bằng `TEXT` không giới hạn; API đặt giới hạn 2000 và 4000 ký tự để chặn dữ liệu quá lớn.
- Câu hỏi gắn được với tiêu chí của khung `DRAFT`, để HR soạn câu hỏi trong lúc khung còn là bản nháp. Task 222 giữ quyết định này: chỉ khi gán khung cho chức danh (task 214) khung mới phải `ACTIVE`.

Task 222:

- Không cho hai câu hỏi trùng nội dung trong cùng một tiêu chí (409); cùng nội dung ở tiêu chí khác vẫn được. Không phân biệt chữ hoa/thường, khoảng trắng giữa các từ và cách mã hóa dấu tiếng Việt; có phân biệt dấu câu.
- Câu hỏi `active = false` vẫn được tính khi kiểm tra trùng.
- Sai mức độ khó là lỗi theo trường (`VALIDATION_ERROR`) thay vì `INVALID_JSON` như task 221, để form biết đúng ô cần sửa.
- Từ chối ký tự điều khiển trừ tab và xuống dòng; lưu văn bản ở dạng NFC.

Task 223:

- `q` chỉ tìm trong nội dung câu hỏi, không tìm trong gợi ý câu trả lời, tên tiêu chí hay tên khung.
- Không gửi `active` thì trả cả câu hỏi đang ngừng dùng, giống danh sách phòng ban, chức danh; người phỏng vấn cũng thấy các câu hỏi này. Màn hình chọn câu hỏi để phỏng vấn cần gửi `active=true`.
- `positionId` hoặc `criterionId` không tồn tại trả 400 thay vì trang rỗng, để màn hình phân biệt "chức danh/tiêu chí không còn" với "chưa có câu hỏi". Chức danh chưa gán khung năng lực trả trang rỗng.
- Sắp xếp theo khung, thứ tự tiêu chí rồi thời điểm tạo câu hỏi; chưa có tùy chọn sắp xếp khác.
- `q` tối đa 255 ký tự như các danh sách khác.

## Database và phạm vi

Dùng bảng `interview_questions` của V9 (task 220), `competency_criteria` và `competency_frameworks` của V8, quyền ORGANIZATION của V3. Task 221, 222 và 223 không thêm migration, mã quyền hay dòng cấp quyền và không cần sửa `.env`. Mã nguồn ở gói `vn.ttcs.recruitment.interviewquestion` (`InterviewQuestionController`, `InterviewQuestionService`, `InterviewQuestionRequest`, `InterviewQuestionView`); task 222 thêm `CompetencyCriterionRepository.findByIdForUpdate` để khóa dòng tiêu chí. Task 223 thêm `InterviewQuestionSearchRepository` (SQL thuần qua `NamedParameterJdbcTemplate`, đọc thêm cột `positions.competency_framework_id`), `InterviewQuestionPage` và phương thức `list` của controller/service.

Chưa có: xóa câu hỏi, phiếu đánh giá phỏng vấn (Sprint 6).
