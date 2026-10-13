# API cổng việc làm công khai

Phạm vi TKNHTTDNB1-314 (xây dựng API công khai danh sách việc làm), TKNHTTDNB1-315 (xử lý bộ lọc và tìm kiếm nội dung việc làm) và TKNHTTDNB1-316 (giới hạn dữ liệu trả ra trên cổng công khai), story TKNHTTDNB1-40 (S4-01). URL dùng tiền tố `/api/v1`, **không** cần tài khoản; mọi response (kể cả lỗi) dùng `Cache-Control: no-store`. Đây là hợp đồng cho các trang công khai của frontend (task 312, 313, 318, 319). Hai API do task 309 (story S3-10) làm ra cùng bản xem trước của người soạn, xem [API duyệt và xuất bản tin tuyển dụng](job-posting-publication.md#xem-trước); trạng thái của tin và việc xuất bản cũng ở tài liệu đó.

## Tổng quan

| API | Trả về |
|---|---|
|`GET /public/job-postings?page=0&size=20`|Một trang việc làm đang tuyển, tin xuất bản gần nhất trước; lọc theo `departmentId`, `workLocationId`, `employmentTypeId` và tìm theo `q` (task 315, mục "Lọc và tìm kiếm")|
|`GET /public/job-postings/filters`|Giá trị cho ba bộ lọc, chỉ lấy từ các tin đang tuyển (task 315, mục "Giá trị bộ lọc")|
|`GET /public/job-postings/{id}`|Một tin đang tuyển, cùng dạng với `content` của bản xem trước|

Ai cũng gọi được, kể cả ứng viên không có tài khoản; người đã đăng nhập (mọi vai trò, kể cả tài khoản không vai trò) nhận đúng JSON như ứng viên. Kết quả không phụ thuộc người gọi.

## Danh sách việc làm (S4-01)

Story S4-01: "Là Ứng viên, tôi muốn xem danh sách việc làm đang tuyển trên trang công khai, để tìm được vị trí phù hợp mà không cần tài khoản." Task 314 (Jira: "Xây dựng API công khai danh sách việc làm") yêu cầu không cần đăng nhập và chỉ trả tin đã xuất bản, chưa hết hạn. Task 309 đã làm đủ hai điều này; task 314 không đổi code chạy thật hay hợp đồng, chỉ thêm test ghim tiêu chí trên một tập tin trộn mọi loại và tài liệu này. Task 315 (Jira: "Xử lý bộ lọc và tìm kiếm nội dung việc làm") làm tiêu chí 2 và 3: kết hợp lọc phòng ban, địa điểm, hình thức làm việc với từ khóa trong tiêu đề và mô tả; gọi không tham số vẫn cho đúng kết quả cũ. Task 316 (Jira: "Giới hạn dữ liệu trả ra trên cổng công khai") yêu cầu cổng chỉ trả trường công khai của tin, không bao giờ trả dữ liệu nội bộ của yêu cầu tuyển dụng; task 309 và 315 đã làm vậy, task 316 ghim từng trường bằng test (mục "Dữ liệu trả ra").

| Tiêu chí của S4-01 | Làm ở đâu | Test |
|---|---|---|
|1. Không cần đăng nhập|`SecurityConfiguration`: `permitAll` cho đúng `GET /api/v1/public/job-postings`, `GET /api/v1/public/job-postings/filters` (task 315) và `GET /api/v1/public/job-postings/*`, đứng trước `anyRequest().denyAll()`; phiên `STATELESS` (task 309)|`JobPostingPublicViewIntegrationTest.thePortalNeedsNoTokenAndIsNotCached`: gọi không token, với token của mọi vai trò và tài khoản không vai trò cho cùng JSON; không response nào có `Set-Cookie` (task 314); Bearer hỏng hoặc hết hạn nhận 401; phương thức khác GET nhận 401 khi không kèm token và 403 khi kèm token hợp lệ|
|2. Lọc theo phòng ban, địa điểm, hình thức làm việc|Tham số `departmentId`, `workLocationId`, `employmentTypeId` (task 315): mỗi bộ lọc thêm một điều kiện AND vào cùng điều kiện "đang tuyển" của trang và số đếm (`JobPostingReadRepository.publicPage`); giá trị cho ô chọn từ `GET /public/job-postings/filters` (`publicFilters`)|`JobPostingPortalSearchIntegrationTest.eachFilterNarrowsTheListAndTheCount`, `unknownIdsGiveAnEmptyPageNotAnError`, `malformedIdsAreRefusedAndEmptyMeansNone`, `filterOptionsListOnlyOpenPostings`, `sameNamedValuesAreSeparateOptions`|
|3. Tìm từ khóa trong tiêu đề và mô tả|Tham số `q` (task 315): một cụm từ trong `title` hoặc `jobDescription` của tin (`PublicJobPostingQuery`, `JobPostingReadRepository.KEYWORD_MATCH`)|`theKeywordSearchesTitleAndDescription`, `invalidKeywordsAre400`, `theDatabaseIsUtf8`; `PublicJobPostingQueryTest`|
|4. Chỉ hiện tin đã xuất bản và chưa hết hạn|`JobPostingReadRepository.PUBLIC_WHERE` (task 309), dùng cho cả trang và số đếm|`theAnonymousListShowsOnlyOpenPostingsAmongEveryOtherKind` (task 314), `onlyLivePublishedPostingsArePublic`, `aPublishedPostingLeavesThePortalAfterItsDeadlineOrWhenItsRequisitionIsNoLongerApproved`, `RequisitionStatusChangeIntegrationTest` (tạm dừng rồi mở lại yêu cầu)|

### "Đang tuyển" nghĩa là gì

Một tin có trong danh sách khi đủ **cả ba** điều:

1. Trạng thái `PUBLISHED` (`JobPostingService.PUBLIC_STATUSES`).
2. Hạn nộp (`applicationDeadline`) từ hôm nay trở đi, "hôm nay" là ngày ở múi giờ `app.business-zone` (`Asia/Ho_Chi_Minh`, `BusinessCalendar.today()`), không theo giờ UTC của server hay giờ của trình duyệt. Chính ngày hạn vẫn hiện tới 23:59:59 giờ Việt Nam; từ 00:00 ngày hôm sau (17:00 UTC của ngày hạn) tin rời danh sách. Ví dụ hạn 30/11/2026: còn ở `2026-11-30T16:59:59Z`, hết ở `2026-11-30T17:00:00Z`.
3. Yêu cầu tuyển dụng nguồn vẫn `APPROVED` (`JobPostingService.SOURCE_STATUSES`). Yêu cầu tạm dừng (task 290) thì tin ẩn và hiện lại khi mở lại; yêu cầu đã tuyển đủ hoặc bị hủy thì tin chuyển hẳn sang `CLOSED` (task 292).

Không có trong danh sách: nháp, chờ duyệt, cần chỉnh sửa, đã duyệt mà chưa xuất bản, đã đóng cùng yêu cầu, tin của yêu cầu không còn đã duyệt và tin đã qua hạn nộp. Tin qua hạn vẫn ở trạng thái `PUBLISHED` trong database; nó rời cổng vì điều kiện ngày, không vì một tác vụ nào chạy. `totalElements` và `totalPages` chỉ đếm tin đang tuyển, tính trong cùng một transaction `REPEATABLE_READ` với trang, nên số đếm khớp với trang.

### Yêu cầu và kết quả

```
GET /api/v1/public/job-postings?page=0&size=20
```

```json
{
  "items": [
    {
      "id": "6f1c0e4e-8f8a-4a5e-9c55-2b8f3d7c1a10",
      "title": "Lập trình viên Java (Hà Nội)",
      "positionName": "Lập trình viên",
      "departmentName": "Phòng IT",
      "workLocationName": "Hà Nội",
      "employmentTypeName": "Toàn thời gian",
      "applicationDeadline": "2026-11-30"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

UUID trong ví dụ chỉ minh họa.

- `page` đếm từ 0, `size` từ 1 đến 100 (mặc định 0 và 20); sai thì 400 `VALIDATION_ERROR` "Trang hoặc số lượng tin tuyển dụng không hợp lệ.". Trang vượt quá số trang trả `items` rỗng, vẫn kèm `totalElements` và `totalPages` đúng.
- Thứ tự: thời điểm xuất bản (`published_at` của tin, task 310) mới nhất trước; các tin xuất bản cùng lúc theo UUID giảm dần.
- Mỗi mục có `id`, `title`, `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`, `applicationDeadline`, và `salaryMin`, `salaryMax` chỉ khi tin chọn hiển thị lương (khi không chọn, hai khóa này **không có**, không phải `null`). Mô tả công việc, yêu cầu ứng viên, quyền lợi và số lượng chỉ có ở chi tiết.
- Không có trạng thái, `revision`, yêu cầu nguồn, người tạo, người duyệt, người xuất bản hay thời điểm nào.

## Lọc và tìm kiếm (task 315)

Bốn tham số tùy chọn của `GET /public/job-postings`. Không gửi tham số nào thì kết quả đúng như mục trên.

| Tham số | Lọc theo | Giá trị sai, rỗng hoặc không khớp |
|---|---|---|
|`departmentId` (UUID)|Đúng phòng ban đó: phòng ban của yêu cầu tuyển dụng nguồn, **không** gồm phòng ban con (như bộ lọc phòng ban của danh sách yêu cầu, task 297)|Không phải UUID: 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ." (`fieldErrors` rỗng). Rỗng (`departmentId=`): không lọc. id không tồn tại hoặc không có tin đang tuyển: 200 với trang rỗng|
|`workLocationId` (UUID)|Địa điểm làm việc của tin (danh mục `WORK_LOCATION`)|Như trên; id của danh mục khác (ví dụ một hình thức làm việc) không khớp tin nào|
|`employmentTypeId` (UUID)|Hình thức làm việc của tin (danh mục `EMPLOYMENT_TYPE`, "hình thức làm việc" của story)|Như trên|
|`q` (chuỗi)|Một cụm từ có trong **tiêu đề** (`title`) hoặc **mô tả công việc** (`jobDescription`) của tin|Mục "Từ khóa" bên dưới|

- Các bộ lọc được gửi kết hợp bằng **AND**, và cùng với ba điều kiện "đang tuyển": bộ lọc chỉ thu hẹp danh sách, không bao giờ cho thấy tin không đang tuyển. Trang, `totalElements` và `totalPages` dùng cùng một điều kiện, nên số đếm và số trang chỉ tính tin khớp. Thứ tự vẫn là tin xuất bản gần nhất trước.
- Mỗi bộ lọc nhận một giá trị. Id không khớp gì cho trang rỗng (200) chứ không báo lỗi, để cổng không cho biết id nào tồn tại.
- `page`, `size` được kiểm **trước** `q`: cả hai cùng sai thì nhận lỗi trang ("Trang hoặc số lượng tin tuyển dụng không hợp lệ.", `fieldErrors` rỗng).
- Tham số khác bị bỏ qua, nên liên kết chia sẻ có `utm_source`, `utm_campaign` vẫn mở được. Tên tham số tìm kiếm là `q`; `keyword` cũng bị bỏ qua (không lọc gì).

### Từ khóa `q`

- Trước khi tìm, từ khóa được đưa về Unicode NFC (chữ tiếng Việt gõ thành chữ gốc cộng dấu tổ hợp, dạng NFD, vẫn tìm được), mỗi dãy dấu cách, tab, xuống dòng thành một dấu cách, và bỏ khoảng trắng ở hai đầu. Còn rỗng thì không tìm (`q=` hay `q=%20%20` giống như không gửi).
- Sau bước trên, từ khóa dài hơn 255 ký tự trả 400 `VALIDATION_ERROR` "Từ khóa tìm kiếm tin tuyển dụng không hợp lệ." với `fieldErrors.q` "Từ khóa tìm kiếm tối đa 255 ký tự."; từ khóa có ký tự điều khiển (nhóm Unicode `Cc`, ví dụ U+0000, U+0001, U+007F) trả cùng mã và thông điệp, `fieldErrors.q` "Từ khóa tìm kiếm không được chứa ký tự điều khiển.". Tab và xuống dòng đã thành dấu cách nên không bị từ chối. Response lỗi không lặp lại từ khóa.
- Từ khóa là **một cụm liền** (không tách từ), tìm ở bất kỳ vị trí nào trong tiêu đề hoặc mô tả công việc của tin. **Không** tìm trong yêu cầu ứng viên, quyền lợi, tên chức danh, phòng ban, địa điểm hay hình thức, và không bao giờ trong văn bản nội bộ của yêu cầu tuyển dụng nguồn.
- Không phân biệt chữ hoa, chữ thường, kể cả chữ tiếng Việt có dấu ("KẾ TOÁN" tìm thấy "kế toán"); **có** phân biệt dấu: "ke toan" không tìm thấy "kế toán" (câu hỏi 21).
- Văn bản của tin, tiêu đề lẫn mô tả công việc, cũng được đưa về NFC và gộp khoảng trắng khi so (`normalize(..., NFC)` và `regexp_replace(..., '[[:space:]]+', ' ')` trong SQL), nên "kịch bản kiểm thử" tìm được tin viết "kịch bản", xuống dòng, rồi "kiểm thử", "kế toán tổng hợp" tìm được tiêu đề có nhiều dấu cách liền giữa các chữ (API chỉ bỏ khoảng trắng ở hai đầu tiêu đề), và văn bản lưu dạng NFD vẫn tìm được.
- `%`, `_` và `!` được tìm đúng là ký tự đó (server thoát các ký tự đại diện của `LIKE` bằng `!`). Mọi giá trị là tham số bind, nên dấu nháy hay chuỗi như `' OR 1=1 --` chỉ là chữ cần tìm.

Ví dụ (giá trị của `q` mã hóa URL, id lấy từ "Giá trị bộ lọc"):

```
GET /api/v1/public/job-postings?q=k%E1%BA%BF%20to%C3%A1n&departmentId=0b7d5c8e-2f43-4a1e-9b61-6f2a4c3d9e10&page=0&size=20
GET /api/v1/public/job-postings?workLocationId=5a0e3f71-8c2d-4b9a-a6e4-1d7f9c2b3e55&employmentTypeId=9c4b2a10-6e7f-4d3c-8b1a-2f5e6d7c8b90
```

Response có đúng dạng của mục "Yêu cầu và kết quả". UUID trong ví dụ chỉ minh họa.

## Giá trị bộ lọc (task 315)

`GET /public/job-postings/filters` (không tham số, không cần tài khoản, `Cache-Control: no-store`) trả các giá trị để frontend vẽ ba ô chọn:

```json
{
  "departments": [{"id": "0b7d5c8e-2f43-4a1e-9b61-6f2a4c3d9e10", "name": "Phòng Công nghệ"}],
  "workLocations": [{"id": "5a0e3f71-8c2d-4b9a-a6e4-1d7f9c2b3e55", "name": "Hà Nội"}],
  "employmentTypes": [{"id": "9c4b2a10-6e7f-4d3c-8b1a-2f5e6d7c8b90", "name": "Toàn thời gian"}]
}
```

- Chỉ lấy từ các tin đang có trên danh sách lúc gọi (cùng ba điều kiện "đang tuyển", cùng ngày theo giờ Việt Nam), mỗi giá trị một lần. Giá trị chỉ được bản nháp, tin chờ duyệt, tin đã duyệt chưa xuất bản, tin đã đóng, tin của yêu cầu tạm dừng hoặc tin đã qua hạn nộp dùng thì không xuất hiện. Không có tin đang tuyển thì cả ba mảng rỗng.
- Giá trị HR đã ngừng áp dụng (`active = false`) sau khi tin được duyệt vẫn xuất hiện chừng nào còn tin đang tuyển dùng nó, vì tin đó vẫn hiện trên danh sách.
- Thứ tự: phòng ban theo tên; địa điểm và hình thức làm việc theo thứ tự của danh mục (`sortOrder`), rồi theo tên; cùng tên thì theo id.
- Mỗi giá trị chỉ có `id` và `name`, không có mã hay trạng thái áp dụng. Gửi lại `id` làm `departmentId`, `workLocationId` hoặc `employmentTypeId` sẽ ra ít nhất một tin (trừ khi tin đó vừa rời cổng). Đây là API công khai duy nhất trả id phòng ban, địa điểm và hình thức; danh sách và chi tiết tin chỉ có tên.
- `name` **không** duy nhất: hai phòng ban có thể cùng tên (V5 chỉ bắt `code` duy nhất), hai giá trị của cùng một danh mục cũng vậy (V10 chỉ bắt mã duy nhất trong một loại). `id` mới là khóa. Hai giá trị cùng tên là hai phòng ban (hoặc hai giá trị danh mục) khác nhau, mỗi giá trị chỉ lọc tin của riêng nó, và đứng cạnh nhau theo id. Ví dụ "Phòng Phát triển" của Khối Công nghệ và của Khối Sản phẩm là hai dòng giống hệt nhau; hiện chưa có nhãn nào phân biệt chúng, kể cả trong `departmentName` của tin (câu hỏi 21, ý 5; `JobPostingPortalSearchIntegrationTest.sameNamedValuesAreSeparateOptions`).
- Ba danh sách đọc trong một transaction `REPEATABLE_READ` chỉ đọc, không khóa gì.

## Chi tiết tin

`GET /public/job-postings/{id}` trả một tin đúng ba điều kiện ở mục "Đang tuyển" nghĩa là gì. Mọi tin khác (nháp, chờ duyệt, cần chỉnh sửa, đã duyệt mà chưa xuất bản, đã đóng cùng yêu cầu, quá hạn nộp, yêu cầu không còn đã duyệt) trả **404** `JOB_POSTING_NOT_FOUND` "Không tìm thấy tin tuyển dụng.", giống hệt tin không tồn tại, nên cổng không cho biết tin nào đang được soạn hay duyệt.

Trường của tin: `id`, `title`, `positionName`, `departmentName`, `headcount`, `workLocationName`, `employmentTypeName`, `applicationDeadline`, `salaryMin`, `salaryMax`, `jobDescription`, `candidateRequirements`, `benefits` (`benefits` là `null` khi chưa viết). Đây đúng là `content` của bản xem trước `GET /job-postings/{id}/preview`: cả hai dựng bằng `PublicJobPostingView.of` từ cùng câu truy vấn `JobPostingReadRepository.SELECT_ROW` (task 309).

- Lương chỉ có khi tin chọn hiển thị (`showSalary: true`): `salaryMin`, `salaryMax` là lương đề xuất đã duyệt của yêu cầu (đồng VND mỗi người mỗi tháng). Khi không chọn, hai khóa này **không có** trong JSON, và server không gửi số tiền đi.
- Tên chức danh, phòng ban, địa điểm và hình thức làm việc được đọc trực tiếp: đổi tên trong danh mục hiện ngay trên cổng (câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)).

