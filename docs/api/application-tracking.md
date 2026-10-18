# Theo dõi hồ sơ ứng tuyển

Phạm vi TKNHTTDNB1-340 (ánh xạ trạng thái nội bộ thành trạng thái công khai) và TKNHTTDNB1-339 (tra cứu bằng mã tra cứu và email), story TKNHTTDNB1-44 (S4-05): ứng viên tra cứu trạng thái hồ sơ của mình bằng mã tra cứu, để biết hồ sơ đã được xem chưa mà không phải gọi điện hỏi. Task 340 thêm quy tắc ánh xạ dùng chung `PublicApplicationStatus` (mục "Trạng thái công khai (task 340)"), không thêm API, migration hay mã quyền. Task 339 thêm API công khai `POST /api/v1/public/applications/lookup` (mục "Tra cứu hồ sơ (task 339)") và migration V31 cho hạn mức theo IP, không thêm mã quyền. Hồ sơ được nộp qua [API nhận hồ sơ công khai](public-applications.md) (task 326); mã tra cứu được trả trong response nộp hồ sơ và gửi trong [email xác nhận](application-confirmation.md) (task 329).

Tiêu chí của story S4-05:

- "Nhập mã tra cứu và email để xem trạng thái": đã có (339). Ứng viên gửi mã tra cứu và email đã dùng khi nộp hồ sơ trong thân một yêu cầu POST, không cần tài khoản.
- "Chỉ hiển thị trạng thái ở mức tổng quát: Đã nhận, Đang xem xét, Mời phỏng vấn, Kết thúc": quy tắc đã có (340): mỗi giai đoạn nội bộ hiện ra đúng một trong bốn trạng thái này. API nộp hồ sơ và API tra cứu (339) đều lấy trạng thái từ quy tắc này.
- "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn": API tra cứu chỉ trả tên vị trí, thời điểm nộp và trạng thái công khai (339); test ghim điều này là task 341.

## Trạng thái công khai (task 340)

Hồ sơ có bảy giai đoạn nội bộ (`ApplicationStage`, task 325), dành cho người tuyển dụng. Ứng viên (API nộp hồ sơ; tra cứu, task 339; rút hồ sơ, task 345) và người giới thiệu (danh sách hồ sơ đã giới thiệu, task 352) chỉ thấy một trong bốn trạng thái công khai của `PublicApplicationStatus`:

| Giai đoạn nội bộ (`stage`) | Trạng thái công khai (`status`) | Tên hiển thị (`statusName`) |
|---|---|---|
| `NEW` (Hồ sơ mới) | `RECEIVED` | Đã nhận |
| `SCREENING` (Sàng lọc) | `IN_REVIEW` | Đang xem xét |
| `INTERVIEW` (Phỏng vấn), `OFFER` (Đề xuất offer) | `INTERVIEWING` | Mời phỏng vấn |
| `HIRED` (Nhận việc), `REJECTED` (Loại), `WITHDRAWN` (Ứng viên rút) | `COMPLETED` | Kết thúc |

Quy tắc:

