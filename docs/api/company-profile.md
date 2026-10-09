# API trang giới thiệu công ty

Phạm vi TKNHTTDNB1-237 (story S2-09, TKNHTTDNB1-28). URL dùng tiền tố `/api/v1`. Mọi response thành công và các lỗi `COMPANY_PROFILE_*`, `INVALID_COMPANY_*` dùng `Cache-Control: no-store`.

| API | Công dụng | Quyền |
|---|---|---|
|`GET /company-profile`|HR mở trình soạn: đọc nội dung đã lưu|`JOB_POSTINGS_WRITE_ALL`|
|`PUT /company-profile`|Lưu (lần đầu là tạo) toàn bộ nội dung trang|`JOB_POSTINGS_WRITE_ALL`|
|`POST /company-profile/preview`|Xem trước đúng như trang công khai, **không lưu**|`JOB_POSTINGS_WRITE_ALL`|
|`GET /public/company-profile`|Cổng tuyển dụng hiển thị trang cho ứng viên|Công khai, không cần token|

Ba API đầu gửi `Authorization: Bearer <accessToken>`. Ma trận hiện tại cấp `JOB_POSTINGS_WRITE_ALL` cho ADMIN và HR_MANAGER. RECRUITER chỉ có `JOB_POSTINGS_WRITE_SCOPED` (tin tuyển dụng của vị trí được phân công) nên bị 403: trang giới thiệu dùng chung cho cả công ty, không có phạm vi phân công. HIRING_MANAGER, APPROVER có `JOB_POSTINGS_READ_ALL` nhưng cũng không đọc được bản trong trình soạn; họ xem trang qua API công khai như ứng viên. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu; khi lưu, service khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái, phiên, hạn JWT và quyền.

API công khai không cần token. Giống `GET /health`, nếu gửi kèm Bearer hỏng hoặc hết hạn thì bộ lọc bảo mật vẫn trả 401, nên cổng tuyển dụng nên gọi API này **không** kèm header `Authorization`.

## Lưu nội dung

`PUT /company-profile` thay thế toàn bộ nội dung, trả **200** cả khi tạo lần đầu lẫn khi sửa. Hệ thống chỉ có một trang (một dòng `id = 1` của V11). Lưu xong, trang công khai đổi ngay; không có bản nháp riêng, muốn kiểm tra trước thì dùng xem trước.

```json
{
  "companyName": "Công ty TTCS",
  "tagline": "Nơi phát triển tài năng",
  "introduction": "Chúng tôi xây dựng phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm",
  "logoMediaId": "00000000-0000-0000-0000-000000000011",
  "imageIds": [
    "00000000-0000-0000-0000-000000000012",
    "00000000-0000-0000-0000-000000000013"
  ]
}
```

| Trường | Quy tắc |
|---|---|
|companyName|Bắt buộc, tối đa 255 ký tự, một dòng|
|tagline|Khẩu hiệu, tùy chọn, tối đa 255 ký tự, một dòng; null, bỏ trường hoặc toàn khoảng trắng đều lưu thành null|
|introduction|Bắt buộc, tối đa 20.000 ký tự; được xuống dòng và dùng tab|
|logoMediaId|UUID ảnh đã tải lên với loại `LOGO`; null hoặc bỏ trường nghĩa là không có logo|
|imageIds|Danh sách UUID ảnh đã tải lên với loại `IMAGE`, theo thứ tự hiển thị (phần tử đầu hiện trước); tối đa 10 ảnh, không lặp, không có phần tử null; null hoặc bỏ trường nghĩa là không có ảnh|

Cả ba trường chữ được bỏ mọi loại khoảng trắng ở đầu/cuối, kể cả khoảng trắng không ngắt (`U+00A0`) và khoảng trắng toàn khổ (`U+3000`). Trong `introduction`, xuống dòng kiểu Windows (`\r\n`) hoặc `\r` được đổi thành `\n`; các dòng trống ở giữa được giữ nguyên. Độ dài tính theo đơn vị UTF-16 của Java, nên một emoji có thể được tính là 2 ký tự.

