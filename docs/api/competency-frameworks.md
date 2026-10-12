# API khung năng lực

Phạm vi TKNHTTDNB1-212 "Xây dựng API quản lý khung năng lực" (tạo, sửa và đọc khung cùng bộ tiêu chí đánh giá), TKNHTTDNB1-213 "Kiểm tra tổng trọng số khung năng lực bằng 100%" và TKNHTTDNB1-214 "Xử lý dùng lại khung năng lực cho nhiều chức danh" (trường `positions` của chi tiết khung), story TKNHTTDNB1-25 (S2-06). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi nghiệp vụ của nhóm này (`COMPETENCY_*`, `INVALID_COMPETENCY_CRITERION`) dùng `Cache-Control: no-store`.

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi, service khóa tài khoản người gọi rồi phiên, kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền, sau đó mới khóa khung năng lực.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /competency-frameworks`, `GET /competency-frameworks/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ (kể cả INTERVIEWER, vì người phỏng vấn chấm theo các tiêu chí này)|
|`POST /competency-frameworks`, `PUT /competency-frameworks/{id}`|`ORGANIZATION_WRITE_ALL`|ADMIN, HR_MANAGER|

Người không có quyền ghi luôn nhận 403, kể cả khi body sai, vì quyền được kiểm tra trước dữ liệu.

## Khái niệm