- Mã giai đoạn nội bộ và tên của nó (cột đầu) không bao giờ có trong response hay email gửi ứng viên hoặc người giới thiệu; chỉ có `status` và `statusName` ở hai cột sau. Không mã hay tên công khai nào trùng một mã hay tên nội bộ (test ghim), nên hai bộ giá trị không lẫn được với nhau.
- Chỉ một hàm quyết định trạng thái công khai: `PublicApplicationStatus.of(stage)`. Mọi API trả trạng thái công khai gọi hàm này, không tự viết lại bảng: API nộp hồ sơ (response 201 có `status: "RECEIVED"`, `statusName: "Đã nhận"`; từ task 340 lấy từ hàm này, JSON không đổi), tra cứu (339), rút hồ sơ (345) và danh sách của người giới thiệu (352).
- `switch` trong hàm liệt kê đủ bảy giai đoạn, không có nhánh `default`. Thêm một giai đoạn vào `ApplicationStage` thì code không biên dịch được cho tới khi có người quyết định giai đoạn đó hiện là trạng thái nào; `PublicApplicationStatusTest` có `switch` riêng, nên test cũng không biên dịch được cho tới khi ghi cùng quyết định.
- Trạng thái công khai chỉ đi tới, không lùi. Bốn giá trị được khai báo theo thứ tự ứng viên đi qua. Đi từ `NEW` tới `OFFER`, bước công khai không bao giờ giảm; từ bất kỳ giai đoạn nào còn trong quy trình sang một giai đoạn kết thúc, bước công khai luôn tăng (test ghim).
- `OFFER` hiện "Mời phỏng vấn". Offer đến sau phỏng vấn: nếu `OFFER` là "Đang xem xét", ứng viên đã thấy "Mời phỏng vấn" sẽ thấy hồ sơ lùi một bước; nếu là "Kết thúc", ứng viên đang chờ offer tưởng hồ sơ đã xong, và lúc nhận việc hay bị loại thì trạng thái không đổi. Offer chưa phải kết quả; kết quả là nhận việc, bị loại hoặc rút hồ sơ.
- "Kết thúc" không cho biết kết quả: trúng tuyển, không phù hợp và đã rút hiện giống nhau.
- Trạng thái chỉ theo giai đoạn của chính hồ sơ: tạm dừng, đóng hay hủy yêu cầu tuyển dụng (kể cả khi tin của yêu cầu bị đóng theo) không đổi trạng thái công khai, vì hồ sơ giữ nguyên giai đoạn (câu hỏi 20, ý 5).
- Sprint 4 chưa có API chuyển giai đoạn cho người tuyển dụng (S5-02), nên tới Sprint 5 hồ sơ hiện "Đã nhận", trừ hồ sơ ứng viên tự rút (task 345) hiện "Kết thúc".

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 24, ý (1)–(4).

### Ghi chú cho frontend (task 340)

- Màn hình giới thiệu (task 348, 349) đã dùng đúng bốn mã này (`ReferralStatus`), nhưng `mapToGeneralStatus` thiếu `INTERVIEW` (rơi về `RECEIVED`) và xếp `OFFER` vào `COMPLETED`. Khi có danh sách của task 352, hiển thị `status` và `statusName` của server, không tự ánh xạ.
- Trang tra cứu (task 337, 338) đang dùng sáu mã (`RECEIVED`, `IN_REVIEW`, `INTERVIEW`, `HIRED`, `REJECTED`, `WITHDRAWN`); API tra cứu (339) chỉ trả bốn mã ở bảng trên, kèm `statusName`.

## Tra cứu hồ sơ (task 339)

Ứng viên không có tài khoản. Muốn xem trạng thái một lần ứng tuyển, ứng viên phải biết **cả hai**: mã tra cứu của đúng lần ứng tuyển đó (43 ký tự, nhận trong response nộp hồ sơ và trong email xác nhận) và email đã dùng khi nộp hồ sơ. Hai giá trị chỉ được gửi trong thân JSON của một yêu cầu POST, không bao giờ trên đường dẫn hay query string, để không lọt vào log máy chủ, lịch sử trình duyệt hay header `Referer`.

```http
POST /api/v1/public/applications/lookup
Content-Type: application/json

{"trackingCode":"<43 ký tự base64url>","email":"NguyenVanA@Example.com "}
```

```http
HTTP/1.1 200
Cache-Control: no-store
Content-Type: application/json

{"postingTitle":"Lập trình viên Java","receivedAt":"2026-10-15T03:00:00Z","status":"RECEIVED","statusName":"Đã nhận"}
```

