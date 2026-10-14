# 329 — Tạo mã theo dõi và gửi email xác nhận ứng tuyển

`PostgresApplicationReceiptWriter` là writer production cho [API nhận hồ sơ](public-applications.md).
Intake **vẫn tắt mặc định**; chỉ nhận khi bật flag và cấu hình khóa mã hóa hợp lệ.

## Cấu hình chung và điều kiện sẵn sàng

Tái sử dụng `JavaMailSender`, toàn bộ `MAIL_*`, TLS, kiểm tên chứng chỉ và timeout hiện có.
Không tạo SMTP client riêng. `app.mail.from` ánh xạ từ `app.password-reset.mail-from`, giữ
tương thích override property cũ và `MAIL_FROM`; mặc định `support@internal-hire.com`.
Cả ba sender đọc `app.mail.from`: override tên chung áp dụng cùng lúc cho ba loại thư;
property cũ và MAIL_FROM vẫn là nguồn fallback tương thích.
Luồng kích hoạt/reset giữ nguyên giao dịch và cách gửi. Phân loại địa chỉ bị SMTP từ chối
được đưa vào `MailFailureClassifier`; method cũ delegate để giữ tương thích.

| Biến | Quy tắc |
|---|---|
| `APPLICATION_INTAKE_ENABLED` | Mặc định false; không tự bật khi có writer |
| `APPLICATION_TRACKING_PAGE_URL` | URL frontend cố định, HTTPS; HTTP chỉ loopback. Không userinfo/query/fragment. Local mặc định `http://localhost:5173/tra-cuu` |
| `APPLICATION_CONFIRMATION_ENCRYPTION_KEY` | Base64 của 32 byte ngẫu nhiên, lưu riêng bền vững; mặc định rỗng |
| `APPLICATION_CONFIRMATION_KEY_ID` | Nhãn khóa, 1..32 ký tự `[A-Za-z0-9_-]`, mặc định v1 |

Khóa rỗng → writer chưa ready → bật riêng flag vẫn 503 trước quota/body. Khóa sai định dạng,
URL hoặc MAIL_FROM sai → lỗi cấu hình khi startup, không echo giá trị bí mật trong lỗi.
Không sinh khóa mới mỗi lần khởi động, không dùng JWT secret làm khóa, không commit khóa.
Worker không xử lý queue khi khóa chưa được cấu hình; hồ sơ/outbox cũ vẫn được giữ.

Hiện hỗ trợ **một khóa cấu hình**, chưa có keyring/rotation tự động. Giữ khóa và key ID đến khi
xử lý hết các bản ghi còn payload. Thay khóa/ID khi còn queue có thể làm giải mã thất bại và
đánh FAILED/PAYLOAD_INVALID; giữ bản sao khóa riêng để phục hồi theo quy trình quản trị sau này.
Không xóa khóa của hồ sơ FAILED nếu còn cần gửi lại theo S8-02.

VPS Compose hiện chưa truyền các biến confirmation: đặt property tương ứng trong
`backend/application-vps.properties` trên server (volume sẵn có), hoặc nối biến vào Compose
trong lần triển khai được duyệt. Chỉ thêm vào `.env` Compose không tự truyền vào backend.
Trước bật production, kiểm tra URL trang tra cứu hoạt động và IP proxy theo [quota](application-ip-quota.md).
Task này không deploy server hoặc gửi email thật.

## Mã và giao dịch

- Sinh 32 byte bằng SecureRandom → Base64URL không padding, 43 ký tự.
- Hồ sơ lưu SHA-256 **của 32 byte gốc**, kiểu BYTEA; không dùng hash của chuỗi mã như reset token.
- API trả mã và URL `page-url#ma=<mã>` với no-store, không lấy host từ request.
- Mã không tự hết hạn; xóa hồ sơ/thu hồi mã là quyết định riêng. Không mở API tra cứu ở 329.

Writer mở TransactionTemplate REQUIRES_NEW/READ_COMMITTED, gọi storage qua Spring proxy để
storage tham gia cùng giao dịch, đọc snapshot posting_title đã lưu, tạo receipt hợp lệ,
mã hóa payload rồi enqueue. **Hồ sơ + CV + outbox cùng commit hoặc cùng rollback**.
Enqueue không mở giao dịch riêng. Writer chỉ trả receipt sau commit thành công; REQUIRES_NEW
ngăn caller nhận receipt chưa commit rồi rollback giao dịch bên ngoài. SMTP chưa chạy ở bước này.
Lỗi ghi/commit ngoài lỗi nghiệp vụ được API che bằng 503.

