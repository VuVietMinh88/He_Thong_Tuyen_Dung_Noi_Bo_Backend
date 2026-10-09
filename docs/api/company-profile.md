# API trang giới thiệu công ty

Phạm vi TKNHTTDNB1-237 (nội dung trang) và TKNHTTDNB1-238 (tải ảnh, logo), story S2-09 (TKNHTTDNB1-28). URL dùng tiền tố `/api/v1`. Mọi response JSON thành công và các lỗi `COMPANY_*`, `INVALID_COMPANY_*`, `UNSUPPORTED_COMPANY_MEDIA_TYPE`, `FILE_TOO_LARGE` dùng `Cache-Control: no-store`; riêng byte ảnh của API ảnh công khai được phép cache (mục [Xem ảnh](#xem-ảnh)).

| API | Công dụng | Quyền |
|---|---|---|
|`GET /company-profile`|HR mở trình soạn: đọc nội dung đã lưu|`JOB_POSTINGS_WRITE_ALL`|
|`PUT /company-profile`|Lưu (lần đầu là tạo) toàn bộ nội dung trang|`JOB_POSTINGS_WRITE_ALL`|
|`POST /company-profile/preview`|Xem trước đúng như trang công khai, **không lưu**|`JOB_POSTINGS_WRITE_ALL`|
|`GET /public/company-profile`|Cổng tuyển dụng hiển thị trang cho ứng viên|Công khai, không cần token|
|`POST /company-profile/media`|Tải lên một logo hoặc ảnh giới thiệu (`multipart/form-data`)|`JOB_POSTINGS_WRITE_ALL`|
|`GET /company-profile/media/{id}`|Trình soạn và xem trước lấy byte của bất kỳ ảnh đã tải nào|`JOB_POSTINGS_WRITE_ALL`|
|`GET /public/company-media/{id}`|Cổng tuyển dụng lấy byte ảnh mà trang đã lưu đang dùng|Công khai, không cần token|

Các API `/company-profile...` gửi `Authorization: Bearer <accessToken>`. Ma trận hiện tại cấp `JOB_POSTINGS_WRITE_ALL` cho ADMIN và HR_MANAGER. RECRUITER chỉ có `JOB_POSTINGS_WRITE_SCOPED` (tin tuyển dụng của vị trí được phân công) nên bị 403: trang giới thiệu dùng chung cho cả công ty, không có phạm vi phân công. HIRING_MANAGER, APPROVER có `JOB_POSTINGS_READ_ALL` nhưng cũng không đọc được bản trong trình soạn; họ xem trang qua API công khai như ứng viên. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu; khi lưu trang hoặc tải ảnh, service khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái, phiên, hạn JWT và quyền.

Hai API `/public/...` không cần token. Giống `GET /health`, nếu gửi kèm Bearer hỏng hoặc hết hạn thì bộ lọc bảo mật vẫn trả 401, nên cổng tuyển dụng nên gọi các API này **không** kèm header `Authorization`.

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
  "logo": {
    "id": "00000000-0000-0000-0000-000000000011",
    "width": 400,
    "height": 200,
    "url": "/api/v1/public/company-media/00000000-0000-0000-0000-000000000011"
  },
  "images": [
    {
      "id": "00000000-0000-0000-0000-000000000012",
      "width": 800,
      "height": 600,
      "url": "/api/v1/public/company-media/00000000-0000-0000-0000-000000000012"
    }
  ]
}
```

`tagline` và `logo` có thể là `null`; `images` có thể là mảng rỗng và luôn theo thứ tự hiển thị. `width`/`height` là kích thước pixel của ảnh, giúp giao diện giữ chỗ trước khi ảnh tải xong. `url` là đường dẫn lấy byte ảnh (mục [Xem ảnh](#xem-ảnh)). Xem trước trả cùng cấu trúc nên ảnh chưa lưu cũng có `url`, nhưng `url` đó chỉ mở được sau khi lưu trang. Trước lần lưu đầu, API trả **404** `COMPANY_PROFILE_NOT_FOUND`; cổng tuyển dụng nên ẩn mục giới thiệu hoặc hiện nội dung mặc định.

## Ảnh và logo

### Tải ảnh lên

`POST /company-profile/media` nhận `multipart/form-data` (giống form có `<input type="file">`) với hai trường, trả **201**:

| Trường form | Quy tắc |
|---|---|
|file|Bắt buộc, một tệp ảnh JPG hoặc PNG, tối đa 5 MB (5.242.880 byte) và 6000 x 6000 pixel|
|kind|Bắt buộc, viết hoa đúng như sau: `LOGO` (để dùng làm `logoMediaId`) hoặc `IMAGE` (để dùng trong `imageIds`)|

```json
{
  "id": "00000000-0000-0000-0000-000000000011",
  "kind": "LOGO",
  "contentType": "image/png",
  "sizeBytes": 18234,
  "width": 400,
  "height": 200,
  "url": "/api/v1/public/company-media/00000000-0000-0000-0000-000000000011"
}
```

Backend kiểm tệp theo thứ tự:

1. **Dung lượng.** Tệp trên 5 MB bị chặn ngay khi server đọc request, trước khi vào controller: 413 `FILE_TOO_LARGE`. Giới hạn nằm trong `application.properties` (`spring.servlet.multipart.max-file-size=5MB`, `max-request-size=6MB`). Request trên 6 MB (ví dụ ảnh điện thoại 7-12 MB) bị từ chối ngay từ `Content-Length`, trước khi đọc body; `server.tomcat.max-swallow-size=20MB` cho Tomcat đọc bỏ phần body còn lại để client vẫn nhận đủ JSON 413. Request trên khoảng 20 MB thì Tomcat đóng kết nối: client gặp lỗi mạng (`fetch` ném `TypeError`), **không** có JSON 413. Vì vậy frontend phải kiểm `file.size <= 5 * 1024 * 1024` trước khi gửi và tự báo lỗi; 413 từ server chỉ là lớp chặn cuối.
2. **Loại tệp theo nội dung.** Backend đọc các byte đầu của tệp: PNG bắt đầu bằng `89 50 4E 47 0D 0A 1A 0A`, JPG bằng `FF D8 FF`. Tên tệp và `Content-Type` trình duyệt gửi **không** được tin. GIF, BMP, WebP, SVG, HTML hoặc tệp chữ đổi đuôi thành `.png` bị từ chối: 400 `UNSUPPORTED_COMPANY_MEDIA_TYPE`. SVG bị từ chối vì có thể chứa script.
3. **Kích thước pixel, trước khi giải mã.** Chiều rộng/cao được đọc từ phần đầu tệp; quá 6000 pixel ở một chiều bị từ chối: 400 `COMPANY_MEDIA_DIMENSIONS_TOO_LARGE`. Nhờ vậy một tệp nhỏ khai là ảnh khổng lồ ("bom giải nén") không làm đầy bộ nhớ server.
4. **Giải mã toàn bộ ảnh** bằng Java ImageIO. Tệp bị cắt, bị hỏng, hoặc chỉ có chữ ký ảnh rồi tới nội dung khác (ví dụ HTML) bị từ chối: 400 `INVALID_COMPANY_MEDIA`. Khi giải mã, server chỉ giữ khoảng 1000 x 1000 điểm ảnh trong bộ nhớ, kể cả với ảnh 6000 x 6000.

Thiếu `kind`, `kind` sai (ví dụ `logo` viết thường), thiếu `file` hoặc tệp rỗng trả 400 `VALIDATION_ERROR`. Gửi JSON thay cho `multipart/form-data` trả **415** với body lỗi mặc định của Spring Boot (không theo dạng `{code, message}`).

Ảnh hợp lệ được lưu nguyên byte gốc vào bảng `company_media` cùng loại (`kind`), kiểu nội dung (`contentType`, lấy từ nội dung tệp), số byte, chiều rộng/cao, thời điểm và người tải. Ảnh không bao giờ bị sửa sau khi tải; muốn thay logo thì tải ảnh mới rồi lưu trang với `logoMediaId` mới. Ảnh không còn dùng vẫn nằm trong database (chưa có API xóa ảnh hay cơ chế dọn ảnh thừa). Backend không mã hóa lại ảnh nên không xóa metadata trong tệp, ví dụ vị trí GPS của ảnh chụp bằng điện thoại; HR nên kiểm ảnh trước khi đưa lên trang công khai.

Tải ảnh **chưa** đưa ảnh lên trang. Ứng viên chỉ thấy ảnh sau khi `PUT /company-profile` lưu ID ảnh vào `logoMediaId` hoặc `imageIds`. Logo phải là ảnh `kind = LOGO`, ảnh giới thiệu phải là ảnh `kind = IMAGE`; ảnh không tồn tại hoặc sai loại bị từ chối ở cả PUT lẫn xem trước (400 `INVALID_COMPANY_LOGO` hoặc `INVALID_COMPANY_IMAGE`), trước khi chạm tới khóa ngoại của V11.

Ví dụ trên trình duyệt:

```js
const file = fileInput.files[0];
if (file.size > 5 * 1024 * 1024) {
  // Kiểm trước khi gửi: tệp quá lớn có thể làm server đóng kết nối thay vì trả 413.
  throw new Error("Ảnh tải lên tối đa 5 MB.");
}
const form = new FormData();
form.append("kind", "LOGO");
form.append("file", file);
const response = await fetch(`${apiBase}/api/v1/company-profile/media`, {
  method: "POST",
  // Không tự đặt Content-Type: trình duyệt tự thêm multipart/form-data kèm boundary.
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form,
});
```

Khi tải ảnh, service kiểm tệp trước, rồi khóa tài khoản người gọi, phiên và lấy advisory lock của trang giống khi lưu, sau đó kiểm lại trạng thái, phiên, hạn JWT và quyền rồi mới ghi ảnh.

### Xem ảnh

| API | Dùng cho | Trả ảnh nào | Cache |
|---|---|---|---|
|`GET /company-profile/media/{id}`|Trình soạn và màn hình xem trước (`JOB_POSTINGS_WRITE_ALL`)|Mọi ảnh đã tải, kể cả ảnh chưa lưu vào trang|`no-store`|
|`GET /public/company-media/{id}`|Cổng tuyển dụng, không cần token|Chỉ ảnh đang là logo hoặc ảnh giới thiệu của trang đã lưu|`max-age=3600, public` kèm `ETag`|

Cả hai trả **200** với byte ảnh gốc và các header:

- `Content-Type`: `image/png` hoặc `image/jpeg` đã lưu;
- `X-Content-Type-Options: nosniff`: trình duyệt không được tự đoán kiểu khác (ví dụ HTML) từ nội dung;
- `Content-Security-Policy: default-src 'none'; sandbox`: kể cả khi mở ảnh trực tiếp trong tab, trang đó không tải thêm gì và không chạy script.

Ảnh không tồn tại trả 404 `COMPANY_MEDIA_NOT_FOUND`; ID không phải UUID trả 400 `VALIDATION_ERROR`.

**Quyết định: ảnh chỉ công khai khi trang đã lưu đang dùng.** API công khai trả 404 `COMPANY_MEDIA_NOT_FOUND` cho ảnh vừa tải nhưng chưa lưu vào trang và cho ảnh đã bị bỏ khỏi trang, giống hệt ảnh không tồn tại. Như vậy HR thử ảnh trong trình soạn mà ứng viên không thấy, và bỏ một ảnh khỏi trang là ảnh đó thôi công khai. Vì ảnh công khai được cache 1 giờ, trình duyệt hoặc proxy đã tải ảnh có thể còn hiển thị ảnh tối đa 1 giờ sau khi ảnh bị bỏ khỏi trang. Hết 1 giờ, trình duyệt hỏi lại kèm `If-None-Match`: ảnh vẫn được dùng thì nhận **304** không có body, ảnh đã bị bỏ thì nhận 404.

`url` (trong response tải ảnh, trong `logo`/`images` của trang công khai và của xem trước) là đường dẫn tương đối của API ảnh công khai, chưa có địa chỉ server. Frontend ghép với địa chỉ API đang dùng, ví dụ `http://localhost:8080` + `url`, rồi đặt vào `<img src>` của trang công khai. Thẻ `<img>` không gửi được header `Authorization`, nên trình soạn và màn hình xem trước (có thể chứa ảnh chưa lưu) tải ảnh qua `GET /company-profile/media/{id}` bằng `fetch` kèm token, rồi hiển thị bằng `URL.createObjectURL(blob)`.

`width`/`height` là kích thước ghi trong tệp. Ảnh JPG chụp bằng điện thoại có thể kèm thông tin xoay (EXIF); khi đó trình duyệt có thể hiển thị ảnh xoay 90° so với `width`/`height`.

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Thiếu/trống/quá dài, có HTML hoặc ký tự điều khiển, không có ký tự nhìn thấy được, quá 10 ảnh, ảnh lặp hoặc phần tử null (`fieldErrors` chỉ ra trường sai); tải ảnh thiếu/sai `kind`, thiếu `file` hoặc tệp rỗng; ID ảnh trong đường dẫn không phải UUID|
|400|INVALID_JSON|JSON sai, UUID sai định dạng hoặc có trường ngoài hợp đồng|
|400|INVALID_COMPANY_LOGO|`logoMediaId` không tồn tại hoặc không phải ảnh loại `LOGO`|
|400|INVALID_COMPANY_IMAGE|Một phần tử của `imageIds` không tồn tại hoặc không phải ảnh loại `IMAGE`|
|400|UNSUPPORTED_COMPANY_MEDIA_TYPE|Tệp tải lên không bắt đầu bằng chữ ký PNG/JPG (GIF, BMP, WebP, SVG, HTML, tệp chữ...)|
|400|COMPANY_MEDIA_DIMENSIONS_TOO_LARGE|Ảnh rộng hoặc cao hơn 6000 pixel|
|400|INVALID_COMPANY_MEDIA|Ảnh bị cắt, hỏng hoặc không giải mã được|
|401|Lỗi xác thực/phiên|Thiếu, sai, hết hạn token; phiên thu hồi; người gọi bị khóa (cả khi gửi token hỏng tới API công khai)|
|403|FORBIDDEN|Thiếu `JOB_POSTINGS_WRITE_ALL` (các API `/company-profile...` của trình soạn)|
|404|COMPANY_PROFILE_NOT_FOUND|Trang chưa được lưu lần nào (`GET /company-profile`, `GET /public/company-profile`)|
|404|COMPANY_MEDIA_NOT_FOUND|Ảnh không tồn tại; với API ảnh công khai, cả ảnh chưa lưu vào trang hoặc đã bị bỏ khỏi trang|
|413|FILE_TOO_LARGE|Tệp tải lên lớn hơn 5 MB (request trên khoảng 20 MB bị đóng kết nối thay vì nhận 413, xem mục [Tải ảnh lên](#tải-ảnh-lên))|
|415|(lỗi mặc định của Spring Boot)|`POST /company-profile/media` không gửi dạng `multipart/form-data`|

Khi lỗi, nội dung trang không thay đổi và không có ảnh nào được lưu.

## Database và phạm vi

Dùng ba bảng `company_profile`, `company_profile_images`, `company_media` của V11 và quyền `JOB_POSTINGS` của V3; task 237 và 238 không thêm migration, quyền mới hay biến `.env`. Task 238 thêm giới hạn tải tệp `spring.servlet.multipart.*` và `server.tomcat.max-swallow-size` vào `application.properties`; giá trị swallow áp dụng cho mọi API (request có body không được đọc hết vẫn được Tomcat đọc bỏ tối đa 20 MB, thay vì 2 MB mặc định). Không có API xóa trang hay xóa ảnh. Giao diện soạn và xem trước thuộc task 234, 235, 239.
