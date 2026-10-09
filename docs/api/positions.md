# API danh mục chức danh

Phạm vi TKNHTTDNB1-203 (API), TKNHTTDNB1-204 (kiểm tra dữ liệu, giới hạn dải lương), TKNHTTDNB1-205 (phân quyền xem dải lương) và TKNHTTDNB1-206 (dữ liệu dải lương chuẩn cho kiểm tra hạn mức offer, không có API mới), story TKNHTTDNB1-24; cùng TKNHTTDNB1-214 (gán khung năng lực dùng chung cho chức danh, story TKNHTTDNB1-25), xem mục [Khung năng lực của chức danh](#khung-năng-lực-của-chức-danh-task-214). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi `POSITION_*` dùng `Cache-Control: no-store`.

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu và kiểm lại phiên/quyền sau khi khóa tài khoản người gọi khi ghi.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /positions`, `GET /positions/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|Thấy `salaryMin`/`salaryMax` trong response|Thêm `SALARY_RANGES_READ_ALL`|Chỉ HR_MANAGER|
|`POST /positions`, `PUT /positions/{id}`|`ORGANIZATION_WRITE_ALL` **và** `SALARY_RANGES_WRITE_ALL`|Chỉ HR_MANAGER|
|`PUT /positions/{id}/competency-framework`, `DELETE /positions/{id}/competency-framework`|`ORGANIZATION_WRITE_ALL` (không cần quyền dải lương)|ADMIN, HR_MANAGER|

## Ai được xem dải lương

Tiêu chí của story 24: "chỉ Trưởng phòng Nhân sự xem được dải lương". Migration V7_1 thêm module quyền `SALARY_RANGES` và chỉ cấp `SALARY_RANGES_READ_ALL`, `SALARY_RANGES_WRITE_ALL` cho HR_MANAGER. ADMIN cố ý **không** được cấp, chờ BA/PO trả lời câu hỏi 4 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md).

Dải lương bị loại ngay trên server, không chỉ ẩn trên giao diện:

- Người gọi có `SALARY_RANGES_READ_ALL`: mỗi chức danh có đủ 10 trường như ví dụ bên dưới.
- Người gọi không có quyền này: response **không có hai khóa** `salaryMin` và `salaryMax` (không phải giá trị `null` hay `0`), chỉ còn 8 trường `id`, `code`, `name`, `level`, `active`, `competencyFrameworkId`, `createdAt`, `updatedAt`. Áp dụng cho `GET /positions/{id}`, từng phần tử `items` của `GET /positions` và response của POST/PUT, kể cả hai API gán/bỏ khung năng lực.
- `SALARY_RANGES_READ_SCOPED` chưa có ý nghĩa nghiệp vụ và chưa vai trò nào được cấp, nên server coi như không có quyền xem.
- Quyền ghi không bao gồm quyền xem: nếu một vai trò chỉ có `SALARY_RANGES_WRITE_ALL`, POST/PUT vẫn lưu dải lương nhưng response không trả lại hai khóa trên.

Quyền được đọc lại ở mỗi yêu cầu, nên khi cấp hoặc thu hồi `SALARY_RANGES_READ_ALL`, cùng access token sẽ thấy hoặc mất dải lương ngay ở yêu cầu kế tiếp. Frontend nên hiện cột lương khi `GET /auth/permissions` có `SALARY_RANGES_READ_ALL` và hiện nút tạo/sửa chức danh khi có cả `ORGANIZATION_WRITE_ALL` lẫn `SALARY_RANGES_WRITE_ALL`; dù vậy, server vẫn tự kiểm tra.

Ví dụ một chức danh trả cho người không có quyền xem dải lương:

