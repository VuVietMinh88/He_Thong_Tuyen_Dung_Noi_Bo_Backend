# Theo dõi hồ sơ ứng tuyển

Phạm vi TKNHTTDNB1-340 (ánh xạ trạng thái nội bộ thành trạng thái công khai), TKNHTTDNB1-339 (tra cứu bằng mã tra cứu và email) và TKNHTTDNB1-341 (không tiết lộ nhận xét nội bộ, đánh giá hay tên người phỏng vấn), story TKNHTTDNB1-44 (S4-05): ứng viên tra cứu trạng thái hồ sơ của mình bằng mã tra cứu, để biết hồ sơ đã được xem chưa mà không phải gọi điện hỏi. Task 340 thêm quy tắc ánh xạ dùng chung `PublicApplicationStatus` (mục "Trạng thái công khai (task 340)"), không thêm API, migration hay mã quyền. Task 339 thêm API công khai `POST /api/v1/public/applications/lookup` (mục "Tra cứu hồ sơ (task 339)") và migration V31 cho hạn mức theo IP, không thêm mã quyền. Task 341 không đổi API: ghim bằng test và tài liệu rằng API công khai chỉ trả bốn trường được phép (mục "Dữ liệu công khai (task 341)"). Hồ sơ được nộp qua [API nhận hồ sơ công khai](public-applications.md) (task 326); mã tra cứu được trả trong response nộp hồ sơ và gửi trong [email xác nhận](application-confirmation.md) (task 329).

Story TKNHTTDNB1-45 (S4-06, "Rút hồ sơ") dùng lại chính mã tra cứu. Task TKNHTTDNB1-344 tạo liên kết rút hồ sơ `#rut=<mã>` và bước kiểm liên kết dưới khóa `ApplicationWithdrawalAccess` (mục "Liên kết rút hồ sơ (task 344)"); chưa thêm API, migration, mã quyền hay đoạn nào trong email. Task TKNHTTDNB1-345 thêm API công khai `POST /api/v1/public/applications/withdraw`, migration V34 (lịch sử giai đoạn của hồ sơ), đoạn mời rút hồ sơ trong email xác nhận và `actions.canWithdraw` trong kết quả tra cứu (mục "Rút hồ sơ (task 345)"); không thêm mã quyền. Task TKNHTTDNB1-346 gửi email cho recruiter chính và cho ứng viên sau mỗi lần rút, qua hàng đợi V35 (mục "Thông báo sau khi rút (task 346)"); không thêm API hay mã quyền.

Tiêu chí của story S4-05:

- "Nhập mã tra cứu và email để xem trạng thái": đã có (339). Ứng viên gửi mã tra cứu và email đã dùng khi nộp hồ sơ trong thân một yêu cầu POST, không cần tài khoản.
- "Chỉ hiển thị trạng thái ở mức tổng quát: Đã nhận, Đang xem xét, Mời phỏng vấn, Kết thúc": quy tắc đã có (340): mỗi giai đoạn nội bộ hiện ra đúng một trong bốn trạng thái này. API nộp hồ sơ và API tra cứu (339) đều lấy trạng thái từ quy tắc này.
- "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn": đã có (339, 341). API tra cứu chỉ trả tên vị trí, thời điểm nộp và trạng thái công khai (339); task 341 ghim điều này bằng test, kể cả cho các bảng nhận xét, đánh giá, phỏng vấn mà Sprint 5, 6 sẽ thêm.

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