- `postingTitle`: tên vị trí **lúc nộp hồ sơ** (`job_applications.posting_title`); đổi tên tin sau đó không đổi giá trị này.
- `receivedAt`: thời điểm hệ thống nhận hồ sơ (UTC, ISO 8601).
- `status`, `statusName`: trạng thái công khai của mục "Trạng thái công khai (task 340)", không bao giờ là giai đoạn nội bộ.
- Không có gì khác: không có mã hồ sơ, mã yêu cầu tuyển dụng, mã tin, giai đoạn nội bộ, họ tên, email, số điện thoại, nguồn biết tin, thư giới thiệu, CV hay tên nhân viên. Trạng thái của yêu cầu tuyển dụng hay tin tuyển dụng (tạm dừng, đã đóng) không được đọc.
- Mã tra cứu và email được bỏ ký tự vô hình ở hai đầu (khoảng trắng, khoảng trắng không ngắt, ký tự rộng 0) theo cùng quy tắc với API nộp hồ sơ; email không phân biệt hoa thường (`Locale.ROOT`, như lúc lưu). Một giá trị chỉ gồm ký tự vô hình được coi là không nhập.
- API không bao giờ trả 401 hay 403: route là `permitAll`, và Bearer token cũ bị bỏ qua (frontend gắn Bearer đã lưu vào mọi request; nếu không bỏ qua, một token hết hạn sẽ đưa ứng viên sang trang đăng nhập). Gửi kèm token hợp lệ của nhân viên cũng không đổi gì.

Thứ tự kiểm tra: CORS → bộ lọc bảo vệ (`no-store`; query string; kiểu nội dung; kích thước; đọc trọn thân, tối đa 8 KiB; cổng đồng thời) → bỏ qua Bearer → `permitAll` → đọc JSON (400) → kiểm dữ liệu (400) → mã đúng cách viết chuẩn (400) → địa chỉ IP của client (400) → hạn mức (429/503) → một câu SQL (404/200) → lỗi bất ngờ (503).

### Lỗi

Mọi lỗi có thân `{code, message, fieldErrors}` và `Cache-Control: no-store`, không lặp lại email, mã tra cứu, IP hay chi tiết SQL.

| HTTP | `code` | Khi nào / thông báo |
|---|---|---|
| 400 | `VALIDATION_ERROR` | "Vui lòng kiểm tra dữ liệu đã nhập." kèm `fieldErrors`: `trackingCode` "Vui lòng nhập mã tra cứu." / "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'." / "Mã tra cứu không hợp lệ." (đúng 43 ký tự nhưng không phải cách viết chuẩn); `email` "Vui lòng nhập email đã dùng khi nộp hồ sơ." / "Email không hợp lệ." / "Email tối đa 254 ký tự." |
| 400 | `VALIDATION_ERROR` | Có query string: "Không gửi mã tra cứu hoặc email trên đường dẫn." (bộ lọc, trước khi đọc thân) |
| 400 | `INVALID_JSON` | Thân không đọc được, thiếu thân, có trường lạ, thân không khai độ dài (chunked) dài quá 8 KiB, hoặc kết nối bị ngắt trước khi gửi hết thân |
| 400 | `APPLICATION_CLIENT_IP_INVALID` | Sau proxy tin cậy mà thiếu hoặc sai `X-Forwarded-For`: "Không thể xác định địa chỉ truy cập. Vui lòng thử lại sau." |
| 404 | `APPLICATION_LOOKUP_NOT_FOUND` | "Mã tra cứu hoặc email không đúng.", `fieldErrors` rỗng: mã không tồn tại, email sai, mã đã bị thu hồi. Cùng status, thân và header |
| 413 | `APPLICATION_TRACKING_REQUEST_TOO_LARGE` | `Content-Length` lớn hơn 8192: "Dữ liệu gửi lên quá lớn." |
| 415 | `APPLICATION_TRACKING_JSON_REQUIRED` | Không phải `application/json` (được kèm `charset`): "Vui lòng gửi dữ liệu dạng JSON." |
| 429 | `APPLICATION_TRACKING_RATE_LIMIT_EXCEEDED` | Quá hạn mức: "Bạn đã tra cứu hoặc rút hồ sơ quá số lượt cho phép. Vui lòng thử lại sau." kèm `Retry-After` |
| 503 | `APPLICATION_TRACKING_BUSY` | Tiến trình đang xử lý đủ `max-concurrent` yêu cầu theo dõi hồ sơ: "Hệ thống đang bận. Vui lòng thử lại sau." kèm `Retry-After: 1`; không ghi gì |
| 503 | `APPLICATION_TRACKING_UNAVAILABLE` | "Chưa thể xử lý yêu cầu lúc này. Vui lòng thử lại sau.": lỗi kho hạn mức, câu lệnh quá 3 giây hoặc lỗi bất ngờ |