```json
{
  "id": "00000000-0000-0000-0000-000000000003",
  "code": "DEV_JUNIOR",
  "name": "Lập trình viên",
  "level": "Junior",
  "active": true,
  "competencyFrameworkId": null,
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

## Tạo và sửa

`POST /positions` tạo chức danh, trả **201**. `PUT /positions/{id}` thay thế toàn bộ trường của chức danh có UUID tương ứng, trả **200**. Vì lương tối thiểu/tối đa là trường bắt buộc, cả hai thao tác cần đồng thời `ORGANIZATION_WRITE_ALL` và `SALARY_RANGES_WRITE_ALL`. Với seed hiện tại chỉ HR_MANAGER làm được; ADMIN có `ORGANIZATION_WRITE_ALL` nhưng vẫn nhận 403.

```json
{
  "code": "DEV_JUNIOR",
  "name": "Lập trình viên",
  "level": "Junior",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "active": true
}
```

| Trường | Quy tắc |
|---|---|
|code|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự; duy nhất và phân biệt hoa/thường (`HR` khác `hr`), giống mã phòng ban|
|name|Bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 255 ký tự|
|level|Cấp bậc dạng chữ tự do, ví dụ `Junior`; bắt buộc, bỏ khoảng trắng đầu/cuối, tối đa 50 ký tự|
|salaryMin|Lương tối thiểu, số nguyên đồng VND, bắt buộc, từ 0 đến 1.000.000.000.000|
|salaryMax|Lương tối đa, số nguyên đồng VND, bắt buộc, từ 0 đến 1.000.000.000.000, không nhỏ hơn `salaryMin` (được phép bằng)|
|active|Boolean bắt buộc; false nghĩa là ngừng áp dụng|

Code/name/level được bỏ khoảng trắng đầu/cuối (kể cả tab, xuống dòng) trước khi kiểm tra độ dài, nên `"  DEV  "` được lưu là `DEV` và khoảng trắng thừa không bị tính vào giới hạn.

Lương là số nguyên đồng (database `BIGINT`, Java `long`), có thể vượt 2.147.483.647. Phải gửi số nguyên JSON như `15000000`; số có phần thập phân (`1.9`, kể cả `1.0`), dạng mũ (`1e3`), chuỗi (`"15000000"`) hoặc số vượt giới hạn `long` bị từ chối với `INVALID_JSON`, không bị cắt phần lẻ hay tự đổi kiểu. Mỗi mức lương phải nằm trong 0 đến 1.000.000.000.000 đồng (1.000 tỷ): trần này cao hơn mọi mức lương thực tế, chỉ để chặn lỗi gõ thừa số 0 và giữ các phép tính offer về sau xa giới hạn `long`. Trần chỉ được kiểm tra ở API; V7 không có CHECK cho trần nên dữ liệu ghi thẳng bằng SQL không bị chặn.

Thứ tự kiểm tra: trước hết từng trường riêng lẻ (bắt buộc, độ dài, lương không âm, không vượt trần), mọi trường sai được trả cùng lúc trong `fieldErrors` với mã `VALIDATION_ERROR`. Chỉ khi từng trường đều hợp lệ, backend mới so hai mức lương: `salaryMin` lớn hơn `salaryMax` trả `POSITION_SALARY_RANGE_INVALID` kèm lỗi ở trường `salaryMax`. `salaryMin` bằng `salaryMax` là dải lương cố định, hợp lệ. Sau đó mới kiểm tra chức danh tồn tại (PUT) và mã trùng. Ràng buộc CHECK của V7 (`0 <= salary_min <= salary_max`) vẫn là lớp chặn cuối trong database.

PUT phải gửi đủ sáu trường; nên GET chi tiết trước rồi gửi lại các giá trị muốn giữ. Giữ nguyên mã của chính chức danh đang sửa không bị coi là trùng. Trường ngoài hợp đồng như `id`, `createdAt` hay `competencyFrameworkId` bị từ chối với HTTP 400 `INVALID_JSON`. PUT này **giữ nguyên** khung năng lực đang gán; muốn đổi khung thì dùng API ở mục [Khung năng lực của chức danh](#khung-năng-lực-của-chức-danh-task-214).

Response của tạo/sửa và `GET /positions/{id}` cho người có `SALARY_RANGES_READ_ALL`:

```json
{
  "id": "00000000-0000-0000-0000-000000000003",
  "code": "DEV_JUNIOR",
  "name": "Lập trình viên",
  "level": "Junior",
  "salaryMin": 15000000,
  "salaryMax": 25000000,
  "active": true,
  "competencyFrameworkId": "00000000-0000-0000-0000-000000000010",
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa. `competencyFrameworkId` là UUID của khung năng lực mà chức danh đang dùng, hoặc `null` khi chưa gán; khóa này luôn có mặt, khác với hai khóa lương. `createdAt` giữ nguyên khi sửa; `updatedAt` là thời điểm ghi gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu).