## Dữ liệu trả ra (task 316)

Task 316 (Jira: "Giới hạn dữ liệu trả ra trên cổng công khai"): cổng chỉ trả trường công khai của tin, không bao giờ trả dữ liệu nội bộ của yêu cầu tuyển dụng. Task 309 đã làm điều này bằng các record chỉ chứa trường công khai, task 315 thêm hai record của `/filters`. Task 316 không đổi code chạy thật hay hợp đồng: không test nào tìm ra trường bị lộ, nên commit chỉ thêm test ghim từng record, từng response, và mục này.

### Trường của từng response

| Response | Trường, đúng thứ tự trong JSON | Record |
|---|---|---|
|Trang danh sách (kể cả khi lọc, tìm, trang rỗng)|`items`, `page`, `size`, `totalElements`, `totalPages`|`PublicJobPostingPage`|
|Một mục của danh sách|`id`, `title`, `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`, `applicationDeadline`; thêm `salaryMin`, `salaryMax` ở cuối chỉ khi tin chọn hiển thị lương|`PublicJobPostingSummary`|
|Chi tiết tin|`id`, `title`, `positionName`, `departmentName`, `headcount`, `workLocationName`, `employmentTypeName`, `applicationDeadline`, `salaryMin`, `salaryMax` (chỉ khi hiển thị lương), `jobDescription`, `candidateRequirements`, `benefits`|`PublicJobPostingView`|
|Giá trị bộ lọc|`departments`, `workLocations`, `employmentTypes`; mỗi giá trị đúng `id`, `name`|`PublicJobPostingFilters`, `PublicJobPostingFilterOption`|
|Lỗi (400, 401, 403, 404)|`code`, `message`, `fieldErrors`|`ApiError`|