{"postingTitle":"Lập trình viên Java","receivedAt":"2026-10-15T03:00:00Z","status":"RECEIVED","statusName":"Đã nhận","actions":{"canWithdraw":true}}
```

- `postingTitle`: tên vị trí **lúc nộp hồ sơ** (`job_applications.posting_title`); đổi tên tin sau đó không đổi giá trị này.
- `receivedAt`: thời điểm hệ thống nhận hồ sơ (UTC, ISO 8601).
- `status`, `statusName`: trạng thái công khai của mục "Trạng thái công khai (task 340)", không bao giờ là giai đoạn nội bộ.
- `actions.canWithdraw` (từ task 345): `true` khi hồ sơ còn rút được, tức giai đoạn còn trong quy trình; xem mục "Rút hồ sơ (task 345)".
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

## Dữ liệu công khai (task 341)

Tiêu chí thứ ba của story S4-05: "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn". API tra cứu của task 339 đã chỉ trả bốn trường, nên task 341 không đổi API, migration hay mã quyền. Task này viết quy tắc thành hợp đồng trong code (Javadoc của `PublicApplicationView` và `PublicApplicationReader`), ghim nó bằng test và đặt quy tắc cho các bảng mà Sprint 5, 6 sẽ thêm (nhận xét, đánh giá, phỏng vấn, người phỏng vấn; tới migration V31 chưa có bảng nào như vậy).

### Danh sách cho phép

Ứng viên chỉ nhận dữ liệu hồ sơ qua API tra cứu bằng record `PublicApplicationView`, và chỉ `PublicApplicationReader` điền record này:

| Trường | Lấy từ | Vì sao ứng viên được thấy |
|---|---|---|
| `postingTitle` | `job_applications.posting_title` (bản chụp lúc nộp) | Ứng viên đã thấy tên này trên tin lúc nộp |
| `receivedAt` | `job_applications.received_at` | Thời điểm của chính lần nộp |
| `status`, `statusName` | `job_applications.stage`, chỉ qua `PublicApplicationStatus.of` (task 340) | Tiêu chí thứ hai của story; giai đoạn nội bộ không ra ngoài |
| `actions.canWithdraw` (task 345) | `job_applications.stage`, chỉ qua `ApplicationStage.active()` | Cho trang tra cứu biết có hiện nút rút hồ sơ không; giai đoạn còn trong quy trình luôn có trạng thái khác `COMPLETED`, giai đoạn kết thúc luôn là `COMPLETED`, nên cờ này không cho biết thêm gì ngoài `status` |

Câu SQL chỉ đọc bảng `job_applications`: ba cột trên, cộng `tracking_code_hash`, `normalized_email`, `tracking_revoked_at` để tìm đúng dòng. Nó không nối (join) bảng nào khác: tài khoản nhân viên, yêu cầu tuyển dụng, nhóm recruiter, phòng ban, tin, danh mục, CV, hàng đợi email, các bảng lịch sử. Response là một record riêng chỉ có trường công khai (danh sách cho phép), không phải dòng dữ liệu nội bộ bị ẩn bớt trường (danh sách cấm), nên một cột mới của bảng nội bộ không tự xuất hiện trong response.

Ngoài API tra cứu, ứng viên nhận dữ liệu hồ sơ ở đúng hai chỗ có chủ đích, đều là biên nhận của chính lần nộp:

- Response 201 của API nộp hồ sơ (`AcceptedApplication`, task 326): `applicationId` (mã hồ sơ nội bộ), `status`, `statusName`, `receivedAt`, `trackingCode`, `trackingUrl`, chỉ trả một lần cho người vừa nộp.
- [Email xác nhận](application-confirmation.md) (task 329), gửi tới email đã nộp: họ tên đã nộp, tên vị trí (bản chụp), thời điểm tiếp nhận, mã tra cứu và liên kết tra cứu.

API tra cứu không trả lại các dữ liệu đó.

Response 200 của API rút hồ sơ (task 345, record `ApplicationWithdrawalView`) cũng là một danh sách cho phép: `postingTitle`, `receivedAt`, `status`, `statusName` như trên (không có `actions`) và `withdrawnAt`, thời điểm rút. `PublicApplicationResponsesTest` và `PublicApplicationPrivacyIntegrationTest` ghim nó như response tra cứu; lý do rút không bao giờ được trả lại.

### Không bao giờ trả cho ứng viên qua API tra cứu

Danh sách dưới đây áp dụng cho API tra cứu. Hai biên nhận ở mục trên là ngoại lệ có chủ đích: response 201 có `applicationId`, email xác nhận có họ tên đã nộp.

- Nhận xét, ghi chú, lý do và giải trình nội bộ. Hiện có: nhận xét duyệt yêu cầu tuyển dụng và lý do tạm dừng, đóng, hủy yêu cầu (`requisition_workflow_events.comment`), nhận xét duyệt tin (`job_posting_events.comment`), ghi chú phân công recruiter (`requisition_recruiter_changes.note`), lý do Trưởng phòng Nhân sự xác nhận vượt định biên (`requisition_headcount_overrides.reason`), giải trình lương của yêu cầu (`recruitment_requisitions.salary_justification`), lý do khóa tài khoản nhân viên (`user_accounts.admin_lock_reason`). Về sau: nhãn và ghi chú hồ sơ (S5-04).
- Phiếu đánh giá, điểm, xếp hạng, kết quả phỏng vấn, người phỏng vấn (Sprint 6); đã có ngân hàng câu hỏi phỏng vấn và gợi ý trả lời (`interview_questions.content`, `answer_hint`, V9).
- Tên, email, id của bất kỳ nhân viên nào: recruiter chính, recruiter hỗ trợ, trưởng phòng ban, người tạo yêu cầu, người duyệt, người phỏng vấn.
- Giai đoạn nội bộ (`stage`) và tên nội bộ của nó ("Hồ sơ mới", "Sàng lọc", "Phỏng vấn", "Đề xuất offer", "Nhận việc", "Loại", "Ứng viên rút").
- Mã hồ sơ, mã yêu cầu tuyển dụng, mã tin, mã phòng ban hay bất kỳ id nội bộ nào.
- Dữ liệu đã nộp: họ tên, email, số điện thoại, nguồn biết tin, thư giới thiệu, CV và tên tệp CV; dữ liệu giới thiệu nội bộ (S4-07); lý do rút hồ sơ (task 345).
- Lỗi cũng vậy: thân lỗi chỉ có `code`, `message`, `fieldErrors`, không lặp lại email hay mã đã gửi; 404 của một mã đúng với email sai không cho biết gì về hồ sơ của mã đó (không có tên vị trí).

### Quy tắc cho các bảng của Sprint 5 và 6

- Bảng nhận xét, đánh giá, phỏng vấn, người phỏng vấn, và mọi bảng khác, không bao giờ được nối vào `PublicApplicationReader`. Test `theLookupReadsNoTableButTheApplicationAndItsQuota` khóa độc quyền (`ACCESS EXCLUSIVE`) mọi bảng trong schema, trừ `job_applications` và hai bảng hạn mức của V31, rồi tra cứu: phải trả 200 ngay, cùng thân như khi không khóa. Bảng mới tự nằm trong danh sách bị khóa, nên ai nối thêm một bảng, kể cả chỉ trong một truy vấn con không hiện ra trong response, là test đỏ (câu truy vấn chờ khóa rồi hết 3 giây, trả 503).
- Dòng thời gian (S5-07), nếu có, chỉ gồm các mục trạng thái công khai và thời điểm, lấy từ `PublicApplicationStatus.of`; không có nhận xét, lý do hay người thực hiện.
- Thêm một trường công khai là đổi hợp đồng: sửa `PublicApplicationView`, các test ghim (`PublicApplicationResponsesTest`, `PublicApplicationPrivacyIntegrationTest`) và mục này, ghi rõ vì sao ứng viên được thấy, rồi báo frontend.
- `PublicResponseContract` (test) từ chối mọi thành phần record có tên giống dữ liệu nội bộ (chứa `note`, `comment`, `evaluat`, `score`, `rating`, `interview`, `recruiter`, `manager`, `staff`, `actor`, `requisition`, `reason`, `email`, `phone`, `cv`, `file`, `stage`, `source`, `referr`, `hash`, `tracking`, `candidate`, `applicationid`, `postingid`; hoặc tên là `id`, kết thúc bằng `Id`), mọi thành phần kiểu `UUID` và mọi thành phần nhắc tới `ApplicationStage`, kể cả trong record lồng nhau, `List`, `Optional`, `Map` hay mảng. Ngoại lệ phải ghi tên từng thành phần, và một ngoại lệ không còn ứng với thành phần nào cũng làm test đỏ. Danh sách hồ sơ đã giới thiệu (task 352) dùng cùng công cụ này.

### Kiểm thử (task 341)

- `PublicApplicationResponsesTest` (unit): `PublicApplicationView` đúng bốn thành phần, không thành phần nào giống dữ liệu nội bộ; `AcceptedApplication` đúng sáu thành phần, chỉ `applicationId`, `trackingCode`, `trackingUrl` là ngoại lệ; mỗi giai đoạn cho đúng mã và tên công khai, không bao giờ mã hay tên nội bộ; JSON đúng bốn khóa. Phần còn lại tự kiểm `PublicResponseContract`: ghi chú, giai đoạn (cả danh sách giai đoạn), người phỏng vấn trong `Optional`, nhận xét trong dòng thời gian (`List`, mảng, `Map`), id dạng chuỗi, `UUID`, điểm, recruiter đều bị từ chối và được gọi đúng đường dẫn (`next.interviewerName`); dòng thời gian chỉ có trạng thái và thời điểm được nhận; ngoại lệ thừa hoặc sai đường dẫn bị từ chối.
- `PublicApplicationPrivacyIntegrationTest` (HTTP thật, `max-attempts=3`): hồ sơ có recruiter chính, recruiter hỗ trợ, trưởng phòng, người tạo yêu cầu với tên dễ nhận ra, nhận xét duyệt yêu cầu, nhận xét duyệt tin, ghi chú phân công, lý do vượt định biên, giải trình lương, lý do khóa tài khoản của recruiter hỗ trợ, nguồn "Nguồn Bí Mật", thư giới thiệu, số điện thoại, CV "cv-bi-mat.pdf". Với mỗi giai đoạn: đúng bốn khóa, giá trị đúng như task 340; thân và mọi header không chứa tên, email hay id của nhân viên, nhận xét, ghi chú, lý do, giải trình, nguồn, tên tệp, số điện thoại, email và tên ứng viên, id hồ sơ, yêu cầu, tin, phòng ban, mã hay tên giai đoạn nội bộ. Khi mọi bảng khác bị khóa: tra cứu vẫn 200 với cùng thân, dưới 2,5 giây. Lỗi 400, 404, 429: đúng ba khóa, không lặp lại email, mã hay phần nào của mã đã gửi, không có tên vị trí.

### Ghi chú cho frontend (task 341)

- Trang tra cứu đang hiện `timeline[].note` (`src/pages/CareerPage/ApplicationLookupPage.tsx`, dòng 347 và 363) và tự thêm lý do rút vào dòng thời gian (dòng 405). Backend không gửi dòng thời gian; bỏ phần hiện ghi chú khỏi trang công khai.
- `sanitizePublicApplicationResult` (`src/services/applicationLookupService.ts`) lọc dữ liệu ở trình duyệt, nhưng lọc ở trình duyệt không bảo vệ được gì: dữ liệu đã nằm trong response mà ai cũng xem được bằng công cụ của trình duyệt. Bảo đảm nằm ở server (danh sách cho phép ở trên); frontend chỉ cần hiện đúng bốn trường.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 24, ý (11).

## Liên kết rút hồ sơ (task 344)

Story S4-06 (TKNHTTDNB1-45): "Rút hồ sơ qua liên kết trong email xác nhận, có xác nhận lại trước khi thực hiện". Task 344 làm hai phần mà API rút hồ sơ (task 345) sẽ dùng: **tạo** liên kết và **kiểm** liên kết dưới khóa. Không thêm API, migration hay mã quyền; ma trận quyền giữ 1572 ca.

### Dạng liên kết

```text
<APPLICATION_TRACKING_PAGE_URL>#rut=<mã tra cứu>
https://portal.example.test/tra-cuu#rut=<43 ký tự base64url>
```

- `ApplicationConfirmationSettings.withdrawalUrl(code)`: cùng trang đã được kiểm khi khởi động như liên kết tra cứu `#ma=` của task 329 (HTTPS, HTTP chỉ với loopback, không userinfo, query hay fragment), cùng mã tra cứu của lần ứng tuyển đó; chỉ khác phần sau `#`: `rut=` thay cho `ma=`.
- Không có query string: phần sau `#` không bao giờ được trình duyệt gửi lên máy chủ, nên mã không lọt vào log của máy chủ, của proxy hay header `Referer`.
- Mã base64url không cần mã hóa thêm trong fragment, nên liên kết chứa đúng mã như trong email.