Giao dịch receipt đặt timeout Spring 5 giây, `SET LOCAL lock_timeout='2s'` và
`SET LOCAL statement_timeout='3s'` trước khi gọi storage. Giới hạn SQL áp dụng cả các
khóa requisition/posting/source, ghi hồ sơ/CV và enqueue; timeout SQL trước COMMIT làm rollback
toàn bộ receipt, không trả mã và không gửi SMTP. Quota đã commit độc lập vẫn tính,
nhưng email không bị giữ chỗ nên ứng viên có thể thử lại khi còn lượt.
`SET LOCAL` chỉ có hiệu lực trong giao dịch này, không đổi cấu hình connection cho
luồng khác. Timeout Spring được áp dụng khi thực hiện JDBC; đây không phải hard
deadline cho đọc upload, kiểm CV, chờ connection, I/O khi COMMIT hoặc toàn bộ HTTP request.

Duplicate trả 409, không tạo thêm CV/outbox; lỗi outbox/CV rollback không giữ chỗ email.
Quota đã commit độc lập vẫn tính. V28 thêm bảng mới, không backfill email cho hồ sơ cũ.

## Outbox và mã hóa

`application_email_outbox`: UUID, application FK UNIQUE/cascade, Message-ID ổn định, trạng thái,
số lần xử lý, thời điểm thử tiếp, lease token/thời hạn, timestamps và mã lỗi tĩnh.
Mỗi hồ sơ tạo đúng một yêu cầu ACK; không có công tắc tắt thư xác nhận.