### Vì sao POST, vì sao cần cả hai giá trị

- Mã tra cứu là bí mật: ai có mã của một lần ứng tuyển thì xem được trạng thái của nó (và, từ task 345, rút được hồ sơ). Mã trên URL sẽ nằm trong log của proxy và máy chủ, lịch sử trình duyệt và header `Referer`; thân POST thì không. Liên kết trong email đặt mã sau dấu `#` (`#ma=<mã>`), phần trình duyệt không gửi lên máy chủ.
- Email là yếu tố thứ hai: một mã bị lộ một mình (ảnh chụp màn hình, chuyển tiếp nhầm) không đủ để xem. Không có cách tra cứu chỉ bằng email hay số điện thoại.
- Mã gồm 32 byte ngẫu nhiên (256 bit, task 329), viết bằng Base64URL không có dấu `=`. Chỉ cách viết chuẩn được nhận: ký tự cuối mang 2 bit không dùng, nên cùng 32 byte có bốn cách viết mà bộ giải mã dễ dãi đều chấp nhận; server giải mã, mã hóa lại rồi so sánh, khác thì trả 400.
- Database chỉ lưu SHA-256 của 32 byte (không phải của chuỗi). Server tính lại SHA-256 rồi tìm bằng **một** câu SQL `WHERE tracking_code_hash = ? AND normalized_email = ? AND tracking_revoked_at IS NULL`, qua chỉ mục UNIQUE của hash. Mã không tồn tại, email sai và mã đã thu hồi đi cùng một đường, không nhánh Java nào khác nhau, nên câu trả lời 404 giống hệt nhau (status, thân, header): người dò không biết mình sai mã hay sai email.
- Một email có nhiều lần ứng tuyển (nhiều yêu cầu tuyển dụng) thì mỗi mã chỉ cho thấy đúng lần ứng tuyển của nó.
- Mã không hết hạn và vẫn tra cứu được sau khi rút hồ sơ.

### Hạn mức theo IP (V31)