### Vì sao dùng lại mã tra cứu, không có mã riêng hết hạn

- Mã tra cứu là 32 byte ngẫu nhiên (256 bit), không đoán được. API rút hồ sơ (task 345) đòi **cả** mã và email đã dùng khi nộp, cùng `confirm: true` (bước xác nhận lại của tiêu chí), và kiểm lại cặp đó sau khi khóa dòng hồ sơ.
- Một liên kết riêng dùng một lần, hết hạn sau 60 phút cần một bảng liên kết, một hàng đợi email, một tác vụ gửi, một API kiểm liên kết và một trang frontend mới, và lại gửi **thêm một bí mật** tới cùng hộp thư đã nhận mã tra cứu: người đọc được hộp thư đó vẫn rút được hồ sơ. Câu hỏi 26 (1) hỏi BA/PO có cần bước đó không.
- Mã không hết hạn và vẫn tra cứu được sau khi rút (câu hỏi 26 (2)). Mã bị lộ được thu hồi theo mục "Thu hồi mã (task 339)": sau đó cả tra cứu lẫn rút hồ sơ đều trả 404.

### Email: đoạn mời rút hồ sơ đến cùng API (task 345)

Task này **không** sửa email xác nhận. Email đã gửi thì không lấy lại được, nên không email nào được hứa một việc mà backend chưa làm được, dù hai PR được merge hay deploy theo thứ tự nào. Task 345 thêm đoạn mời rút hồ sơ cùng lúc với API; đoạn đó được dựng lúc gửi, từ mã đã giải mã, nên email còn trong hàng đợi lúc deploy cũng có liên kết. Email đã gửi trước task 345 không có liên kết rút; ứng viên vẫn rút được từ trang tra cứu (câu hỏi 26 (3)).

### Kiểm liên kết dưới khóa (`ApplicationWithdrawalAccess`)

`ApplicationWithdrawalAccess.lock(TrackingCredential)` (package-private, chỉ module hồ sơ ứng tuyển gọi) nhận hash SHA-256 của mã và email đã chuẩn hóa (cùng `TrackingCredential` của API tra cứu) và trả đúng **một** lần ứng tuyển đã khóa, hoặc rỗng:

1. Đọc không khóa `id` và `requisition_id` của lần ứng tuyển có đúng hash, đúng email và mã chưa bị thu hồi (một câu SQL, qua chỉ mục UNIQUE của hash). Không có thì trả rỗng ngay, không khóa gì.
2. Khóa dòng **yêu cầu tuyển dụng** `FOR NO KEY UPDATE`, như mọi thao tác đóng, tạm dừng hay đổi nhóm recruiter. Nhờ đó một lần rút chạy lần lượt với các thao tác đó trên cùng yêu cầu, và một thao tác đổi giai đoạn của Sprint 5 có thể đi đúng đường này.
3. Khóa dòng **hồ sơ** `FOR NO KEY UPDATE`, theo id **và** lại đủ hash, email, `tracking_revoked_at IS NULL`. Ở mức READ COMMITTED, câu khóa đã phải chờ sẽ đọc bản mới nhất đã commit, nên mã bị thu hồi trong lúc chờ cho kết quả rỗng, và giai đoạn đổi trong lúc chờ được trả theo giá trị mới.

Kết quả `LockedApplication(id, requisitionId, stage, postingTitle, receivedAt)`: `id` và `requisitionId` là dữ liệu nội bộ, không bao giờ trả cho ứng viên; tên vị trí và thời điểm nộp là bản chụp lúc nộp. Mã lạ, email sai và mã đã thu hồi đi cùng các câu SQL và cùng cho kết quả rỗng, nên task 345 trả chung một 404 như API tra cứu. Bước kiểm không ghi gì.

Điều kiện với người gọi (lỗi lập trình, báo trước mọi câu SQL):

| Gọi trong | Kết quả |
|---|---|
| Không có transaction | `IllegalTransactionStateException` (`Propagation.MANDATORY`): khóa phải giữ tới khi lần rút commit |
| Transaction chỉ đọc | `IllegalStateException`: PostgreSQL không cho khóa dòng trong transaction chỉ đọc |
| `REPEATABLE_READ`, `SERIALIZABLE` | `IllegalStateException`: ở các mức này, khóa một dòng đã đổi sau snapshot báo lỗi serialization thay vì đọc bản mới |
| READ COMMITTED đọc ghi | Chạy bình thường |

Người gọi đọc đồng hồ **sau** lời gọi này (sau lần chờ khóa cuối cùng), và ghi dòng của mình trong cùng transaction. Khi kết quả rỗng ở bước 3, khóa của yêu cầu vẫn giữ tới hết transaction của người gọi; không có gì được ghi.

### Kiểm thử (task 344)

- `ApplicationWithdrawalLinkTest` (unit): với trang HTTPS, trang `/careers/applications/lookup` và ba trang loopback, liên kết đúng bằng trang + `#rut=` + mã; cùng scheme, host, cổng, đường dẫn với liên kết tra cứu; không query, không userinfo; fragment đúng `rut=<mã>`, không có `#ma=`.
- `ApplicationWithdrawalAccessIntegrationTest` (PostgreSQL thật, không HTTP), mọi lần chờ khóa đo bằng `pg_blocking_pids` và thử khóa `NOWAIT` từ kết nối khác: một email nộp hai yêu cầu, mã A khóa đúng hồ sơ A và yêu cầu của A (khóa chia sẻ trên yêu cầu bị chặn, khóa `FOR KEY SHARE` thì không, nên đúng mức `FOR NO KEY UPDATE`), không khóa gì của B; email khác hoa thường và có khoảng trắng hai đầu vẫn khớp; giai đoạn trả đúng giá trị đang lưu; không dòng nào đổi `xmin`; mã của người khác, email của người khác, mã lạ, mã đã thu hồi → rỗng và không khóa gì; khi yêu cầu đang bị giữ, lời gọi chờ ở yêu cầu trong lúc dòng hồ sơ vẫn khóa được; thu hồi mã hoặc đổi giai đoạn commit trong lúc chờ → rỗng, hoặc giai đoạn mới; ngoài transaction, chỉ đọc, `REPEATABLE_READ`, `SERIALIZABLE` bị từ chối.
- `ApplicationConfirmationSecurityTest` (của TRAN DUC LUONG, không sửa): kiểm trang khi khởi động và liên kết `#ma=` vẫn đúng.

### Ghi chú cho frontend (task 344)

- Trang ở `APPLICATION_TRACKING_PAGE_URL` đọc thêm `#rut=`: mở chế độ rút hồ sơ, điền sẵn mã, hỏi email đã dùng khi nộp, rồi xóa phần sau `#` bằng `history.replaceState`, như với `#ma=`.
- **Không bao giờ** gọi API khi tải trang: trình quét thư và trình xem trước liên kết tự mở mọi liên kết trong email. Chỉ gọi API rút hồ sơ (task 345) sau khi ứng viên nhập email và bấm xác nhận trong hộp thoại hỏi lại.
- Không lưu mã hay email vào `localStorage`/`sessionStorage`; phục vụ trang với `Content-Security-Policy: frame-ancestors 'none'`.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 26, ý (1)–(3).