Payload gồm người nhận, họ tên, snapshot vị trí/thời gian nhận, mã, URL và người gửi tại lúc
tiếp nhận. JSON UTF-8 được mã hóa **AES-256-GCM**, nonce ngẫu nhiên 12 byte, tag 128 bit.
AAD gắn phiên bản với outbox ID, application ID và key ID, ngăn tráo ciphertext giữa hàng.
Ciphertext tối đa 16 KiB, kiểm receipt khi giải mã; buffer plaintext được xóa sau xử lý.
Các toString được redacted; không lưu plaintext mã/email trong bảng outbox.
Tham khảo [NIST GCM](https://csrc.nist.gov/pubs/sp/800/38/d/final).

SENT xóa ciphertext/nonce/key ID, giữ metadata; FAILED giữ payload mã hóa để xử lý sau.
Xóa hồ sơ cascade outbox và CV. Không có API công khai đọc outbox; HR gửi lại thuộc S8-02.

## Worker, retry và phục hồi

Worker poll mỗi 10 giây, tối đa 10 việc/lượt. Scheduler có 2 thread để cleanup IP không dùng chung
một thread với SMTP. Worker xử lý queue đã có cả khi intake flag tắt, nếu khóa còn sẵn sàng.

1. Giao dịch ngắn REQUIRES_NEW/READ_COMMITTED: chọn một việc đến hạn bằng FOR UPDATE
   SKIP LOCKED, tăng attempts, gán lease token ngẫu nhiên/thời hạn 2 phút rồi commit.
2. Giải mã; payload/key sai → FAILED/PAYLOAD_INVALID, không gửi SMTP.
3. Renew lease nếu token còn thuộc worker và chưa hết hạn; worker mất quyền không gửi.
4. Gửi SMTP **ngoài transaction DB**, MIME UTF-8/plain text, Message-ID đã lưu.
5. Giao dịch ngắn đánh SENT hoặc RETRY_WAIT/FAILED, luôn WHERE lease_token khớp;
   worker cũ không ghi đè owner mới. Timeout DB: lock 2s/statement 3s/transaction 5s.

| Trạng thái | Ý nghĩa |
|---|---|
| PENDING | Chờ gửi lần đầu |
| PROCESSING | Đã claim, có lease/token |
| RETRY_WAIT | Lỗi tạm thời, chờ thời điểm tiếp theo |
| SENT | SMTP đã chấp nhận; không chứng minh thư vào inbox |
| FAILED | Lỗi địa chỉ/payload, hết số lần thử hoặc lần cuối mất kết quả |

Lần đầu + tối đa 5 retry: chờ **60, 300, 900, 3600, 21600 giây** sau từng lần lỗi, dùng clock DB.
SMTP 4xx/connect/auth/timeout và lỗi gửi chưa phân loại là tạm thời; hết 6 attempts →
FAILED/RETRIES_EXHAUSTED. Địa chỉ bị SMTP từ chối vĩnh viễn ngay RCPT TO → FAILED/RECIPIENT_REFUSED.
Chưa xử lý DSN/bounce đến sau khi SMTP đã chấp nhận.

Attempts tăng lúc claim, crash cũng tiêu tốn một lần để không retry vô hạn. Lease hết
được nhận lại; lần thứ 6 hết lease → FAILED/DELIVERY_UNCERTAIN, không gửi lần 7. Dọn trường hợp
này theo batch 100/SKIP LOCKED. Lỗi worker/DB chỉ log thông báo tĩnh, kỳ sau tiếp tục.

**Có thể gửi trùng** nếu SMTP đã nhận nhưng worker chết/DB ghi SENT thất bại, hoặc worker
kéo dài qua lease và việc được nhận lại. Lease 2 phút phù hợp timeout SMTP 5 giây hiện tại;
không tăng timeout tùy ý vượt lease. Token fencing bảo vệ trạng thái DB, không thu hồi được
email đã gửi. Message-ID ổn định hỗ trợ nhận diện, không bảo đảm hộp thư loại trùng.
[Spring JavaMailSenderImpl](https://github.com/spring-projects/spring-framework/blob/main/spring-context-support/src/main/java/org/springframework/mail/javamail/JavaMailSenderImpl.java)
giữ Message-ID có sẵn khi gửi. [PostgreSQL SKIP LOCKED](https://www.postgresql.org/docs/16/sql-select.html)
được dùng để phối hợp các consumer trên queue.

## Nội dung thư

```text
Tiêu đề: Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ

Chào {họ tên},

Hệ thống đã nhận hồ sơ ứng tuyển của bạn.

Vị trí ứng tuyển: {snapshot tên vị trí}
Thời gian tiếp nhận: {dd/MM/yyyy HH:mm} (giờ Việt Nam)
Mã tra cứu: {mã}

Bạn có thể dùng liên kết sau để tra cứu hồ sơ:
{liên kết}

Vui lòng giữ mã và liên kết này để theo dõi hồ sơ.

Email này xác nhận hồ sơ đã được tiếp nhận.
Kết quả tuyển dụng sẽ được thông báo sau.

Hệ thống tuyển dụng nội bộ
```

Không đính kèm CV/thông tin nội bộ. Mẫu cố định theo nền kích hoạt/reset; quản lý template và
resend HR theo story riêng. Snapshot tên vị trí/thời gian không thay theo tin sửa sau đó.

## Kiểm thử và phạm vi

`ApplicationConfirmationSecurityTest`: URL/mailfrom/key/readiness, AES roundtrip/nonce,
tamper/AAD swapping/wrongkey, redaction, receipt/size validation.
`ApplicationConfirmationIntegrationTest`: HTTP/DB/SMTP thật local; 201/409, hash và payload mã hóa;
rollback, quota, plain UTF-8, giờ Việt Nam, alias người gửi, Message-ID và xóa payload SENT;
SMTP ngoài transaction, retry tối đa 6 lần, 550/connect/restart, worker đồng thời và lease;
lỗi DB, tamper, commit visibility, khả năng gửi trùng và migration V27→V28.
Kiểm thêm timeout khi khóa requisition/posting/source, khóa enqueue sau khi lưu CV,
SQL chậm và tổng nhiều SQL vượt ngân sách; giữ quota, rollback sạch, nộp lại cùng email,
không đổi timeout của transaction caller và vẫn nhận CV đúng giới hạn 10 MiB.
Tests intake OFF/thiếu key và các luồng kích hoạt/reset/import được hồi quy.

Database/schema và SMTP fixture riêng; container test fsync off không chứng minh độ bền sau crash.
API tra cứu S4-05/frontend/mapping sau NEW/manual resend và template S8-01/S8-02 vẫn riêng.
Không tự bật production hoặc nghiệm thu phần frontend.