### Nguồn của từng trường và vì sao được công khai

| Trường | Lấy từ | Vì sao công khai |
|---|---|---|
|`id`|`job_postings.id`|Để mở chi tiết (và sau này nộp hồ sơ); không cho biết gì về yêu cầu nguồn|
|`title`, `jobDescription`, `candidateRequirements`, `benefits`|Bản của chính tin (`job_postings`), viết bằng ngôn ngữ tuyển dụng và đã qua duyệt (task 304, 308)|Là nội dung tin. Văn bản của yêu cầu chỉ được chép một lần lúc tạo tin (nếu người soạn không tự viết), sau đó tách hẳn; cổng không bao giờ đọc văn bản của yêu cầu|
|`applicationDeadline`|`job_postings.application_deadline`|Hạn nộp, ứng viên cần biết (S4-02)|
|`workLocationName`, `employmentTypeName`|Tên hiện tại của giá trị danh mục tin đã chọn|Thông tin công khai của tin; chỉ có tên, không có id hay mã|
|`positionName`, `departmentName`, `headcount`|Từ yêu cầu tuyển dụng nguồn: tên chức danh và tên phòng ban hiện tại (đọc trực tiếp), số lượng đã duyệt|Câu hỏi 16 cho hiện ba trường này; frontend (task 312, 313, 318) dùng `positionName`. Chỉ có tên, không có id|
|`salaryMin`, `salaryMax`|Lương đề xuất đã duyệt của yêu cầu, **chỉ** khi tin chọn `showSalary`|Người soạn chọn công khai. Khi không chọn, server bỏ cả hai khóa trước khi gửi (`PublicJobPostingView.of`); số tiền không có trong response|
|`/filters`: `id`, `name`|Phòng ban của yêu cầu nguồn, giá trị danh mục của tin; chỉ của tin đang tuyển|Để vẽ ô chọn và gửi lại làm bộ lọc. Đây là API công khai duy nhất có id phòng ban, địa điểm, hình thức|

