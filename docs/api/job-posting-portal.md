# API cổng việc làm công khai

Phạm vi TKNHTTDNB1-314 (xây dựng API công khai danh sách việc làm), story TKNHTTDNB1-40 (S4-01). URL dùng tiền tố `/api/v1`, **không** cần tài khoản; mọi response (kể cả lỗi) dùng `Cache-Control: no-store`. Đây là hợp đồng cho các trang công khai của frontend (task 312, 313, 318, 319). Hai API do task 309 (story S3-10) làm ra cùng bản xem trước của người soạn, xem [API duyệt và xuất bản tin tuyển dụng](job-posting-publication.md#xem-trước); trạng thái của tin và việc xuất bản cũng ở tài liệu đó.

## Tổng quan

| API | Trả về |
|---|---|
|`GET /public/job-postings?page=0&size=20`|Một trang việc làm đang tuyển, tin xuất bản gần nhất trước|
|`GET /public/job-postings/{id}`|Một tin đang tuyển, cùng dạng với `content` của bản xem trước|

Ai cũng gọi được, kể cả ứng viên không có tài khoản; người đã đăng nhập (mọi vai trò, kể cả tài khoản không vai trò) nhận đúng JSON như ứng viên. Kết quả không phụ thuộc người gọi.

## Danh sách việc làm (S4-01)

Story S4-01: "Là Ứng viên, tôi muốn xem danh sách việc làm đang tuyển trên trang công khai, để tìm được vị trí phù hợp mà không cần tài khoản." Task 314 (Jira: "Xây dựng API công khai danh sách việc làm") yêu cầu không cần đăng nhập và chỉ trả tin đã xuất bản, chưa hết hạn. Task 309 đã làm đủ hai điều này; task 314 không đổi code chạy thật hay hợp đồng, chỉ thêm test ghim tiêu chí trên một tập tin trộn mọi loại và tài liệu này.

| Tiêu chí của S4-01 | Làm ở đâu | Test |
|---|---|---|
|1. Không cần đăng nhập|`SecurityConfiguration`: `permitAll` cho đúng `GET /api/v1/public/job-postings` và `GET /api/v1/public/job-postings/*`, đứng trước `anyRequest().denyAll()`; phiên `STATELESS` (task 309)|`JobPostingPublicViewIntegrationTest.thePortalNeedsNoTokenAndIsNotCached`: gọi không token, với token của mọi vai trò và tài khoản không vai trò cho cùng JSON; không response nào có `Set-Cookie` (task 314); Bearer hỏng hoặc hết hạn nhận 401; phương thức khác GET nhận 401 khi không kèm token và 403 khi kèm token hợp lệ|
|2. Lọc theo phòng ban, địa điểm, hình thức làm việc|Chưa có|Task 315|
|3. Tìm từ khóa trong tiêu đề và mô tả|Chưa có|Task 315|
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

## Chi tiết tin

`GET /public/job-postings/{id}` trả một tin đúng ba điều kiện ở mục "Đang tuyển" nghĩa là gì. Mọi tin khác (nháp, chờ duyệt, cần chỉnh sửa, đã duyệt mà chưa xuất bản, đã đóng cùng yêu cầu, quá hạn nộp, yêu cầu không còn đã duyệt) trả **404** `JOB_POSTING_NOT_FOUND` "Không tìm thấy tin tuyển dụng.", giống hệt tin không tồn tại, nên cổng không cho biết tin nào đang được soạn hay duyệt.

Trường của tin: `id`, `title`, `positionName`, `departmentName`, `headcount`, `workLocationName`, `employmentTypeName`, `applicationDeadline`, `salaryMin`, `salaryMax`, `jobDescription`, `candidateRequirements`, `benefits` (`benefits` là `null` khi chưa viết). Đây đúng là `content` của bản xem trước `GET /job-postings/{id}/preview`: cả hai dựng bằng `PublicJobPostingView.of` từ cùng câu truy vấn `JobPostingReadRepository.SELECT_ROW` (task 309).

- Lương chỉ có khi tin chọn hiển thị (`showSalary: true`): `salaryMin`, `salaryMax` là lương đề xuất đã duyệt của yêu cầu (đồng VND mỗi người mỗi tháng). Khi không chọn, hai khóa này **không có** trong JSON, và server không gửi số tiền đi.
- Tên chức danh, phòng ban, địa điểm và hình thức làm việc được đọc trực tiếp: đổi tên trong danh mục hiện ngay trên cổng (câu hỏi 16 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)).