- Tối đa `app.application-tracking.max-attempts` lượt (mặc định **30**, biến `APPLICATION_TRACKING_MAX_ATTEMPTS`, từ 1 đến 1000) trong **60 phút trượt** cho mỗi địa chỉ IPv4 hoặc mỗi dải IPv6 **/64**; từ task 345 dùng chung với rút hồ sơ. Một thuê bao IPv6 thường có cả một dải /64, nên nếu tính theo từng địa chỉ thì đổi địa chỉ là có lượt mới. Dải /64 được lưu bằng địa chỉ đầu dải (64 bit thấp bằng 0), ví dụ `2001:db8:0:1::5` thành `2001:db8:0:1::`. Địa chỉ IPv4 viết dạng IPv6 (`::ffff:192.0.2.7`) dùng chung lượt với `192.0.2.7`.
- Hạn mức nằm ở bảng riêng (`application_tracking_ip_quotas`, `application_tracking_ip_attempts`), khác bảng của API nộp hồ sơ (V27, 5 lượt mỗi giờ, task 328): tra cứu không làm hết lượt nộp hồ sơ, và ngược lại. API nộp hồ sơ vẫn tính IPv6 theo từng địa chỉ.
- Mọi lượt đúng dạng đều được tính, dù tìm thấy (200) hay không (404). Yêu cầu sai dạng (400, 413, 415) không được tính, vì việc kiểm dữ liệu chạy trước hạn mức; lượt bị từ chối (429) không được ghi và không kéo dài thời gian chờ. Một lượt ở thời điểm `t` hết tính khi `t <= bây giờ - 3600 giây`, như V27.
- `Retry-After` là số giây (làm tròn lên, tối thiểu 1) tới khi số lượt còn tính xuống dưới hạn mức. Nếu hạn mức vừa bị hạ trong khi còn nhiều lượt hơn hạn mức mới, server chờ tới lượt mà khi nó hết tính thì số lượt còn lại nhỏ hơn hạn mức, không chỉ chờ lượt cũ nhất.
- Giao dịch hạn mức là REQUIRES_NEW, READ COMMITTED, tối đa 5 giây, `lock_timeout` 2 giây, `statement_timeout` 3 giây, commit **trước** câu đọc hồ sơ. Khóa duy nhất là dòng của bucket (`FOR UPDATE`), nên các lượt cùng một bucket đi lần lượt trong vài mili giây. Lỗi kho hạn mức trả 503 và không tính là lượt.
- Dọn bucket: mỗi `app.application-tracking.cleanup-delay-ms` (mặc định 60 000 ms) xóa các bucket không có lượt nào trong 60 phút, từng mẻ 500 bucket với `SKIP LOCKED`, lặp khi mẻ vừa xóa đủ 500, tối đa 20 mẻ (10 000 bucket) mỗi lần chạy; lượt của bucket bị xóa đi theo `ON DELETE CASCADE`. Lỗi chỉ ghi log một câu cố định.
- Những người dùng chung một IP (NAT công ty, Wi-Fi) dùng chung hạn mức; mặc định 30 để một văn phòng dùng chung IP vẫn tra cứu được. Mã 256 bit không đoán được, nên hạn mức chỉ giới hạn mỗi client được hỏi bao nhiêu lần; nó không giới hạn tải database (mỗi lượt là một giao dịch ghi ngắn). Việc đó do cổng đồng thời bên dưới.

### Cổng đồng thời và bộ lọc bảo vệ

`ApplicationTrackingRequestFilter` chạy trong Spring Security, sau CORS, trước Bearer và trước khi Spring đọc JSON (cùng chỗ với bộ lọc của API nộp hồ sơ):

1. Đặt `Cache-Control: no-store`.
2. Từ chối query string (400): mã và email không được có trên URL.
3. Từ chối kiểu nội dung khác `application/json` (415) và `Content-Length` lớn hơn 8192 byte (413). Spring không tự giới hạn kích thước thân JSON của yêu cầu không đăng nhập.
4. Đọc trọn thân vào bộ nhớ, tối đa 8192 byte, **trước khi lấy giấy phép**. Thân không khai độ dài (chunked) dài quá 8192 byte bị cắt ngay khi vượt (không đọc tiếp), và kết nối bị ngắt (hoặc ngừng gửi tới khi máy chủ hết thời gian chờ đọc) trước khi gửi hết thân: cả hai trả 400 `INVALID_JSON`, cùng thân lỗi với JSON hỏng. 8 KiB đủ cho một lý do rút hồ sơ 500 ký tự sau khi thoát ký tự (task 345). Vì thân được đọc trước, một client gửi thân thật chậm chỉ giữ kết nối của chính nó, không giữ giấy phép nào: vài kết nối chậm không chặn được tra cứu (và rút hồ sơ) của ứng viên khác.
5. Lấy một trong `app.application-tracking.max-concurrent` giấy phép (mặc định **4**, biến `APPLICATION_TRACKING_MAX_CONCURRENT`, từ 1 đến 16) **trước mọi truy cập database**. Hết giấy phép thì trả ngay 503 `APPLICATION_TRACKING_BUSY` với `Retry-After: 1`, không ghi lượt hạn mức. Giấy phép được giữ từ khi đã đọc xong thân tới khi trả xong response và luôn được trả lại. Nhờ vậy API công khai này không bao giờ giữ quá `max-concurrent` kết nối database của một tiến trình, dù client gửi bao nhiêu yêu cầu.