### Không bao giờ trả

- id của yêu cầu tuyển dụng nguồn (không có `requisitionId`) và mọi dữ liệu riêng của yêu cầu: mô tả công việc, yêu cầu ứng viên và giải trình lương **của yêu cầu**, lý do tuyển, ngày cần người, trạng thái, lịch sử duyệt và nhận xét của người duyệt, nhóm recruiter, lương đề xuất khi tin không chọn hiển thị.
- id chức danh (tên chức danh thì có, xem bảng trên), id phòng ban, địa điểm, hình thức ngoài `/filters`, mã phòng ban và mã danh mục, phòng ban cha, trưởng phòng, cờ áp dụng, thứ tự sắp xếp.
- Trạng thái và `revision` của tin; người tạo, người sửa, người gửi duyệt, người duyệt, người xuất bản (id, tên, email); nhận xét khi gửi duyệt, yêu cầu chỉnh sửa và duyệt tin; mọi thời điểm (tạo, sửa, duyệt, xuất bản).
- Bất cứ điều gì về tin không đang tuyển: chi tiết trả **cùng một body 404**, giống hệt từng byte, cho bản nháp, tin chờ duyệt, cần chỉnh sửa, đã duyệt chưa xuất bản, đã đóng, tin của yêu cầu tạm dừng và id không tồn tại; danh sách và số đếm không tính chúng; `/filters` không lấy giá trị của chúng.
- Lỗi 400 của từ khóa không lặp lại từ khóa; lỗi 404 không lặp lại id.