## Rút hồ sơ (task 345)

Story S4-06 (TKNHTTDNB1-45): "Rút hồ sơ qua liên kết trong email xác nhận, có xác nhận lại trước khi thực hiện". Ứng viên không có tài khoản rút một lần ứng tuyển ra khỏi quy trình bằng đúng cặp mã tra cứu và email của API tra cứu, kèm `confirm: true`. API công khai, không thêm mã quyền; migration V34 (`application_stage_events`, xem [dữ liệu](../database/README.md)); ma trận quyền có thêm một dòng công khai (1581 ca).

```http
POST /api/v1/public/applications/withdraw
Content-Type: application/json

{"trackingCode":"<43 ký tự base64url>","email":"nguyenvana@example.com","reason":"Đã tìm được công việc khác","confirm":true}
```

```http
HTTP/1.1 200
Cache-Control: no-store
Content-Type: application/json

{"postingTitle":"Lập trình viên Java","receivedAt":"2026-10-15T03:00:00Z","status":"COMPLETED","statusName":"Kết thúc","withdrawnAt":"2026-10-16T02:00:00Z"}
```

- `trackingCode`, `email`: như API tra cứu (cùng thông báo lỗi, bỏ ký tự vô hình ở hai đầu, email không phân biệt hoa thường).
- `confirm`: bắt buộc là giá trị JSON `true`. Thiếu, `null` hay `false` trả 400 `VALIDATION_ERROR` và không rút gì. Chuỗi hay số (ví dụ `"true"`, `"TRUE"`, `1`) không được đổi thành `true`: trả 400 `INVALID_JSON`, cũng không rút gì. Frontend chỉ gửi `true` từ hộp thoại hỏi lại ứng viên (bước "xác nhận lại trước khi thực hiện" của tiêu chí), nên một liên kết bị trình quét thư mở, hay một form gửi thiếu bước đó, không rút được hồ sơ.
- `reason`: không bắt buộc, tối đa 500 ký tự, văn bản thuần (`@PlainText`, cho phép xuống dòng và tab; ký tự điều khiển khác và thẻ HTML bị từ chối). `\r\n` và `\r` được đổi thành `\n`; lý do không có ký tự nhìn thấy được (chỉ khoảng trắng, khoảng trắng không ngắt, ký tự rộng 0, xuống dòng) coi như không ghi và được lưu `NULL`. Lý do chỉ người có phạm vi xem ứng viên thấy trong hồ sơ ứng viên (task 361); nó không bao giờ được trả lại cho ứng viên hay đưa vào email.
- Không có trường nào khác: trường lạ (ví dụ `stage`) trả 400 `INVALID_JSON`; `confirm` là chuỗi, số, mảng hay object cũng vậy.
- Response 200, cho lần gọi đầu và mọi lần gọi lại: đúng năm khóa. Tên vị trí và thời điểm nộp là bản chụp như API tra cứu; trạng thái công khai của `WITHDRAWN` (`COMPLETED`, "Kết thúc", task 340); `withdrawnAt` là thời điểm rút (UTC, tới micro giây). `withdrawnAt` là `null` chỉ khi hồ sơ đã ở `WITHDRAWN` mà không có dòng lịch sử (đổi bằng SQL trước task 345). Không có id, giai đoạn nội bộ, lý do hay dữ liệu liên hệ.
- Như API tra cứu: Bearer cũ bị bỏ qua, không bao giờ trả 401/403, cùng bộ lọc bảo vệ và cùng cổng đồng thời.

Thứ tự kiểm tra: CORS → bộ lọc bảo vệ (`no-store`; query string; kiểu nội dung; kích thước; đọc trọn thân, tối đa 8 KiB; cổng đồng thời) → bỏ qua Bearer → `permitAll` → đọc JSON (400) → kiểm dữ liệu, gồm `confirm` và `reason` (400) → mã đúng cách viết chuẩn (400) → địa chỉ IP của client (400) → hạn mức dùng chung với tra cứu (429/503) → transaction rút hồ sơ (404/409/200) → lỗi bất ngờ (503). Mọi bước kiểm dữ liệu chạy trước mọi truy cập database, nên yêu cầu sai dạng không tính lượt và không đổi gì.

### Lỗi (task 345)

Mọi lỗi của API tra cứu ở mục "Tra cứu hồ sơ (task 339)" (400 dữ liệu và query string, `INVALID_JSON`, `APPLICATION_CLIENT_IP_INVALID`; 413; 415; 429; 503 `APPLICATION_TRACKING_BUSY` và `APPLICATION_TRACKING_UNAVAILABLE`), thêm:

| HTTP | `code` | Khi nào / thông báo |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `fieldErrors.confirm` "Vui lòng xác nhận rút hồ sơ." (thiếu, `null` hoặc `false`); `fieldErrors.reason` "Lý do tối đa 500 ký tự." hoặc "Chỉ được nhập văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML hoặc ký tự điều khiển." (ví dụ có ký tự NUL hoặc `<script`) |
| 404 | `APPLICATION_LOOKUP_NOT_FOUND` | Giống hệt từng byte 404 của API tra cứu (status, thân, header): mã không tồn tại, email sai, mã đã thu hồi, kể cả mã bị thu hồi trong lúc yêu cầu đang chờ khóa |
| 409 | `APPLICATION_NOT_WITHDRAWABLE` | "Hồ sơ đã có kết quả nên không thể rút nữa.": hồ sơ ở `HIRED` hoặc `REJECTED`; không ghi gì |
| 503 | `APPLICATION_TRACKING_UNAVAILABLE` | Như tra cứu, thêm: chờ khóa quá 2 giây, câu lệnh quá 3 giây, commit thất bại; không còn gì của lần rút |

### Rút được khi nào, gọi lại thì sao

| Giai đoạn đọc được dưới khóa | Kết quả |
|---|---|
| `NEW`, `SCREENING`, `INTERVIEW`, `OFFER` | Chuyển sang `WITHDRAWN` và ghi một dòng lịch sử; 200 với `withdrawnAt` là lúc này |
| `WITHDRAWN` | Lần gọi lại: không ghi gì; 200 với `withdrawnAt` của lần rút đầu (hoặc `null` khi không có dòng lịch sử) |
| `HIRED`, `REJECTED` | 409 `APPLICATION_NOT_WITHDRAWABLE` |

- `switch` trên giai đoạn liệt kê đủ bảy giá trị, không có nhánh `default`: thêm một giai đoạn thì code không biên dịch được cho tới khi có người quyết định giai đoạn đó có rút được không.
- Gọi lại luôn trả 200 với cùng thân, dù lý do hay thời điểm gửi lần sau khác: ứng viên bấm hai lần, hoặc mạng gửi lại, không gặp lỗi và không ghi đè lý do.
- Không kiểm trạng thái yêu cầu tuyển dụng hay tin: ứng viên rút được cả khi yêu cầu đã tạm dừng, đã tuyển đủ, đã hủy, khi tin đã đóng hay đã quá hạn nộp (câu hỏi 26, ý 4).

### Khóa và transaction (task 345)