PUT phải gửi đủ nội dung muốn giữ: trường bỏ trống được hiểu là xóa (ví dụ bỏ `imageIds` sẽ xóa hết ảnh khỏi trang). Nên `GET /company-profile` trước rồi gửi lại các giá trị muốn giữ. Bỏ một ảnh khỏi trang không xóa ảnh trong database. Trường ngoài hợp đồng như `createdAt`, `updatedBy` bị từ chối với HTTP 400 `INVALID_JSON`.

Response của PUT và `GET /company-profile` (dữ liệu cho trình soạn; tên trường nội dung giống body của PUT):

```json
{
  "companyName": "Công ty TTCS",
  "tagline": "Nơi phát triển tài năng",
  "introduction": "Chúng tôi xây dựng phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm",
  "logoMediaId": "00000000-0000-0000-0000-000000000011",
  "imageIds": ["00000000-0000-0000-0000-000000000012", "00000000-0000-0000-0000-000000000013"],
  "createdAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T09:30:00Z",
  "updatedBy": "00000000-0000-0000-0000-000000000001"
}
```

UUID trong ví dụ chỉ minh họa. `createdAt` là lần lưu đầu, giữ nguyên khi sửa; `updatedAt` là lần lưu gần nhất (UTC, độ chính xác micro giây như PostgreSQL lưu); `updatedBy` là tài khoản lưu gần nhất. Trước lần lưu đầu, `GET /company-profile` trả **404** `COMPANY_PROFILE_NOT_FOUND`; trình soạn hiển thị form trống.

Hai người lưu cùng lúc được xử lý lần lượt (khóa advisory của PostgreSQL), kể cả lần lưu đầu tiên; cả hai đều nhận 200 và nội dung của người lưu sau cùng được giữ. Chưa có kiểm tra phiên bản để báo "trang đã bị người khác sửa".

## Chỉ nhận văn bản thuần, không nhận HTML

Nội dung là văn bản thuần (có thể viết kiểu Markdown đơn giản như `- ý`, dòng trống giữa các đoạn), **không phải HTML**, để tránh stored XSS trên cổng tuyển dụng. Backend từ chối với 400 `VALIDATION_ERROR` khi:

- có `<` đứng **ngay trước** chữ cái Latin, `/`, `!` hoặc `?`, tức là thứ trình duyệt hiểu là thẻ, thẻ đóng, chú thích hoặc khai báo HTML: `<b>`, `</p>`, `<!-- -->`, `<?xml`, `<img src=x onerror=...>`;
- có ký tự điều khiển (ví dụ ký tự NUL). `introduction` chỉ được dùng xuống dòng `\n` và tab; `companyName`, `tagline` phải nằm trên một dòng, không có tab;
- có ký tự ngắt dòng/ngắt đoạn Unicode `U+2028`, `U+2029` (ở cả ba trường, vì xuống dòng duy nhất được lưu là `\n`);
- có ký tự điều khiển hướng chữ (bidi) `U+202A`–`U+202E`, `U+2066`–`U+2069`, vốn làm chữ hiện ra theo thứ tự khác với thứ tự đã gõ. Dấu hướng `U+200E`, `U+200F` vẫn được nhận;
- không có ký tự nào nhìn thấy được, ví dụ chỉ gồm khoảng trắng độ rộng 0 (`U+200B`), `U+2060` hoặc `U+FEFF`: trang công khai sẽ hiện tên hoặc nội dung trống. Khẩu hiệu chỉ gồm khoảng trắng thường được lưu thành null, nhưng khẩu hiệu chỉ gồm ký tự vô hình như vậy bị từ chối. Ký tự định dạng vô hình nằm giữa chữ thì vẫn được giữ (ví dụ `U+200D` ghép emoji gia đình 👨‍👩‍👧);
- có nửa emoji đứng lẻ (UTF-16 surrogate lẻ, chỉ gửi được bằng escape JSON), vì không lưu đúng nguyên văn được.

Các dạng sau vẫn hợp lệ vì trình duyệt không coi là HTML: `lương < 20 triệu`, `5 <= 10`, `<3`, `&lt;script&gt;` (được giữ nguyên là chữ). Vì vậy không viết liên kết dạng `<https://...>`; hãy ghi thẳng địa chỉ.