Tìm kiếm cũng không dùng được để dò văn bản nội bộ: từ khóa `q` chỉ đọc tiêu đề và mô tả công việc của tin đang tuyển, nên một chuỗi chỉ có trong văn bản của yêu cầu, trong nhận xét duyệt, trong yêu cầu ứng viên hay quyền lợi của tin, trong tên chức danh, phòng ban, địa điểm, hình thức, hoặc trong bản nháp đều cho trang rỗng với `totalElements` 0.

### Cách bảo đảm

- Response của cổng chỉ được dựng từ các record công khai ở bảng trên. `PublicJobPostingView.of` là chỗ duy nhất chọn trường từ dòng dữ liệu và bỏ lương bị ẩn; mục danh sách được cắt từ view đó (`PublicJobPostingSummary.of`), nên lương chỉ bị ẩn ở một chỗ.
- `JobPostingReadRepository.SELECT_ROW` đọc **nhiều hơn** những gì cổng trả (trạng thái yêu cầu, lương đề xuất, người tạo, nhận xét duyệt, người xuất bản...), vì bản xem trước, màn hình nội bộ và cổng dùng chung một câu truy vấn (task 303, 309) để luôn hiện cùng nội dung. Dòng đầy đủ (`JobPostingRow`) không bao giờ rời server: chỉ record công khai được chuyển sang JSON. Đây là lựa chọn có chủ ý; nếu sau này cần, có thể viết câu SQL riêng cho cổng mà không đổi hợp đồng.
- Test (task 316): `PublicJobPostingViewTest.everyPortalRecordHasOnlyItsPublicComponents` ghim tên và kiểu từng thành phần của năm record (trang chỉ chứa `List<PublicJobPostingSummary>`, `/filters` chỉ chứa `List<PublicJobPostingFilterOption>`), `theSummaryJsonCarriesOnlyListFields`, `thePublicPostingNamesButNeverIdentifiesItsOrganizationValues`; `JobPostingPublicViewIntegrationTest.theSalaryIsPublicOnlyWhenChosen` ghim thêm khóa của mục có lương; `JobPostingPortalSearchIntegrationTest.filteredAndSearchedListsCarryOnlyListFields`, `theFilterValuesCarryOnlyIdAndName`, `thePortalNeverShowsRequisitionData` (yêu cầu nguồn đi qua luồng duyệt thật, văn bản nội bộ là chuỗi đánh dấu, kiểm mọi response kể cả 404 và 400), `theSearchNeverReadsInternalOrUnsearchedText`, `theNotFoundBodyIsTheSameForEveryHiddenPosting`. Thêm một trường vào cổng buộc phải sửa các danh sách khóa này, nên không thể lộ thêm trường mà không ai thấy.