1. Hạn mức V31 chạy trước, trong transaction riêng đã commit, như tra cứu: lượt được tính dù kết quả là 200, 404 hay 409.
2. Một transaction REQUIRES_NEW, READ COMMITTED, tối đa 5 giây, `lock_timeout` 2 giây, `statement_timeout` 3 giây (như transaction nhận hồ sơ của task 329).
3. `ApplicationWithdrawalAccess.lock` (task 344): khóa yêu cầu tuyển dụng `FOR NO KEY UPDATE`, rồi dòng hồ sơ `FOR NO KEY UPDATE`, và kiểm lại mã, email và mã chưa thu hồi trên dòng đã khóa. Không tìm thấy → 404.
4. Đọc đồng hồ **sau** lần chờ khóa cuối cùng, cắt tới micro giây (độ chính xác của PostgreSQL).
5. `UPDATE job_applications SET stage = 'WITHDRAWN' WHERE id = ? AND stage = ?` với đúng giai đoạn đã khóa (phải đổi đúng một dòng), rồi chèn dòng `application_stage_events` (`CANDIDATE`, không có tài khoản, giai đoạn trước, lý do, thời điểm) bằng `JdbcTemplate` trong **cùng** transaction. Hai việc commit cùng nhau hoặc không việc nào. Không ghi dòng lịch sử trong transaction riêng: khóa ngoại của nó chỉ lấy `FOR KEY SHARE` trên dòng hồ sơ đang khóa, nên một transaction riêng không phải chờ và sẽ commit dòng lịch sử ngay cả khi việc đổi giai đoạn thất bại lúc commit.
6. Lỗi khóa, quá thời gian, commit thất bại hay lỗi bất ngờ: 503 `APPLICATION_TRACKING_UNAVAILABLE`, không còn gì của lần rút.

Hai lần rút cùng một hồ sơ chạy lần lượt trên khóa của yêu cầu; lần sau đọc thấy `WITHDRAWN` và là một lần gọi lại. Rút hồ sơ và đóng yêu cầu (task 292), tạm dừng hay đổi nhóm recruiter cũng chạy lần lượt, vì tất cả đều khóa yêu cầu trước. Unique index `application_stage_events_one_withdrawal_idx` là chốt cuối nếu sau này một đường ghi bỏ qua khóa, và trigger `application_stage_events_immutable` chặn mọi `UPDATE` dòng lịch sử.

### Sau khi rút