Frontend vẫn phải hiển thị nội dung như văn bản (`{{ }}`/`textContent`, không dùng `v-html`/`innerHTML`). Nếu sau này dùng thư viện Markdown, phải tắt HTML thô và chặn liên kết `javascript:`; backend không kiểm tra cú pháp Markdown.

## Xem trước

`POST /company-profile/preview` nhận body giống hệt PUT, kiểm tra dữ liệu giống hệt PUT (cùng lỗi 400) và trả **200** với đúng cấu trúc của `GET /public/company-profile`. API này **không ghi gì** vào database: không tạo/sửa trang, không đổi `updatedAt`, trang công khai giữ nguyên. Backend dùng chung một hàm dựng kết quả cho xem trước và trang công khai, nên nội dung đã xem trước, sau khi PUT, sẽ hiển thị y hệt cho ứng viên.

## Trang công khai

`GET /public/company-profile` trả nội dung đã lưu, chỉ gồm các trường được công khai, không có `createdAt`, `updatedAt`, `updatedBy`:

```json
{
  "companyName": "Công ty TTCS",
  "tagline": "Nơi phát triển tài năng",
  "introduction": "Chúng tôi xây dựng phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm",
  "logo": { "id": "00000000-0000-0000-0000-000000000011", "width": 400, "height": 200 },
  "images": [
    { "id": "00000000-0000-0000-0000-000000000012", "width": 800, "height": 600 },
    { "id": "00000000-0000-0000-0000-000000000013", "width": 1200, "height": 800 }
  ]
}
```

`tagline` và `logo` có thể là `null`; `images` có thể là mảng rỗng và luôn theo thứ tự hiển thị. `width`/`height` là kích thước pixel của ảnh, giúp giao diện giữ chỗ trước khi ảnh tải xong. Response chỉ có ID ảnh; API trả byte ảnh và đường dẫn ảnh thuộc task 238 (tải ảnh và logo). Trước lần lưu đầu, API trả **404** `COMPANY_PROFILE_NOT_FOUND`; cổng tuyển dụng nên ẩn mục giới thiệu hoặc hiện nội dung mặc định.

## Ảnh và logo

Task này chưa có API tải ảnh (task 238). `logoMediaId` và `imageIds` phải là UUID của ảnh đã có trong bảng `company_media`: logo phải có loại `LOGO`, ảnh giới thiệu phải có loại `IMAGE`. Ảnh không tồn tại hoặc sai loại bị từ chối ngay (400 `INVALID_COMPANY_LOGO` hoặc `INVALID_COMPANY_IMAGE`), ở cả PUT lẫn xem trước, trước khi chạm tới khóa ngoại của V11.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/trống/quá dài, có HTML hoặc ký tự điều khiển, không có ký tự nhìn thấy được, quá 10 ảnh, ảnh lặp hoặc phần tử null; `fieldErrors` chỉ ra trường sai|
|400|INVALID_JSON|JSON sai, UUID sai định dạng hoặc có trường ngoài hợp đồng|
|400|INVALID_COMPANY_LOGO|`logoMediaId` không tồn tại hoặc không phải ảnh loại `LOGO`|
|400|INVALID_COMPANY_IMAGE|Một phần tử của `imageIds` không tồn tại hoặc không phải ảnh loại `IMAGE`|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa (cả khi gửi token hỏng tới API công khai)|
|403|FORBIDDEN|Thiếu `JOB_POSTINGS_WRITE_ALL` (ba API của trình soạn)|
|404|COMPANY_PROFILE_NOT_FOUND|Trang chưa được lưu lần nào (`GET /company-profile`, `GET /public/company-profile`)|

Khi lỗi, nội dung trang không thay đổi.

## Database và phạm vi

Dùng ba bảng `company_profile`, `company_profile_images`, `company_media` của V11 và quyền `JOB_POSTINGS` của V3; task này không thêm migration, quyền mới hay biến `.env`. Không có DELETE trang. API tải và trả ảnh/logo thuộc task 238; giao diện soạn và xem trước thuộc task 234, 235, 239.