## Bảo mật và cache

- `permitAll` chỉ cho đúng phương thức GET và đúng ba đường dẫn trên (`/filters` được ghi tên riêng dù `/public/job-postings/*` đã gồm nó, để vẫn công khai nếu sau này `/*` bị thu hẹp lại chỉ cho id). Phương thức khác (ví dụ `POST /public/job-postings`, `POST /public/job-postings/filters`, `PUT` hay `DELETE /public/job-postings/{id}`) hoặc đường dẫn khác trả **401** `UNAUTHORIZED` khi gọi không kèm token, vì `anyRequest().denyAll()` đưa người gọi ẩn danh tới bước đăng nhập, và trả **403** `FORBIDDEN` khi kèm token hợp lệ (`JobPostingPublicViewIntegrationTest.thePortalNeedsNoTokenAndIsNotCached`; `ApiAuthorizationMatrixIntegrationTest` kiểm với token ADMIN).
- Như [trang giới thiệu công ty](company-profile.md), gửi kèm Bearer hỏng hoặc hết hạn vẫn bị 401 ở bộ lọc bảo mật, nên trang công khai gọi ba API này **không** kèm header `Authorization`.
- Server không tạo phiên (`SessionCreationPolicy.STATELESS`): response không có `Set-Cookie`, kể cả khi người gọi đã đăng nhập.
- Mọi response dùng `Cache-Control: no-store`, kể cả `/filters`, 404 và lỗi, vì tin rời cổng ngay khi quá hạn hoặc khi yêu cầu không còn đã duyệt.
- Lọc và tìm kiếm chỉ thêm điều kiện vào câu SQL đã có điều kiện "đang tuyển", với mọi giá trị là tham số bind (task 315); không tham số nào cho thấy thêm tin hay đổi câu SQL.
- Hiển thị **mọi** chuỗi dạng chữ (không dùng `dangerouslySetInnerHTML`, `v-html` hay `innerHTML`), kể cả `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`: server chỉ kiểm văn bản thuần với `title`, `jobDescription`, `candidateRequirements`, `benefits` (task 304); bốn tên kia đến từ dữ liệu tổ chức, chỉ được kiểm độ dài.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`page`/`size` sai ("Trang hoặc số lượng tin tuyển dụng không hợp lệ.", kiểm trước `q`); `q` quá 255 ký tự hoặc có ký tự điều khiển ("Từ khóa tìm kiếm tin tuyển dụng không hợp lệ.", kèm `fieldErrors.q`, task 315); `page` hoặc `size` không phải số, `{id}`, `departmentId`, `workLocationId` hoặc `employmentTypeId` không phải UUID ("Tham số đường dẫn hoặc bộ lọc không hợp lệ.")|
|401|`UNAUTHORIZED`|Gửi kèm Bearer hỏng hoặc hết hạn; hoặc không kèm token mà gọi phương thức hay đường dẫn khác ba API trên, ví dụ `POST /public/job-postings` (có `WWW-Authenticate: Bearer`)|
|403|`FORBIDDEN`|Kèm token hợp lệ mà gọi phương thức hoặc đường dẫn khác ba API trên, ví dụ `POST /public/job-postings`|
|404|`JOB_POSTING_NOT_FOUND`|Chi tiết: không có tin với `{id}`, hoặc tin không đang tuyển (mục "Chi tiết tin")|