- Hồ sơ ra khỏi quy trình ngay khi commit: `ApplicationPipelineSource` chỉ đếm giai đoạn còn hoạt động, nên bước kiểm khi đóng yêu cầu (task 291) và `GET /requisitions/{id}/active-pipeline` không còn tính hồ sơ đó. Rút hồ sơ cuối cùng còn trong quy trình thì đóng yêu cầu vì đã tuyển đủ không cần `confirmActiveCandidates` (xem [tạm dừng, đóng và hủy yêu cầu](requisition-status.md#dành-cho-module-ứng-viên)).
- API tra cứu hiện `COMPLETED` ("Kết thúc") và `actions.canWithdraw: false`. Mã vẫn tra cứu được (câu hỏi 26, ý 2).
- Hồ sơ ứng viên hợp nhất (task 361) có thêm `withdrawnAt` và `withdrawalReason` ở lần ứng tuyển đó, chỉ với người được đọc lần ứng tuyển đó; `updatedAt` tính cả `withdrawnAt` (xem [hồ sơ ứng viên](candidate-profiles.md#response-200)).
- Email vẫn giữ chỗ trong yêu cầu đó: nộp lại vào cùng yêu cầu vẫn trả 409 `APPLICATION_ALREADY_SUBMITTED` (task 327, [chặn hồ sơ trùng](application-duplicates.md)). Rút là kết thúc; có cần cho HR hoàn tác việc rút không, và ai được làm, là câu hỏi 26, ý 6.
- Recruiter chính (nếu còn quyền xem ứng viên) và ứng viên nhận email báo việc rút: mục "Thông báo sau khi rút (task 346)".

### Ai rút được hồ sơ

Quyền duy nhất API kiểm là giữ mã tra cứu của đúng lần ứng tuyển đó cùng email đã dùng khi nộp. Vì vậy ai đọc được email xác nhận (bị chuyển tiếp, hộp thư dùng chung) đều rút được hồ sơ. Email báo cho ứng viên của task 346 giúp ứng viên phát hiện việc rút không do mình làm. Mã bị lộ được thu hồi theo mục "Thu hồi mã (task 339)"; sau đó rút hồ sơ trả 404. Hệ thống không lưu dấu vết kỹ thuật của yêu cầu rút (IP, trình duyệt); có cần lưu không là câu hỏi 26, ý 7.

### `actions.canWithdraw` trong tra cứu

Response của API tra cứu có thêm `actions` với đúng một khóa: `canWithdraw` là `true` khi giai đoạn còn trong quy trình (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`) và `false` khi đã có kết quả hoặc đã rút (theo mẫu `actions.canUnpublish` của cổng việc làm). Giá trị lấy từ cùng cột `stage`, nên API tra cứu vẫn chỉ đọc bảng `job_applications` (test khóa mọi bảng khác vẫn qua). Mọi giai đoạn còn trong quy trình có trạng thái công khai khác `COMPLETED` và mọi giai đoạn kết thúc có `COMPLETED`, nên cờ này không cho ứng viên biết thêm gì ngoài `status`.

### Đoạn rút hồ sơ trong email xác nhận

Email xác nhận (task 329) có thêm một đoạn sau liên kết tra cứu, kèm liên kết `#rut=<mã>` của task 344: "Nếu không muốn tiếp tục ứng tuyển, bạn có thể rút hồ sơ tại liên kết sau. Hệ thống sẽ hỏi email đã dùng khi nộp hồ sơ và yêu cầu bạn xác nhận lại trước khi rút:". Đoạn được dựng lúc gửi từ mã đã giải mã, nên thư còn trong hàng đợi lúc deploy cũng có liên kết; thư đã gửi trước task 345 thì không (câu hỏi 26, ý 3). Xem [email xác nhận](application-confirmation.md#nội-dung-thư).

### Ví dụ (PowerShell)

```powershell
$body = @{ trackingCode = '<43 ký tự>'; email = 'nguyenvana@example.com'; reason = 'Đã tìm được công việc khác'; confirm = $true } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/public/applications/withdraw' `
    -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

Không gửi header `Authorization`. Gọi lại cùng lệnh trả cùng kết quả.

### Kiểm thử (task 345)

- `ApplicationTextTest` (unit): văn bản chỉ gồm NBSP, U+2007, U+202F, U+0085, U+180E, U+200B, U+FEFF, U+2060, U+3000 hay ký tự điều khiển → không có văn bản; văn bản có ký tự nhìn thấy được giữ nguyên như đã gửi; `\r\n` và `\r` → `\n`; request rút hồ sơ đổi xuống dòng rồi bỏ lý do vô hình, bỏ ký tự vô hình hai đầu của mã và email, `toString` không lộ dữ liệu.
- `ApplicationStageEventMigrationTest`: nâng cấp từ migration ngay trước V34 giữ nguyên mọi hồ sơ, bảng mới rỗng; rút từ bốn giai đoạn còn hoạt động và thay đổi của nhân viên được nhận; CHECK từ chối `CANDIDATE` có tài khoản, `STAFF` không có tài khoản, loại người làm lạ, `CANDIDATE` sang giai đoạn khác `WITHDRAWN` hoặc từ `HIRED`/`REJECTED`, trước bằng sau, giai đoạn lạ, lý do rỗng hoặc chỉ khoảng trắng; cột `VARCHAR(500)` từ chối lý do 501 ký tự; lần rút thứ hai của một hồ sơ bị unique index từ chối (23505); `UPDATE` bị chặn (23514); xóa tài khoản còn được tham chiếu bị từ chối; xóa hồ sơ xóa lịch sử của nó.
- `ApplicationWithdrawalIntegrationTest` (HTTP thật): bốn giai đoạn còn hoạt động → 200 đúng năm khóa, `WITHDRAWN`, một dòng lịch sử `CANDIDATE` đúng giai đoạn trước, lý do và thời điểm micro giây, một lượt hạn mức; số hồ sơ đang xử lý giảm từng hồ sơ, đóng yêu cầu bị 409 khi còn một hồ sơ và được 200 không cần xác nhận sau hồ sơ cuối; `canWithdraw` đúng theo bảy giai đoạn, sau khi rút là `COMPLETED` và `false`; gọi lại → cùng thân, không ghi thêm; `WITHDRAWN` bằng SQL → 200 với `withdrawnAt` `null`; `HIRED`, `REJECTED` → 409, không đổi gì; email sai, mã lạ, mã đã thu hồi → đúng 404 của tra cứu; thiếu, `null`, `false` `confirm`, `confirm` là `"true"`, `"TRUE"`, `1`, `"false"`, `0`, mảng hay object (`INVALID_JSON`), lý do 501 ký tự, có NUL hoặc `<script`, mã không chuẩn, mã ngắn, email sai, trường lạ, query string, `text/plain`, quá 8 KiB → 400/415/413, không tính lượt, không đổi gì; xuống dòng Windows lưu thành `\n`, đúng 500 ký tự được nhận, lý do vô hình và không có lý do lưu `NULL`; yêu cầu tạm dừng, tuyển đủ, hủy, tin đã đóng, quá hạn nộp vẫn rút được; nộp lại cùng yêu cầu vẫn trùng (409); không Bearer và Bearer cũ đều 200; hồ sơ ứng viên hiện thời điểm và lý do rút cho người trong phạm vi, không hiện gì của lần đã rút cho người chỉ đọc được lần khác; lỗi lúc commit (trigger ràng buộc hoãn tới commit) → 503, giai đoạn không đổi và **không** có dòng lịch sử nào.
- `ApplicationWithdrawalLockIntegrationTest` (HTTP thật, `pg_blocking_pids` và thử khóa `NOWAIT`, không `sleep`): hai lần rút cùng lúc → hai 200 cùng thân, một dòng lịch sử; lần rút chờ yêu cầu đang bị giữ (trong lúc dòng hồ sơ vẫn khóa được), rồi thành công sau khi bên giữ commit `FILLED`; khi dòng hồ sơ bị giữ, lần rút giữ yêu cầu (khóa chia sẻ bị từ chối ngay) và lần đóng yêu cầu xếp hàng sau nó, rồi đóng được mà không cần xác nhận; mã bị thu hồi trong lúc chờ → 404, không ghi gì.
- `ApplicationTrackingQuotaIntegrationTest` (`max-attempts=3`): tra cứu, rút, tra cứu dùng hết ba lượt của cùng bucket; lần rút sau bị 429 và không rút gì.
- `ApplicationTrackingRequestFilterTest`: đúng POST của đường dẫn rút hồ sơ được bảo vệ như tra cứu; GET, PATCH và đường dẫn khác thì không thuộc bộ lọc.
- Cập nhật có chủ ý: `PublicApplicationResponsesTest` (`PublicApplicationView` thêm `actions` với đúng `canWithdraw`; `ApplicationWithdrawalView` đúng năm thành phần, JSON đúng năm khóa kể cả `withdrawnAt` `null`), `PublicApplicationPrivacyIntegrationTest` (năm khóa của tra cứu; response rút hồ sơ không chứa gì nội bộ hay lý do), `ApplicationLookupIntegrationTest` (năm khóa, `canWithdraw` theo giai đoạn), `ApplicationConfirmationIntegrationTest` của TRAN DUC LUONG (thư có "rút hồ sơ" và `https://portal.example.test/tra-cuu#rut=<mã>`), `ApplicationCommonMailConfigurationTest` của TRAN DUC LUONG (đăng ký thêm `ApplicationConfirmationSettings`, bean mà người gửi thư nay cần).
- `ApiAuthorizationMatrixIntegrationTest`: dòng công khai `POST /api/v1/public/applications/withdraw` (400 với thân `{}` cho mọi vai trò, khi không đăng nhập và với Bearer cũ); `PATCH /api/v1/public/applications/{id}/withdraw` của frontend bị từ chối.

### Ghi chú cho frontend (task 343)

- Đổi `PATCH /public/applications/{code}/withdraw` với `{reason}` (CORS không cho phép PATCH, và mã nằm trên đường dẫn) thành `POST /api/v1/public/applications/withdraw` với `{trackingCode, email, reason, confirm: true}`. Chỉ gọi sau khi ứng viên nhập email và bấm xác nhận trong hộp thoại hỏi lại; không gọi khi tải trang, kể cả khi trang mở từ liên kết `#rut=`.
- Hiện nút rút hồ sơ theo `actions.canWithdraw` của API tra cứu, không tự suy từ `status` (trang tra cứu đang chỉ cho rút khi `RECEIVED`, `IN_REVIEW` hoặc `INTERVIEW`, nên hồ sơ `INTERVIEWING` sẽ mất nút).
- Server không bắt buộc lý do; frontend có thể vẫn bắt chọn lý do, tối đa 500 ký tự.
- 200 cũng là kết quả của lần gọi lại: hiện "Đã rút hồ sơ" và `withdrawnAt`. Hiện `message` của server cho 404 và 409; xử lý 400, 413, 415, 429, 503 như API tra cứu (không đưa người dùng sang `/login` hay `/unauthorized`).
- Không lưu mã hay email vào `localStorage`/`sessionStorage`.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 26, ý (4)–(7).

## Thông báo sau khi rút (task 346)

Story S4-06 (TKNHTTDNB1-45), tiêu chí "Recruiter phụ trách nhận thông báo". Sau mỗi lần rút hồ sơ thật sự (không phải lần gọi lại), backend gửi một email cho recruiter chính của yêu cầu tuyển dụng và một email cho ứng viên. Không thêm API hay mã quyền; migration V35 (`application_notice_outbox`, xem [dữ liệu](../database/README.md)); ma trận quyền giữ 1581 ca.

### Ai nhận email nào

| Loại (`kind`) | Người nhận | Gửi khi nào | Nội dung |
|---|---|---|---|
| `RECRUITER_WITHDRAWAL` | Recruiter chính (`PRIMARY`) của yêu cầu tuyển dụng | Sau 15 phút, gộp theo người nhận và yêu cầu | Tên của chính recruiter, tên vị trí (bản chụp lúc nộp), thời điểm rút, liên kết tới hồ sơ ứng viên |
| `CANDIDATE_WITHDRAWAL` | Địa chỉ email của chính lần ứng tuyển đó | Ngay | Tên vị trí, thời điểm nộp, thời điểm rút, địa chỉ hỗ trợ |

- **Lúc rút** (`ApplicationWithdrawalNotices.queue`, trong transaction rút hồ sơ): recruiter chính chỉ được xếp email khi tài khoản còn đăng nhập được (bật, không bị quản trị khóa, như `Account.isAccessAllowed`) **và** có `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED` (đọc lại từ database). Recruiter chính đã được phân công và có một trong hai mã thì đọc được lần ứng tuyển này theo quy tắc task 363; người bị gỡ vai trò mà vẫn còn tên trong nhóm (V16 cho phép) không biết gì thêm. Recruiter hỗ trợ không nhận email. Yêu cầu chưa có recruiter chính đủ điều kiện (chưa có nhóm, tài khoản bị tắt, bị khóa, không còn quyền xem ứng viên) thì không ai nhận email recruiter, và việc rút vẫn thành công (câu hỏi 26, ý 8).
- **Lúc gửi** (`ApplicationNoticeWorker`): người nhận phải vẫn bật, không bị khóa, vẫn trong nhóm recruiter của yêu cầu đó (chính hoặc hỗ trợ) và vẫn có mã `CANDIDATES_READ_*`. Không đạt thì cả lô thành `FAILED` với `last_failure = 'RECIPIENT_UNAVAILABLE'`, không gửi gì. Recruiter vừa được chuyển sang hỗ trợ vẫn nhận (vẫn theo dõi yêu cầu); người đã rời nhóm thì không.
- Email ứng viên đi tới địa chỉ đã nhập khi nộp hồ sơ, để ứng viên phát hiện việc rút không do mình làm (mục "Ai rút được hồ sơ"). Email không có tên, mã tra cứu, lý do hay liên kết nào.

### Vì sao email recruiter không chứa gì ứng viên đã nhập

Ô họ tên của form công khai nhận mọi dòng chữ nhìn thấy được tới 120 ký tự, kể cả đường dẫn, ví dụ "A. Tài khoản của bạn sắp bị khóa, xác minh tại https://…". Nếu email gửi từ địa chỉ của hệ thống tới recruiter chép lại tên đó, bất kỳ ai cũng dùng được hệ thống để gửi thư lừa đảo cho nhân viên. Vì vậy email recruiter chỉ chứa chữ do nhân viên viết (tên của chính người nhận, tên vị trí do HR soạn, chụp lúc nộp hồ sơ), thời điểm và liên kết. Tên, email, số điện thoại, thư giới thiệu, lý do rút, mã tra cứu và tên tệp CV của ứng viên chỉ xem được sau khi đăng nhập, trong hồ sơ ứng viên (task 361, 362). Tiêu đề của cả hai email là hằng số; không header nào chứa dữ liệu.

### Nội dung thư

Email recruiter, tiêu đề "Có ứng viên rút hồ sơ — Hệ thống tuyển dụng nội bộ":

```text
Chào {tên recruiter},

Các hồ sơ ứng tuyển sau, thuộc yêu cầu tuyển dụng bạn phụ trách, vừa được ứng viên rút:
- Vị trí "{tên vị trí}", rút lúc {dd/MM/yyyy HH:mm} (giờ Việt Nam). Xem hồ sơ: {CANDIDATE_PROFILE_PAGE_URL}/{id hồ sơ ứng viên}
(một dòng cho mỗi hồ sơ trong lô)

Các hồ sơ này đã chuyển sang giai đoạn "Ứng viên rút" và không còn được tính trong danh sách đang xử lý.
Tên và thông tin liên hệ của ứng viên chỉ hiển thị sau khi đăng nhập.

Email này được gửi tự động tới recruiter chính của yêu cầu tuyển dụng.
Hệ thống tuyển dụng nội bộ
```

Email ứng viên, tiêu đề "Xác nhận rút hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ":

```text
Chào bạn,

Hồ sơ ứng tuyển vị trí "{tên vị trí}" nộp lúc {dd/MM/yyyy HH:mm} đã được rút lúc {dd/MM/yyyy HH:mm} (giờ Việt Nam)
theo yêu cầu gửi từ trang tra cứu hồ sơ.

Nếu không phải bạn thực hiện việc này, vui lòng trả lời email này hoặc liên hệ {địa chỉ MAIL_FROM} để được hỗ trợ.

Hệ thống tuyển dụng nội bộ
```

- Văn bản thuần UTF-8, From là `MAIL_FROM` (`app.mail.from`, như email xác nhận), Message-ID `<application-notice.<id dòng hàng đợi>@<miền của MAIL_FROM>>` của dòng worker lấy (dòng đầu của lô); thử lại cùng dòng đó thì giữ cùng Message-ID. Thời gian theo `app.business-zone` (giờ Việt Nam).
- Dòng của một hồ sơ chưa liên kết hồ sơ ứng viên (không xảy ra với hồ sơ nộp từ task 360) không có "Xem hồ sơ".
- Liên kết hồ sơ chỉ là trang của frontend; trang đòi đăng nhập và API hồ sơ (task 361) kiểm lại người xem.

### Gộp trong 15 phút

Dòng `RECRUITER_WITHDRAWAL` đến hạn sau `app.application-notice.recruiter-delay-seconds` giây (mặc định 900, từ 0 tới 3600). Khi worker lấy một dòng recruiter đến hạn, nó lấy luôn mọi dòng recruiter khác đang chờ của **cùng người nhận** và **cùng yêu cầu tuyển dụng**, đến hạn hay chưa, tối đa 50 dòng (dòng vừa lấy đứng đầu), dưới cùng một `lease_token`: một email liệt kê từng hồ sơ, sắp theo thời điểm rút. Hồ sơ của yêu cầu khác có email riêng. Nhờ vậy một loạt rút hồ sơ (kể cả do một script nộp rồi rút liên tục) chỉ tạo khoảng bốn email mỗi giờ cho mỗi recruiter và yêu cầu (câu hỏi 26, ý 9). Email ứng viên không gộp và không chờ.

### Hàng đợi và gửi lại

- Hai dòng được ghi bằng `JdbcTemplate` trong **cùng** transaction rút hồ sơ, sau dòng lịch sử giai đoạn, chỉ khi giai đoạn thật sự đổi (lần gọi lại không ghi gì): việc rút commit khi và chỉ khi hai email đã vào hàng đợi. Không bao giờ ghi trong transaction riêng (quy tắc của `ApplicationOutboxRepository`): khóa ngoại của dòng hàng đợi chỉ lấy `FOR KEY SHARE` trên hồ sơ đang khóa, nên một transaction riêng không phải chờ và sẽ commit email ngay cả khi việc rút thất bại lúc commit. Ràng buộc `application_notice_outbox_once` giữ mỗi loại một dòng cho mỗi hồ sơ (`ON CONFLICT … DO NOTHING`).
- Bảng không có payload: nội dung được dựng lúc gửi, trong một transaction chỉ đọc, từ `job_applications`, dòng `WITHDRAWN` của `application_stage_events`, `candidate_applications` và tài khoản người nhận lúc đó. Không lưu dữ liệu cá nhân hay bí mật nào trong hàng đợi.
- `ApplicationNoticeWorker` chạy mỗi `app.application-notice.poll-delay-ms` (mặc định 10 giây), tối đa 10 lần lấy mỗi lượt, cùng quy tắc với email xác nhận (task 329, V28): lease 2 phút, `FOR UPDATE SKIP LOCKED`, tối đa 6 lần thử, thử lại sau 60/300/900/3600/21600 giây; máy chủ thư từ chối người nhận → `FAILED`/`RECIPIENT_REFUSED`; lease hết hạn ở lần thử thứ 6 (worker chết giữa chừng) → `FAILED`/`DELIVERY_UNCERTAIN`; SMTP luôn chạy ngoài mọi transaction. Mỗi dòng trong lô giữ số lần thử của riêng nó.
- Hồ sơ không còn ở `WITHDRAWN` lúc gửi (chỉ đổi được bằng SQL trong Sprint 4) → dòng đó `FAILED`/`OBSOLETE` và bị bỏ khỏi lô; lô không còn hồ sơ nào thì không gửi.
- Mọi cập nhật kết quả (`renew`, `sent`, `failed`, đánh dấu bỏ) chỉ đổi các dòng còn mang đúng `lease_token` của lô và đúng các id của lô: worker đến muộn, sau khi lease hết hạn và worker khác đã lấy lại lô, không đổi được dòng nào.

| `status` / `last_failure` | Ý nghĩa |
|---|---|
| `PENDING` | Chờ tới hạn (`next_attempt_at`) |
| `PROCESSING` | Đang có worker giữ lease |
| `RETRY_WAIT` / `SMTP_TEMPORARY` | Lỗi tạm thời, sẽ thử lại |
| `SENT` | Đã gửi (`sent_at`) |
| `FAILED` / `RECIPIENT_REFUSED`, `RETRIES_EXHAUSTED`, `DELIVERY_UNCERTAIN` | Như email xác nhận (V28) |
| `FAILED` / `RECIPIENT_UNAVAILABLE` | Người nhận không còn được biết (khóa, tắt, rời nhóm, mất quyền xem ứng viên) |
| `FAILED` / `OBSOLETE` | Hồ sơ không còn ở `WITHDRAWN` |

### Cấu hình (task 346)

| Khóa | Biến môi trường | Mặc định | Ghi chú |
|---|---|---|---|
| `app.application-notice.poll-delay-ms` | `APPLICATION_NOTICE_POLL_DELAY_MS` | `10000` | Khoảng nghỉ giữa hai lượt của worker |
| `app.application-notice.recruiter-delay-seconds` | `APPLICATION_NOTICE_RECRUITER_DELAY_SECONDS` | `900` | 0..3600; ngoài khoảng này ứng dụng không khởi động |
| `app.candidate-profile.page-url` | `CANDIDATE_PROFILE_PAGE_URL` | `http://localhost:5173/candidates` | Trang hồ sơ ứng viên của frontend; kiểm như trang tra cứu: HTTPS (HTTP chỉ với loopback), không userinfo, query hay fragment. Production: `https://internal-hire.com/candidates`, ghi sẵn trong `devops/vps/compose.yaml` (`.env` của VPS không tới được backend) |

Hai worker (email xác nhận và email sau khi rút) cùng với hai tác vụ dọn hạn mức dùng chung bộ lập lịch hai luồng của module hồ sơ ứng tuyển.

### Khóa và transaction (task 346)

- Recruiter chính được đọc trong transaction rút hồ sơ, khi đang giữ khóa yêu cầu tuyển dụng (`FOR NO KEY UPDATE`, task 344). Mọi thay đổi nhóm recruiter khóa yêu cầu trước, nên người được đọc là recruiter chính tại thời điểm commit. Quyền được đọc không khóa; thay đổi vai trò, nhóm hay tài khoản sau khi commit được bắt lúc gửi.
- Khóa ngoại tới tài khoản người nhận lấy `FOR KEY SHARE`, không bị khóa quản trị (`FOR NO KEY UPDATE`) chặn; khóa ngoại tới hồ sơ lấy `FOR KEY SHARE` trên chính dòng hồ sơ đang giữ. Không có lần chờ khóa mới nào sau hai khóa của task 344.
- Lỗi khi ghi hàng đợi hay lúc commit: 503 `APPLICATION_TRACKING_UNAVAILABLE`, không còn gì của lần rút (giai đoạn, lịch sử, email).

### Kiểm thử (task 346)

- `ApplicationNoticeSettingsTest` (unit): liên kết hồ sơ là trang đã cấu hình cộng id ở cuối (không nhân đôi dấu `/`); từ chối HTTP ngoài loopback, scheme khác, userinfo, query, fragment, đường dẫn tương đối; độ trễ 0..3600; Message-ID, From và địa chỉ hỗ trợ lấy từ `MAIL_FROM`.
- `ApplicationWithdrawalNoticeIntegrationTest` (HTTP thật, PostgreSQL thật, máy chủ SMTP loopback; worker chỉ chạy khi test gọi `processNext`, độ trễ được "trôi qua" bằng SQL): một lần rút xếp đúng một dòng recruiter cho recruiter chính (đến hạn sau 900 giây) và một dòng ứng viên (đến hạn ngay), không bao giờ cho recruiter hỗ trợ, và trước khi hết độ trễ chỉ email ứng viên được gửi; email recruiter tới địa chỉ **hiện tại** của recruiter chính, đúng tiêu đề, From, Message-ID, văn bản thuần UTF-8, có tên vị trí, thời điểm rút, "Ứng viên rút" và liên kết hồ sơ; với họ tên ứng viên là câu lừa đảo kèm `https://internal-hire.co/login`, không email nào (thân và header) chứa tên đó, miền đó, email, số điện thoại, thư giới thiệu, lý do, mã hay tên tệp CV; email ứng viên tới đúng địa chỉ của lần ứng tuyển, có tên vị trí, hai thời điểm và địa chỉ hỗ trợ, không có tên, mã, lý do hay liên kết; ba lần rút cùng yêu cầu → một email recruiter ba dòng, cả ba dòng `SENT` sau một lần thử, lần rút ở yêu cầu khác có email riêng; một lần lấy giữ các dòng chờ của cùng người nhận và yêu cầu dưới một `lease_token` (kể cả dòng chưa đến hạn), không giữ dòng của người khác; sau khi lease hết hạn, worker khác lấy lại cả lô và `renew`, `sent`, `failed`, đánh dấu bỏ của worker cũ đều bị từ chối cho mọi dòng; gọi lại và hai lần rút song song vẫn chỉ một dòng mỗi loại; không có recruiter chính (chỉ có hỗ trợ, không có nhóm), recruiter chính bị tắt, bị khóa, bị gỡ vai trò lúc rút → không có dòng recruiter, vẫn có dòng ứng viên, rút vẫn 200; sau khi xếp hàng, recruiter chính bị khóa, bị tắt, rời nhóm, bị gỡ vai trò → `RECIPIENT_UNAVAILABLE` cho cả lô, không gửi; bị chuyển sang hỗ trợ → vẫn gửi; rời nhóm của yêu cầu này nhưng vẫn ở nhóm (chính hoặc hỗ trợ) của yêu cầu khác → vẫn `RECIPIENT_UNAVAILABLE`, không gửi; bàn giao recruiter chính trong lúc chờ → mỗi lô chỉ có dòng của chính người nhận, dù dòng nào được lấy trước: dòng của người cũ `RECIPIENT_UNAVAILABLE`, người mới nhận một email chỉ nêu lần rút sau bàn giao; hồ sơ bị đặt lại giai đoạn khác → `OBSOLETE`, bị bỏ khỏi lô, không gửi khi lô không còn gì; SMTP lỗi → cả lô `RETRY_WAIT` sau 60 giây rồi `SENT` với cùng Message-ID; người nhận bị từ chối → `RECIPIENT_REFUSED` sau một lần; lease hết hạn ở lần thứ 6 → `DELIVERY_UNCERTAIN`; trigger làm hỏng việc ghi hàng đợi → rút trả 503, giai đoạn không đổi, không lịch sử, không dòng hàng đợi; lỗi lúc commit (trigger ràng buộc hoãn tới commit trên `application_stage_events`) → 503 và **không** có dòng hàng đợi nào; bảng chỉ có các cột id, trạng thái gửi và thời điểm, và các loại CHECK nhận đúng bằng các giá trị của enum.
- Cập nhật có chủ ý: `ApplicationTestSupport.reset()` xóa cả `application_notice_outbox`; bốn lớp test có rút hồ sơ (`ApplicationWithdrawalIntegrationTest`, `ApplicationWithdrawalLockIntegrationTest`, `ApplicationTrackingQuotaIntegrationTest`, `PublicApplicationPrivacyIntegrationTest`) đặt `app.application-notice.poll-delay-ms=3600000` để worker không tự chạy giữa test.

### Ghi chú cho frontend (task 346)

- Không có API mới và không có hộp thư thông báo trong ứng dụng (thuộc S8-03). Liên kết trong email recruiter là `<CANDIDATE_PROFILE_PAGE_URL>/<id hồ sơ ứng viên>`, tức route `/candidates/:id` của frontend: trang đòi đăng nhập rồi gọi `GET /api/v1/candidates/{id}/unified-profile`; xử lý 401, 403, 404 như mọi lần mở hồ sơ (hiện thông báo của server, không hiện hồ sơ giả).
- Trang tra cứu, sau khi rút thành công, có thể báo ứng viên rằng một email xác nhận đã được gửi tới địa chỉ đã dùng khi nộp hồ sơ.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 26, ý (8)–(10).