- **Khung năng lực** là một bộ tiêu chí đánh giá dùng lại được: nhiều chức danh trỏ tới cùng một khung qua cột `positions.competency_framework_id` (V8), tiêu chí chỉ lưu một lần và không bị sao chép theo từng chức danh. Sửa khung là sửa cho mọi chức danh đang dùng khung đó. Gán hoặc bỏ khung của một chức danh bằng `PUT`/`DELETE /positions/{id}/competency-framework` (task 214, chỉ gán được khung `ACTIVE`), mô tả ở [API chức danh](positions.md#khung-năng-lực-của-chức-danh-task-214); chi tiết khung liệt kê các chức danh đang dùng nó trong trường `positions`.
- **Tiêu chí** có tên, mô tả tùy chọn, trọng số phần trăm và thứ tự hiển thị. Đây là bộ tiêu chí sẽ sinh phiếu đánh giá phỏng vấn ở Sprint 6.
- **Trạng thái** `DRAFT` (bản nháp đang soạn: tổng trọng số có thể chưa đủ hoặc vượt 100) hoặc `ACTIVE` (khung hoàn chỉnh: tổng trọng số **đúng 100%**). Request có trường `status` tùy chọn; gửi `"ACTIVE"` để lưu khung hoàn chỉnh. Quy tắc nằm ở mục [Khung hoàn chỉnh và tổng trọng số 100%](#khung-hoàn-chỉnh-và-tổng-trọng-số-100) (task 213).

## Tạo và sửa

`POST /competency-frameworks` tạo khung, trả **201**. `PUT /competency-frameworks/{id}` thay thế toàn bộ khung có UUID tương ứng, **kể cả toàn bộ danh sách tiêu chí**, trả **200**.

```json
{
  "code": "DEV_CORE",
  "name": "Năng lực lập trình viên",
  "description": "Dùng cho mọi cấp lập trình viên",
  "criteria": [
    { "name": "Kỹ năng lập trình", "description": "Viết mã đúng và dễ đọc", "weight": 40 },
    { "name": "Thiết kế hệ thống", "weight": 35.5 },
    { "name": "Làm việc nhóm", "weight": 24.5 }
  ]
}
```

| Trường của khung | Quy tắc |
|---|---|
|code|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự; duy nhất và phân biệt hoa/thường (`HR` khác `hr`), giống mã chức danh|
|name|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự|
|description|Tùy chọn, tối đa 1000 ký tự sau khi bỏ khoảng trắng đầu/cuối (kể cả tab, xuống dòng); xuống dòng ở giữa được giữ. Bỏ trường, `null`, chuỗi rỗng hoặc chỉ có khoảng trắng đều được lưu là `null`|
|status|Tùy chọn, là chuỗi JSON `"DRAFT"` hoặc `"ACTIVE"` (viết hoa đúng như vậy). Bỏ trường hoặc `null`: POST tạo `DRAFT`, PUT giữ trạng thái hiện có. Giá trị khác như `"active"`, `""`, số `1`, `true` trả `INVALID_JSON`. `ACTIVE` bắt buộc tổng trọng số đúng 100% (xem bên dưới)|
|criteria|Bắt buộc, là mảng; `[]` hợp lệ (khung `DRAFT` có thể chưa có tiêu chí); tối đa 50 tiêu chí; phần tử không được là `null`|

| Trường của một tiêu chí | Quy tắc |
|---|---|
|id|Bỏ trống với tiêu chí mới. Gửi `id` của tiêu chí đang có trong **chính khung này** để giữ tiêu chí đó (xem bên dưới). POST không nhận `id` nào, vì khung mới chưa có tiêu chí|
|name|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự; không trùng tên tiêu chí khác trong cùng khung (so chính xác, phân biệt hoa/thường như ràng buộc database: `Giao tiếp` khác `giao tiếp`). Hai khung khác nhau được có tiêu chí cùng tên|
|description|Như mô tả của khung|
|weight|Trọng số phần trăm, bắt buộc, phải là **số JSON**, lớn hơn 0 và không quá 100, tối đa 2 chữ số thập phân|

Không có trường `sortOrder` trong request: **thứ tự trong mảng chính là thứ tự tiêu chí** (phần tử đầu có `sortOrder` 1, tiếp theo 2, 3...). Muốn đổi thứ tự thì gửi mảng theo thứ tự mới. Trường ngoài hợp đồng, như `id` hay `createdAt` của khung hoặc `sortOrder` của tiêu chí, bị từ chối với HTTP 400 `INVALID_JSON`.

### Trọng số

Trọng số lưu `NUMERIC(5,2)` và Java dùng `BigDecimal`, không dùng số thực, nên `33.33` được lưu và trả về đúng `33.33`.

- Hợp lệ: `0.01` đến `100`. Số không thừa chữ số thập phân có nghĩa như `40.000` (= 40) hoặc dạng mũ `1e1` (= 10) được chấp nhận.
- Không hợp lệ (`VALIDATION_ERROR`, lỗi ở trường `criteria[i].weight`): thiếu hoặc `null`, `0`, số âm, lớn hơn `100`, có chữ số thập phân thứ ba khác 0 như `33.335` hoặc `0.001`. Backend từ chối thay vì để PostgreSQL tự làm tròn `33.335` thành `33.34`.
- Không phải số JSON (`"40"`, `true`, `{}`, `[40]`) trả `INVALID_JSON`.
- Response luôn trả đúng 2 chữ số thập phân như database lưu: gửi `40` nhận `40.00`, gửi `35.5` nhận `35.50`.

### Khung hoàn chỉnh và tổng trọng số 100%

Task 213: một khung là **hoàn chỉnh** khi có trạng thái `ACTIVE`. Phiếu đánh giá phỏng vấn (Sprint 6) chấm theo khung hoàn chỉnh, nên backend **từ chối lưu khung `ACTIVE` khi tổng trọng số các tiêu chí khác 100%**. Khung `DRAFT` không bị kiểm tổng.

Tổng được tính trên **mảng `criteria` trong request** (chính là danh sách sau khi lưu), cộng bằng `BigDecimal` nên chính xác tới 2 chữ số thập phân, và phải bằng đúng 100 (`100`, `100.0`, `100.00` như nhau):

| Trọng số gửi lên | Tổng | Khung `ACTIVE` |
|---|---|---|
|`33.33`, `33.33`, `33.34`|100.00|Hợp lệ|
|`100`|100.00|Hợp lệ (một tiêu chí)|
|`5e1`, `50.000`|100.00|Hợp lệ|
|`33.33`, `33.33`, `33.33`|99.99|400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`|
|`60`, `40.01`|100.01|400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`|
|`[]` (không có tiêu chí)|0.00|400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`|
|`33.335`, `66.665`|Không tính|400 `VALIDATION_ERROR` ở từng dòng, vì chữ số thập phân thứ ba bị từ chối trước khi cộng|

Vì mỗi trọng số lớn hơn 0 và tổng phải là 100, khung hoàn chỉnh luôn có ít nhất một tiêu chí.

Trạng thái sau khi lưu:

| Yêu cầu | `status` trong request | Kết quả |
|---|---|---|
|POST|bỏ trống, `null` hoặc `"DRAFT"`|Tạo khung `DRAFT`, không kiểm tổng|
|POST|`"ACTIVE"`|Tạo khung `ACTIVE` nếu tổng đúng 100|
|PUT khung đang `DRAFT`|bỏ trống, `null` hoặc `"DRAFT"`|Vẫn `DRAFT`, không kiểm tổng|
|PUT khung đang `DRAFT`|`"ACTIVE"`|Chuyển sang `ACTIVE` nếu tổng đúng 100|
|PUT khung đang `ACTIVE`|bỏ trống, `null` hoặc `"ACTIVE"`|Vẫn `ACTIVE`; **mọi lần sửa phải giữ tổng đúng 100**|
|PUT khung đang `ACTIVE`|`"DRAFT"`|409 `COMPETENCY_FRAMEWORK_ALREADY_ACTIVE`, không thay đổi gì|

Muốn thêm, bỏ hoặc đổi trọng số của khung `ACTIVE` thì gửi một PUT có cả danh sách tiêu chí mới đã cân lại đủ 100%; không cần (và không thể) chuyển về `DRAFT` trước. **Quyết định tạm thời của task 213:** khung `ACTIVE` không quay lại `DRAFT`, vì chức danh (task 214) và phiếu đánh giá sau này dựa vào khung luôn hoàn chỉnh. Nhờ quy tắc này, khung đang được chức danh dùng (chỉ khung `ACTIVE` mới gán được) không bao giờ trở lại trạng thái thiếu trọng số. Nếu nghiệp vụ cần "ngừng dùng" một khung, BA/PO cần bổ sung yêu cầu riêng.

Ví dụ kích hoạt khung đang có hai tiêu chí (gửi lại `id` của cả hai để giữ chúng):

```json
{
  "code": "DEV_CORE",
  "name": "Năng lực lập trình viên",
  "status": "ACTIVE",
  "criteria": [
    { "id": "00000000-0000-0000-0000-000000000011", "name": "Kỹ năng lập trình", "weight": 50.5 },
    { "id": "00000000-0000-0000-0000-000000000012", "name": "Thiết kế hệ thống", "weight": 49.5 }
  ]
}
```

Lỗi khi tổng khác 100 (ví dụ 99.99); `message` và `fieldErrors.criteria` đều nêu tổng hiện tại với 2 chữ số thập phân:

```json
{
  "code": "COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID",
  "message": "Khung năng lực hoàn chỉnh (ACTIVE) cần tổng trọng số các tiêu chí đúng 100%; tổng hiện tại là 99.99%.",
  "fieldErrors": {
    "criteria": "Tổng trọng số hiện tại là 99.99%, cần đúng 100%."
  }
}
```

Database không có CHECK cho tổng trọng số (V8 giữ nguyên); quy tắc chỉ nằm ở `CompetencyFrameworkService`. Dữ liệu sửa trực tiếp bằng SQL không đi qua quy tắc này.

### PUT thay thế danh sách tiêu chí như thế nào

PUT làm cho danh sách tiêu chí đã lưu **giống hệt** mảng `criteria` trong request:

1. Phần tử có `id`: cập nhật tiêu chí đó (tên, mô tả, trọng số, thứ tự) và **giữ nguyên `id`**.
2. Phần tử không có `id`: tạo tiêu chí mới với `id` mới.
3. Tiêu chí đang có nhưng không xuất hiện trong mảng: **bị xóa**.

Vì câu hỏi phỏng vấn (task 221) và phiếu đánh giá sau này sẽ trỏ tới tiêu chí theo `id`, frontend phải gửi lại `id` của mọi tiêu chí muốn giữ. Gửi lại cùng tên nhưng không kèm `id` nghĩa là xóa tiêu chí cũ và tạo tiêu chí mới có `id` khác. **Quyết định tạm thời của task 212:** đây là thay thế đơn giản, chưa chặn việc xóa tiêu chí đã có câu hỏi phỏng vấn; quy tắc "tiêu chí đang được câu hỏi dùng thì không được xóa" sẽ được bổ sung khi có bảng câu hỏi.

Cách làm an toàn ở frontend: `GET /competency-frameworks/{id}`, sửa trên dữ liệu vừa đọc, rồi PUT đủ mọi trường. Một lần PUT có thể đổi chỗ tên hoặc thứ tự của hai tiêu chí (ví dụ đổi tên A thành B và B thành A) vì database chỉ kiểm trùng trên kết quả cuối cùng. Đổi `code`, `name`, `description` của khung không làm đổi `id` của khung hay `createdAt`.

### Thứ tự kiểm tra và ghi đồng thời

1. Quyền (403) và từng trường riêng lẻ (`VALIDATION_ERROR`, mọi trường sai được trả cùng lúc trong `fieldErrors`, ví dụ `criteria[2].name`).
2. PUT: khung phải tồn tại (404). Service khóa dòng khung (`SELECT ... FOR UPDATE`) rồi mới đọc các tiêu chí hiện có.
3. `id` của tiêu chí phải thuộc khung và chỉ xuất hiện một lần (400 `INVALID_COMPETENCY_CRITERION`).
4. Tên tiêu chí không trùng trong danh sách (409 `COMPETENCY_CRITERION_NAME_DUPLICATE`).
5. PUT: khung đang `ACTIVE` không chuyển về `DRAFT` (409 `COMPETENCY_FRAMEWORK_ALREADY_ACTIVE`).
6. Nếu trạng thái sau khi lưu là `ACTIVE`: tổng trọng số đúng 100 (400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`).
7. Mã khung không trùng khung khác (409 `COMPETENCY_FRAMEWORK_CODE_EXISTS`).
8. Ghi, rồi kiểm ngay hai ràng buộc UNIQUE "kiểm lúc COMMIT" của V8 (`checkUniqueConstraintsNow`) để lỗi trùng còn sót vẫn thành 409 thay vì 500.

Một lần gán khung cho chức danh (task 214) đọc khung bằng `SELECT ... FOR SHARE`, nên PUT khung chờ lần gán đang chạy commit, và lần gán đến trong lúc PUT khung đang chạy cũng chờ rồi đọc trạng thái mới nhất. Hai người sửa cùng một khung cùng lúc sẽ được xử lý lần lượt: người sau chờ người trước commit, rồi kiểm và ghi trên dữ liệu mới nhất. Không có kiểm tra phiên bản (optimistic lock), nên người lưu sau ghi đè thay đổi của người lưu trước; nếu người sau vẫn gửi `id` của tiêu chí người trước vừa xóa, yêu cầu bị từ chối với 400 `INVALID_COMPETENCY_CRITERION` và không có gì thay đổi. Trạng thái cũng được đọc sau khi khóa: nếu người trước vừa chuyển khung sang `ACTIVE`, PUT không gửi `status` của người sau sẽ giữ `ACTIVE` nên phải có tổng đúng 100, nếu không nhận 400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`. Hai yêu cầu tạo/sửa cùng một mã khung đồng thời: một yêu cầu thành công, yêu cầu còn lại nhận 409.

Response của tạo/sửa và `GET /competency-frameworks/{id}`:

```json
{
  "id": "00000000-0000-0000-0000-000000000010",
  "code": "DEV_CORE",
  "name": "Năng lực lập trình viên",
  "description": "Dùng cho mọi cấp lập trình viên",
  "status": "DRAFT",
  "criteria": [
    {
      "id": "00000000-0000-0000-0000-000000000011",
      "name": "Kỹ năng lập trình",
      "description": "Viết mã đúng và dễ đọc",
      "weight": 40.00,
      "sortOrder": 1
    },
    {
      "id": "00000000-0000-0000-0000-000000000012",
      "name": "Thiết kế hệ thống",
      "description": null,
      "weight": 35.50,
      "sortOrder": 2
    },
    {
      "id": "00000000-0000-0000-0000-000000000013",
      "name": "Làm việc nhóm",
      "description": null,
      "weight": 24.50,
      "sortOrder": 3
    }
  ],
  "positions": [
    {
      "id": "00000000-0000-0000-0000-000000000003",
      "code": "DEV_JUNIOR",
      "name": "Lập trình viên",
      "level": "Junior",
      "active": true
    },
    {
      "id": "00000000-0000-0000-0000-000000000004",
      "code": "DEV_SENIOR",
      "name": "Lập trình viên",
      "level": "Senior",
      "active": true
    }
  ],
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa. `criteria` sắp theo `sortOrder`. `positions` (task 214) là các chức danh đang trỏ tới khung này, sắp theo code rồi UUID; mọi chức danh trong danh sách dùng chung đúng các tiêu chí ở `criteria` (cùng `id`), không có bản sao. Mỗi phần tử chỉ có `id`, `code`, `name`, `level`, `active`, **không có dải lương**, vì mọi người có quyền đọc khung đều thấy danh sách này; dải lương xem ở `GET /positions/{id}` theo quyền riêng. Khung chưa được chức danh nào dùng (kể cả khung vừa tạo) có `positions: []`. Danh sách khung (`GET /competency-frameworks`) không có trường này. `createdAt` giữ nguyên khi sửa; `updatedAt` là thời điểm ghi gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu).

## Danh sách

`GET /competency-frameworks?q=dev&status=DRAFT&page=0&size=20`

| Tham số | Ý nghĩa |
|---|---|
|q|Tìm một phần code/name, không phân biệt hoa/thường, tối đa 255 ký tự; %, _ và ! được hiểu là ký tự thật; không tìm theo mô tả hay tên tiêu chí|
|status|`DRAFT` hoặc `ACTIVE` (viết hoa đúng như vậy); bỏ qua để lấy cả hai|
|page|Từ 0, mặc định 0|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`. Mỗi item là khung **không kèm danh sách tiêu chí**, chỉ có số tiêu chí; muốn xem tiêu chí thì gọi chi tiết.

```json
{
  "id": "00000000-0000-0000-0000-000000000010",
  "code": "DEV_CORE",
  "name": "Năng lực lập trình viên",
  "description": "Dùng cho mọi cấp lập trình viên",
  "status": "DRAFT",
  "criterionCount": 3,
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

Sắp xếp theo code rồi UUID để phân trang ổn định; trang ngoài phạm vi có `items` rỗng.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, trọng số ngoài (0, 100] hoặc quá 2 chữ số thập phân, quá 50 tiêu chí, UUID trên đường dẫn, page/size, status hoặc q quá dài; lỗi theo trường nằm trong `fieldErrors`|
|400|INVALID_JSON|JSON sai, trọng số không phải số JSON, `status` không phải chuỗi `"DRAFT"`/`"ACTIVE"`, hoặc có trường ngoài hợp đồng|
|400|INVALID_COMPETENCY_CRITERION|`id` tiêu chí không thuộc khung này (kể cả mọi `id` khi POST) hoặc một `id` được gửi hai lần; `fieldErrors` chỉ ra dòng, ví dụ `criteria[1].id`|
|400|COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID|Khung có trạng thái `ACTIVE` sau lần ghi nhưng tổng trọng số khác 100 (kể cả khi không có tiêu chí); `message` và `fieldErrors.criteria` nêu tổng hiện tại|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Đọc thiếu `ORGANIZATION_READ_ALL`; ghi thiếu `ORGANIZATION_WRITE_ALL`|
|404|COMPETENCY_FRAMEWORK_NOT_FOUND|Không tìm thấy khung đích|
|409|COMPETENCY_FRAMEWORK_CODE_EXISTS|Mã đã được khung khác dùng, kể cả khi hai yêu cầu ghi cùng mã đồng thời|
|409|COMPETENCY_CRITERION_NAME_DUPLICATE|Hai tiêu chí trong cùng khung trùng tên (sau khi bỏ khoảng trắng đầu/cuối); `fieldErrors` chỉ ra từng dòng bị trùng|
|409|COMPETENCY_FRAMEWORK_ALREADY_ACTIVE|PUT gửi `"status": "DRAFT"` cho khung đang `ACTIVE`; `fieldErrors.status`|

Ví dụ lỗi trùng tên tiêu chí:

```json
{
  "code": "COMPETENCY_CRITERION_NAME_DUPLICATE",
  "message": "Tên tiêu chí trong một khung năng lực không được trùng nhau.",
  "fieldErrors": {
    "criteria[2].name": "Tên tiêu chí đã có ở dòng khác trong khung."
  }
}
```

Mọi lỗi đều không thay đổi dữ liệu: cả khung lẫn danh sách tiêu chí được ghi trong một transaction.

## Database và phạm vi

Dùng bảng `competency_frameworks`, `competency_criteria` của V8 (task 211) và quyền ORGANIZATION của V3; task 212 và 213 không thêm migration, không thêm mã quyền, không thêm endpoint và không cần sửa `.env`. Task 214 cũng không thêm migration hay mã quyền: chi tiết khung đọc thêm cột `positions.competency_framework_id` của V8, còn hai endpoint gán/bỏ khung nằm ở [API chức danh](positions.md#khung-năng-lực-của-chức-danh-task-214). Service làm đủ ba bước ghi tiêu chí mà [tài liệu database](../database/README.md) yêu cầu: khóa dòng khung, kiểm trùng trên danh sách cuối cùng, rồi gọi `checkUniqueConstraintsNow()` và đổi lỗi trùng thành 409.

Chưa có: DELETE khung (khung đang được chức danh dùng cũng không xóa được nhờ khóa ngoại `ON DELETE RESTRICT`), ngừng dùng khung `ACTIVE`, dữ liệu cho phiếu đánh giá (task 215) và câu hỏi phỏng vấn (task 220–223).