Body lỗi có đúng ba khóa `code`, `message`, `fieldErrors`.

## Ghi chú cho frontend

- Nhánh `UI` (10/10/2026) chưa có trang việc làm công khai. `src/utils/axiosClient.ts` gắn `Authorization: Bearer …` vào mọi request khi trình duyệt còn lưu token, nên người từng đăng nhập mà token đã hết hạn sẽ nhận 401. Trang công khai cần một axios instance riêng **không** gắn `Authorization` và không chạy luồng refresh token.
- `page` đếm từ 0 (nút "Trang 1" gửi `page=0`); `size` tối đa 100. Dùng `totalPages` để vẽ phân trang, không tự đếm.
- Bộ lọc (task 315): gửi `departmentId`, `workLocationId`, `employmentTypeId` và `q` (không phải `keyword`), mã hóa bằng `encodeURIComponent`; bỏ hẳn tham số hoặc gửi rỗng khi người dùng chọn "Tất cả". Đổi bộ lọc thì quay về `page=0`. Lấy ba ô chọn từ `GET /public/job-postings/filters`, không từ `/departments` hay `/recruitment-catalogs` (cần đăng nhập). Dùng `id` làm `key` và giá trị của ô chọn, không dùng `name`, và không gộp các giá trị cùng tên: hai phòng ban có thể cùng tên mà là hai phòng ban khác nhau (mục "Giá trị bộ lọc"; nhãn phân biệt còn chờ câu hỏi 21). Form soạn tin ở nhánh `UI` đang để "Remote", "Hybrid" trong ô địa điểm; backend lọc hình thức làm việc theo danh mục `EMPLOYMENT_TYPE` (câu hỏi 21).
- Từ khóa có phân biệt dấu: gợi ý người dùng gõ có dấu; trang rỗng khi tìm là kết quả bình thường, không phải lỗi.
- Tin đang mở có thể rời danh sách giữa hai lần tải (qua hạn lúc 00:00 giờ Việt Nam, yêu cầu bị tạm dừng); mở chi tiết khi đó nhận 404: hiện "Không tìm thấy tin tuyển dụng" thay vì báo lỗi hệ thống.
- So sánh hạn nộp theo ngày `applicationDeadline` (không có giờ); không đổi sang giờ của trình duyệt.

## Giả định chờ BA/PO

Câu hỏi 21 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md): định nghĩa "đang tuyển" ở trên (tính cả ngày hạn theo giờ Việt Nam, yêu cầu tạm dừng thì ẩn, tin xuất bản gần nhất trước); bộ lọc phòng ban không gồm phòng ban con, mỗi bộ lọc một giá trị; "hình thức làm việc" là danh mục `EMPLOYMENT_TYPE`; từ khóa là một cụm, có phân biệt dấu, chỉ tìm trong tiêu đề và mô tả công việc; giá trị bộ lọc chỉ lấy từ tin đang tuyển và lộ id, id không tồn tại trả trang rỗng, giá trị cùng tên chưa có nhãn phân biệt (task 315); và các trường cổng hiện (có số lượng tuyển và tên chức danh, không có ngày đăng, người xuất bản, id chức danh và dữ liệu nội bộ của yêu cầu; task 316, mục "Dữ liệu trả ra").