## Danh sách

`GET /positions?q=dev&active=true&page=0&size=20`

| Tham số | Ý nghĩa |
|---|---|
|q|Tìm một phần code/name, không phân biệt hoa/thường, tối đa 255 ký tự; %, _ và ! được hiểu là ký tự thật; không tìm theo level|
|active|true/false; bỏ qua để lấy cả hai trạng thái|
|page|Từ 0, mặc định 0|
|size|Từ 1 đến 100, mặc định 20|

Response: `{items, page, size, totalElements, totalPages}`; mỗi item có cấu trúc chi tiết ở trên, và cũng không có `salaryMin`/`salaryMax` nếu người gọi thiếu `SALARY_RANGES_READ_ALL`. Danh sách không lọc hay sắp xếp theo lương. Sắp xếp theo code rồi UUID để phân trang ổn định; trang ngoài phạm vi có items rỗng.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/sai trường, lương âm hoặc vượt 1.000.000.000.000, UUID, page/size, active hoặc q quá dài; lỗi theo trường nằm trong `fieldErrors`|
|400|INVALID_JSON|JSON sai, lương không phải số nguyên JSON trong giới hạn `long`, hoặc có trường ngoài hợp đồng|
|400|POSITION_SALARY_RANGE_INVALID|`salaryMin` lớn hơn `salaryMax`; `fieldErrors.salaryMax` có lời nhắn để form hiển thị; dữ liệu không thay đổi|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Đọc thiếu `ORGANIZATION_READ_ALL`; ghi thiếu `ORGANIZATION_WRITE_ALL` hoặc `SALARY_RANGES_WRITE_ALL` (kể cả ADMIN)|
|404|POSITION_NOT_FOUND|Không tìm thấy chức danh đích|
|409|POSITION_CODE_EXISTS|Mã đã được chức danh khác dùng, kể cả khi hai yêu cầu ghi cùng mã đồng thời|