Câu đọc hồ sơ chạy trong giao dịch chỉ đọc với `statement_timeout` 3 giây: nếu bảng hồ sơ bị khóa lâu, ứng viên nhận 503 `APPLICATION_TRACKING_UNAVAILABLE` sau khoảng 3 giây.

### Proxy tin cậy

Địa chỉ client do `ApplicationClientIpResolver` của task 328 xác định, với cùng cấu hình `APPLICATION_INTAKE_TRUSTED_PROXIES` (xem [giới hạn lượt gửi hồ sơ theo IP](application-ip-quota.md#xác-định-ip)): không có proxy tin cậy thì lấy địa chỉ socket; có thì lấy địa chỉ không tin cậy ngoài cùng bên phải của `X-Forwarded-For`, bỏ qua giá trị giả mạo bên trái. Thiếu hoặc sai header từ proxy tin cậy trả 400 `APPLICATION_CLIENT_IP_INVALID` với thông báo trung tính của tra cứu và không ghi lượt. Máy chủ chạy sau Caddy ở 127.0.0.1: nếu không khai proxy, mọi ứng viên dùng chung một bucket (30 lượt mỗi giờ cho tất cả), nên cần cấu hình đúng proxy trước khi mở trang tra cứu.

### Thu hồi mã (task 339)

Sprint 4 chưa có API thu hồi mã. Khi một mã bị lộ, HR nhờ quản trị hệ thống chạy câu dưới đây, từ **mã hồ sơ nội bộ** (không bao giờ từ mã tra cứu):

```sql
UPDATE job_applications SET tracking_revoked_at = now()
WHERE id = '<applicationId>' AND tracking_revoked_at IS NULL;
```

Sau đó tra cứu (và rút hồ sơ, task 345) bằng mã đó trả 404 như mã không tồn tại. Chưa có cách cấp mã mới. Ai được yêu cầu thu hồi, và có cần API thu hồi, cấp lại mã không, là câu hỏi 24, ý (10) cho BA/PO.

### Kiểm thử (task 339)

- `TrackingCodeTest`: mã chuẩn được nhận; ba cách viết khác của ký tự cuối, 42/44 ký tự, `+`, `/`, `=` bị từ chối; hash là SHA-256 của 32 byte, khác SHA-256 của chuỗi.
- `TrackingBucketsTest`: IPv4 giữ nguyên; các địa chỉ trong một /64 chung bucket; IPv4 dạng IPv6 về IPv4; chỉ nhận địa chỉ dạng số.
- `ApplicationTrackingRequestFilterTest`: giới hạn 1..16 của `max-concurrent`; query string, kiểu nội dung, kích thước; cổng bận và việc trả giấy phép; thân chunked bị cắt sau 8 KiB mà không đọc tiếp; thân bị ngắt → 400 `INVALID_JSON`, không giữ giấy phép; thân được đọc xong trước khi lấy giấy phép (yêu cầu khác vào được trong lúc thân còn đang tới); controller đọc thân qua stream hoặc reader theo `charset`.
- `ApplicationSubmissionTest`: email của tra cứu được chuẩn hóa đúng như lúc lưu (khoảng trắng không ngắt, ký tự rộng 0, BOM, chữ hoa, `İ`).
- `ApplicationLookupIntegrationTest` (HTTP thật, `max-concurrent=1`): mã do `PostgresApplicationReceiptWriter` cấp, email khác hoa thường và có ký tự vô hình ở hai đầu → 200, đúng bốn khóa; mỗi giai đoạn hiện đúng trạng thái công khai; mã lạ, email sai, mã đã thu hồi cùng một 404; một email hai yêu cầu; tên vị trí là bản chụp, yêu cầu tạm dừng hay tin đã đóng không đổi gì; các lỗi 400/413/415 không tính lượt; Bearer cũ, không có Bearer, Bearer hợp lệ của nhân viên đều 200; bảng hồ sơ bị khóa → 503 sau khoảng 3 giây; yêu cầu thứ hai khi yêu cầu đầu đang chờ → 503 `APPLICATION_TRACKING_BUSY`, `Retry-After: 1`, không ghi lượt; một socket gửi header và đầu thân rồi dừng không giữ giấy phép: ba lượt tra cứu khác vẫn 200, lượt chậm được trả 200 và được tính khi gửi hết thân; thân chunked quá 8 KiB có cùng thân lỗi `INVALID_JSON` với JSON hỏng.
- `ApplicationTrackingQuotaIntegrationTest` (`max-attempts=3`): lượt thứ tư → 429; 200 và 404 đều tính, 400 không tính; lượt bị từ chối không được ghi; cửa sổ trượt; hạn mức bị hạ; tách khỏi hạn mức nộp hồ sơ; hai yêu cầu đồng thời; dọn 1 200 bucket trong một lần chạy, tối đa 20 mẻ; CHECK của V31; bảng hạn mức bị khóa → 503.
- `ApplicationTrackingTrustedProxyIntegrationTest`: `X-Forwarded-For` qua proxy tin cậy, header thiếu hoặc sai, IPv6 /64, IPv4 dạng IPv6.
- `ApiAuthorizationMatrixIntegrationTest`: dòng công khai `POST /api/v1/public/applications/lookup` (400 với thân `{}` cho mọi vai trò, khi không đăng nhập và với Bearer cũ); `GET` cùng đường dẫn bị từ chối.

### Ghi chú cho frontend (task 339)

- Trang tra cứu (`src/services/applicationLookupService.ts`) đang gọi `GET /public/applications/lookup?method=applicationCode|contact&value=`: một yếu tố, bí mật trên query. Cần đổi sang `POST /api/v1/public/applications/lookup` với thân `{trackingCode, email}`; server không có route GET (bị từ chối như mọi URL không khai báo: 401 khi không có token, 403 khi có).
- Trang tra cứu đang có sáu trạng thái và `timeline[].note`; API chỉ trả bốn trạng thái kèm `statusName`, không có dòng thời gian và không có mã hồ sơ.
- Ô nhập mã đang gợi ý "APP-2026-00125"; mã thật dài 43 ký tự.
- Liên kết trong email mở `APPLICATION_TRACKING_PAGE_URL` (mặc định `/tra-cuu`; frontend hiện ở `/careers/applications/lookup`, nên thêm route `/tra-cuu` hoặc đặt biến môi trường tới trang hiện có). Đọc `#ma=` để điền sẵn mã, hỏi email, rồi xóa phần sau `#` bằng `history.replaceState`; không tự gọi API khi tải trang.
- Hiện `message` của server cho 404. 429 và 503 `APPLICATION_TRACKING_BUSY` có `Retry-After` (giây): hiện thông báo chờ và chỉ thử lại sau số giây đó. 503 `APPLICATION_TRACKING_UNAVAILABLE` không có `Retry-After`: frontend tự chọn thời gian chờ trước khi thử lại. API không trả 401/403, nên không đưa người dùng sang `/login` hay `/unauthorized`.
- Không lưu mã hay email vào `localStorage`/`sessionStorage`; phục vụ trang tra cứu với `Content-Security-Policy: frame-ancestors 'none'`.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 24, ý (5)–(10).
