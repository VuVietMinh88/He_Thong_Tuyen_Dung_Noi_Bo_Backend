# API danh mục chức danh

Phạm vi TKNHTTDNB1-203 (API), TKNHTTDNB1-204 (kiểm tra dữ liệu, giới hạn dải lương), TKNHTTDNB1-205 (phân quyền xem dải lương) và TKNHTTDNB1-206 (dữ liệu dải lương chuẩn cho kiểm tra hạn mức offer, không có API mới), story TKNHTTDNB1-24. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và các lỗi `POSITION_*` dùng `Cache-Control: no-store`.

Backend đọc quyền hiện tại trong database ở mỗi yêu cầu và kiểm lại phiên/quyền sau khi khóa tài khoản người gọi khi ghi.

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /positions`, `GET /positions/{id}`|`ORGANIZATION_READ_ALL`|6 vai trò nội bộ|
|Thấy `salaryMin`/`salaryMax` trong response|Thêm `SALARY_RANGES_READ_ALL`|Chỉ HR_MANAGER|
|`POST /positions`, `PUT /positions/{id}`|`ORGANIZATION_WRITE_ALL` **và** `SALARY_RANGES_WRITE_ALL`|Chỉ HR_MANAGER|

## Ai được xem dải lương

Tiêu chí của story 24: "chỉ Trưởng phòng Nhân sự xem được dải lương". Migration V7_1 thêm module quyền `SALARY_RANGES` và chỉ cấp `SALARY_RANGES_READ_ALL`, `SALARY_RANGES_WRITE_ALL` cho HR_MANAGER. ADMIN cố ý **không** được cấp, chờ BA/PO trả lời câu hỏi 4 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md).

Dải lương bị loại ngay trên server, không chỉ ẩn trên giao diện:

- Người gọi có `SALARY_RANGES_READ_ALL`: mỗi chức danh có đủ 9 trường như ví dụ bên dưới.
- Người gọi không có quyền này: response **không có hai khóa** `salaryMin` và `salaryMax` (không phải giá trị `null` hay `0`), chỉ còn 7 trường `id`, `code`, `name`, `level`, `active`, `createdAt`, `updatedAt`. Áp dụng cho `GET /positions/{id}`, từng phần tử `items` của `GET /positions` và response của POST/PUT.
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

PUT phải gửi đủ sáu trường; nên GET chi tiết trước rồi gửi lại các giá trị muốn giữ. Giữ nguyên mã của chính chức danh đang sửa không bị coi là trùng. Trường ngoài hợp đồng như `id`, `createdAt` bị từ chối với HTTP 400 `INVALID_JSON`.

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
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:00Z"
}
```

UUID trong ví dụ chỉ minh họa. `createdAt` giữ nguyên khi sửa; `updatedAt` là thời điểm ghi gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu).

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

Dùng bảng `positions` của V7, quyền ORGANIZATION của V3 và quyền SALARY_RANGES của V7_1. Task 203 và 204 không thêm migration; task 205 chỉ thêm V7_1 (4 mã quyền, 2 dòng cấp quyền cho HR_MANAGER), không đổi bảng `positions` và không cần sửa `.env`. Task 206 chỉ đọc bảng `positions`, không thêm migration hay quyền. Không có DELETE: muốn ngừng dùng thì PUT `active=false`. Chưa có liên kết chức danh với phòng ban, yêu cầu tuyển dụng hay offer; task 206 mới chuẩn bị dải lương chuẩn và phép so sánh, còn quy tắc duyệt offer (ví dụ `ABOVE` thì cần Approver duyệt) sẽ làm ở các task offer sau.