Lỗi riêng của hai API gán/bỏ khung năng lực nằm ở mục [Khung năng lực của chức danh](#khung-năng-lực-của-chức-danh-task-214).

Ví dụ lỗi dải lương ngược:

```json
{
  "code": "POSITION_SALARY_RANGE_INVALID",
  "message": "Lương tối thiểu không được lớn hơn lương tối đa.",
  "fieldErrors": {
    "salaryMax": "Lương tối đa phải lớn hơn hoặc bằng lương tối thiểu."
  }
}
```

Người không có quyền ghi luôn nhận 403, kể cả khi body sai, vì quyền được kiểm tra trước dữ liệu.

## Khung năng lực của chức danh (task 214)

Story 25: "khung năng lực dùng lại được cho nhiều chức danh". Mỗi chức danh **trỏ tới** một khung năng lực qua cột `positions.competency_framework_id` (V8); tiêu chí chỉ nằm trong khung và **không bị sao chép** sang từng chức danh. Các chức danh dùng chung một khung cùng đọc một bộ dòng `competency_criteria`, nên sửa khung (`PUT /competency-frameworks/{id}`) là áp dụng ngay cho mọi chức danh đang dùng nó. Danh sách chức danh đang dùng một khung nằm ở trường `positions` của `GET /competency-frameworks/{id}` ([API khung năng lực](competency-frameworks.md)). Tiêu chí và trọng số mà một chức danh được chấm theo (task 215) đọc bằng `GET /positions/{id}/evaluation-criteria` ([API tiêu chí đánh giá theo chức danh](evaluation-criteria.md)).

Hai API dưới đây chỉ đổi liên kết, không đụng tới dải lương, nên chỉ cần `ORGANIZATION_WRITE_ALL`: ADMIN cũng làm được (khác với tạo/sửa chức danh), dù response gửi cho ADMIN vẫn không có `salaryMin`/`salaryMax`.

### Gán hoặc đổi khung: `PUT /positions/{id}/competency-framework`

```json
{ "frameworkId": "00000000-0000-0000-0000-000000000010" }
```

- `frameworkId` bắt buộc, là UUID của khung năng lực. Thiếu hoặc `null` trả 400 `VALIDATION_ERROR` (`fieldErrors.frameworkId`); không phải UUID hoặc có trường khác trả 400 `INVALID_JSON`. Muốn bỏ khung thì dùng DELETE bên dưới, không gửi `null`.
- Chỉ gán được khung **hoàn chỉnh** (`ACTIVE`, tổng trọng số đúng 100%), vì phiếu đánh giá phỏng vấn sẽ chấm theo khung này. Khung `DRAFT` trả 409 `COMPETENCY_FRAMEWORK_NOT_ACTIVE`, kể cả khi trọng số của bản nháp đã đủ 100%.
- Chức danh đang có khung khác thì được đổi sang khung mới; gán lại đúng khung đang dùng vẫn trả 200. Chức danh ngừng áp dụng (`active=false`) vẫn được gán.
- Trả **200** với chức danh sau khi đổi (cấu trúc như `GET /positions/{id}`); `updatedAt` là thời điểm gán.

### Bỏ khung: `DELETE /positions/{id}/competency-framework`

Không có body. Đặt `competencyFrameworkId` của chức danh về `null` và trả **200** với chức danh sau khi đổi. Khung và tiêu chí vẫn giữ nguyên cho các chức danh khác. Gọi khi chức danh chưa có khung vẫn trả 200.

### Khung đang được dùng luôn hoàn chỉnh

Theo task 213, khung `ACTIVE` không chuyển lại `DRAFT` (409 `COMPETENCY_FRAMEWORK_ALREADY_ACTIVE`) và mọi lần sửa phải giữ tổng trọng số đúng 100% (400 `COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID`). Vì chỉ khung `ACTIVE` được gán, chức danh nào có khung cũng luôn trỏ tới một khung hoàn chỉnh. Khung đang được chức danh dùng cũng không xóa được (khóa ngoại `ON DELETE RESTRICT`; hiện chưa có API xóa khung).

### Thứ tự kiểm tra và ghi đồng thời

1. Quyền (403) và body (`VALIDATION_ERROR`, `INVALID_JSON`); người không có `ORGANIZATION_WRITE_ALL` luôn nhận 403, kể cả khi body sai.
2. Service khóa tài khoản người gọi rồi phiên, kiểm lại trạng thái, phiên, hạn JWT và quyền (giống tạo/sửa chức danh).
3. Khóa dòng chức danh (`SELECT ... FOR UPDATE`, như `PUT /positions/{id}`); không có thì 404 `POSITION_NOT_FOUND`. Đường dẫn được kiểm trước, nên chức danh và khung cùng không tồn tại thì trả 404.
4. PUT: đọc khung bằng `SELECT ... FOR SHARE`; không có thì 400 `INVALID_COMPETENCY_FRAMEWORK`; khung `DRAFT` thì 409 `COMPETENCY_FRAMEWORK_NOT_ACTIVE`.

Khóa `FOR SHARE` giữ trạng thái khung không đổi cho tới khi lần gán commit. Nếu một lần sửa khung đang chạy (khóa `FOR UPDATE`), lần gán chờ rồi đọc trạng thái đã commit: khung vừa được chuyển sang `ACTIVE` thì gán thành công. Nhiều lần gán cùng một khung cho các chức danh khác nhau không chờ nhau. Sửa danh mục (`PUT /positions/{id}`) và gán khung cho cùng chức danh chạy lần lượt nhờ khóa dòng chức danh, nên lần sửa danh mục chạy sau vẫn giữ khung vừa được gán.

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu hoặc `null` `frameworkId` (`fieldErrors.frameworkId`), UUID trên đường dẫn sai|
|400|INVALID_JSON|JSON sai hoặc rỗng, `frameworkId` không phải UUID, có trường ngoài `frameworkId`|
|400|INVALID_COMPETENCY_FRAMEWORK|Không có khung năng lực với `frameworkId` này; `fieldErrors.frameworkId`|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa|
|403|FORBIDDEN|Thiếu `ORGANIZATION_WRITE_ALL`|
|404|POSITION_NOT_FOUND|Không tìm thấy chức danh trên đường dẫn|
|409|COMPETENCY_FRAMEWORK_NOT_ACTIVE|Khung còn là bản nháp (`DRAFT`); `fieldErrors.frameworkId`|

Ví dụ gán khung bản nháp:

```json
{
  "code": "COMPETENCY_FRAMEWORK_NOT_ACTIVE",
  "message": "Chỉ gán được khung năng lực đã hoàn chỉnh (ACTIVE) cho chức danh.",
  "fieldErrors": {
    "frameworkId": "Khung năng lực này còn là bản nháp (DRAFT)."
  }
}
```

Mọi lỗi đều không thay đổi chức danh.

## Dải lương chuẩn cho kiểm tra hạn mức offer (task 206)

Task 206 không thêm endpoint, không đổi request/response ở trên và không thêm migration. Backend có thêm dịch vụ nội bộ `SalaryBandService` (gói `vn.ttcs.recruitment.position`) để các chức năng làm sau, như duyệt offer và kiểm tra yêu cầu tuyển dụng, lấy dải lương chuẩn của một chức danh và so với mức lương đề xuất. HR_MANAGER vẫn xem dải lương qua `GET /positions/{id}` như trước.

| Phương thức Java | Kết quả |
|---|---|
|`standardBand(positionId)`|`SalaryBand(positionId, salaryMin, salaryMax)`: hai mức lương là số nguyên đồng VND (`long`), đọc từ bảng `positions`|
|`compare(positionId, proposedSalary)`|`BELOW` nếu thấp hơn `salaryMin`; `WITHIN` nếu từ `salaryMin` đến `salaryMax`; `ABOVE` nếu cao hơn `salaryMax`, tức vượt hạn mức|
|`SalaryBand.compare(proposedSalary)`|Như dòng trên, dùng khi đã có `SalaryBand`|

Quy tắc:

- Cả hai đầu đều nằm trong dải: với dải 15.000.000–25.000.000, mức 15.000.000 và 25.000.000 là `WITHIN`, 25.000.001 là `ABOVE`. Dải cố định (`salaryMin = salaryMax`) chỉ nhận đúng một mức.
- Lương đề xuất âm bị từ chối bằng `IllegalArgumentException` thay vì trả `BELOW`: module gọi phải kiểm request của mình trước (ví dụ `@PositiveOrZero`), nên giá trị âm đến được đây là lỗi lập trình.
- Chức danh không tồn tại: `ApiException` 404 `POSITION_NOT_FOUND`, giống API ở trên.
- **Quyết định với chức danh ngừng áp dụng:** chỉ chức danh `active=true` có dải lương chuẩn. Chức danh `active=false` trả `ApiException` 409 `POSITION_INACTIVE` ("Chức danh đã ngừng áp dụng nên không dùng dải lương của chức danh này để kiểm tra."), áp dụng cho cả yêu cầu/offer mới lẫn offer đang chờ duyệt. Lý do: "ngừng áp dụng" nghĩa là dải lương đó không còn là khung công ty đang duyệt, nên không âm thầm so với nó. Muốn tiếp tục, HR_MANAGER bật lại chức danh bằng PUT `active=true`. Quyết định này chờ BA/PO xác nhận cùng câu hỏi 5 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md).
- **Không kiểm quyền người gọi:** dịch vụ không nhận token và không phải API, nên trả dải lương cho mọi service gọi nó. Module gọi tự kiểm quyền nghiệp vụ của mình (ví dụ quyền `OFFERS_*`) và chỉ được đưa `salaryMin`/`salaryMax` vào response cho người có `SALARY_RANGES_READ_ALL`, giống `PositionView`. Khi chỉ cần biết có vượt hạn mức không, nên gọi `compare(positionId, proposedSalary)` để không phải cầm con số. Lưu ý: kết quả `BELOW`/`WITHIN`/`ABOVE` vẫn hé lộ một phần dải lương; nếu sau này trả kết quả này cho người không có quyền xem, thử nhiều mức lương có thể đoán ra dải, nên module offer cần cân nhắc khi thiết kế response.
- **Đồng thời:** khi được gọi trong transaction ghi của module gọi, dịch vụ đọc bằng `SELECT ... FOR SHARE` nên giữ khóa chia sẻ trên dòng chức danh đến khi transaction đó commit hoặc rollback. Trong thời gian này, PUT của HR_MANAGER (sửa lương hoặc ngừng áp dụng) phải chờ, nên offer không bị duyệt theo một dải lương vừa bị đổi; nhiều lần kiểm tra cùng chức danh vẫn chạy song song. Ngược lại, nếu PUT đang ghi dở, lần kiểm tra chờ PUT commit rồi dùng giá trị mới (hoặc trả `POSITION_INACTIVE` nếu chức danh vừa bị ngừng áp dụng). Điều này chỉ đúng khi transaction ghi của module gọi dùng mức cô lập mặc định READ COMMITTED: với `REPEATABLE_READ` hoặc `SERIALIZABLE`, nếu HR đổi dòng chức danh sau khi transaction của module gọi đã chụp snapshot, PostgreSQL từ chối `FOR SHARE` bằng lỗi serialization (SQLSTATE 40001) thay vì trả giá trị mới, và lỗi này hiện chưa được xử lý nên sẽ thành 500. Vì vậy module gọi nên gọi dịch vụ từ transaction ghi dùng mức cô lập mặc định. Module gọi nên lấy dải lương sau khi đã khóa tài khoản và phiên của người gọi, cùng thứ tự với `PositionService`, để tránh deadlock. Gọi ngoài transaction chỉ là một lần đọc, không giữ khóa. Trong transaction chỉ đọc (`readOnly`), PostgreSQL không cho `FOR SHARE`, nên dịch vụ đọc không khóa; transaction chỉ đọc không lưu gì dựa trên kết quả nên không cần khóa.

## Database và phạm vi

Dùng bảng `positions` của V7, quyền ORGANIZATION của V3 và quyền SALARY_RANGES của V7_1. Task 203 và 204 không thêm migration; task 205 chỉ thêm V7_1 (4 mã quyền, 2 dòng cấp quyền cho HR_MANAGER), không đổi bảng `positions` và không cần sửa `.env`. Task 206 chỉ đọc bảng `positions`, không thêm migration hay quyền. Task 214 không thêm migration hay mã quyền: dùng cột `competency_framework_id` có sẵn từ V8 và quyền ORGANIZATION của V3. Không có DELETE chức danh: muốn ngừng dùng thì PUT `active=false` (`DELETE /positions/{id}/competency-framework` chỉ bỏ liên kết khung năng lực). Chưa có liên kết chức danh với phòng ban, yêu cầu tuyển dụng hay offer; task 206 mới chuẩn bị dải lương chuẩn và phép so sánh, còn quy tắc duyệt offer (ví dụ `ABOVE` thì cần Approver duyệt) sẽ làm ở các task offer sau.