## Bảo mật và cache

- `permitAll` chỉ cho đúng phương thức GET và đúng hai đường dẫn trên. Phương thức khác (ví dụ `POST /public/job-postings`, `PUT` hay `DELETE /public/job-postings/{id}`) hoặc đường dẫn khác trả **401** `UNAUTHORIZED` khi gọi không kèm token, vì `anyRequest().denyAll()` đưa người gọi ẩn danh tới bước đăng nhập, và trả **403** `FORBIDDEN` khi kèm token hợp lệ (`JobPostingPublicViewIntegrationTest.thePortalNeedsNoTokenAndIsNotCached`; `ApiAuthorizationMatrixIntegrationTest` kiểm với token ADMIN).
- Như [trang giới thiệu công ty](company-profile.md), gửi kèm Bearer hỏng hoặc hết hạn vẫn bị 401 ở bộ lọc bảo mật, nên trang công khai gọi hai API này **không** kèm header `Authorization`.
- Server không tạo phiên (`SessionCreationPolicy.STATELESS`): response không có `Set-Cookie`, kể cả khi người gọi đã đăng nhập.
- Mọi response dùng `Cache-Control: no-store`, kể cả 404 và lỗi, vì tin rời cổng ngay khi quá hạn hoặc khi yêu cầu không còn đã duyệt.
- Hiển thị **mọi** chuỗi dạng chữ (không dùng `dangerouslySetInnerHTML`, `v-html` hay `innerHTML`), kể cả `positionName`, `departmentName`, `workLocationName`, `employmentTypeName`: server chỉ kiểm văn bản thuần với `title`, `jobDescription`, `candidateRequirements`, `benefits` (task 304); bốn tên kia đến từ dữ liệu tổ chức, chỉ được kiểm độ dài.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`page`/`size` sai ("Trang hoặc số lượng tin tuyển dụng không hợp lệ."); `page` hoặc `size` không phải số, `{id}` không phải UUID ("Tham số đường dẫn hoặc bộ lọc không hợp lệ.")|
|401|`UNAUTHORIZED`|Gửi kèm Bearer hỏng hoặc hết hạn; hoặc không kèm token mà gọi phương thức hay đường dẫn khác hai API trên, ví dụ `POST /public/job-postings` (có `WWW-Authenticate: Bearer`)|
|403|`FORBIDDEN`|Kèm token hợp lệ mà gọi phương thức hoặc đường dẫn khác hai API trên, ví dụ `POST /public/job-postings`|
|404|`JOB_POSTING_NOT_FOUND`|Chi tiết: không có tin với `{id}`, hoặc tin không đang tuyển (mục "Chi tiết tin")|

Body lỗi có đúng ba khóa `code`, `message`, `fieldErrors`.

## Ghi chú cho frontend

- Nhánh `UI` (10/10/2026) chưa có trang việc làm công khai. `src/utils/axiosClient.ts` gắn `Authorization: Bearer …` vào mọi request khi trình duyệt còn lưu token, nên người từng đăng nhập mà token đã hết hạn sẽ nhận 401. Trang công khai cần một axios instance riêng **không** gắn `Authorization` và không chạy luồng refresh token.
- `page` đếm từ 0 (nút "Trang 1" gửi `page=0`); `size` tối đa 100. Dùng `totalPages` để vẽ phân trang, không tự đếm.
- Tin đang mở có thể rời danh sách giữa hai lần tải (qua hạn lúc 00:00 giờ Việt Nam, yêu cầu bị tạm dừng); mở chi tiết khi đó nhận 404: hiện "Không tìm thấy tin tuyển dụng" thay vì báo lỗi hệ thống.
- So sánh hạn nộp theo ngày `applicationDeadline` (không có giờ); không đổi sang giờ của trình duyệt.

## Giả định chờ BA/PO

Câu hỏi 21 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md): định nghĩa "đang tuyển" ở trên (tính cả ngày hạn theo giờ Việt Nam, yêu cầu tạm dừng thì ẩn, tin xuất bản gần nhất trước) và các trường cổng hiện (có số lượng tuyển, không có ngày đăng, người xuất bản và dữ liệu nội bộ của yêu cầu).
