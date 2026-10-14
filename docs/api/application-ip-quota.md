# 328 — Giới hạn lượt gửi hồ sơ theo IP trong một giờ

Backend của S4-03, [ATSNB-97](https://luoplayer.atlassian.net/browse/ATSNB-97).
`PostgresApplicationAttemptQuota` là provider production của `ApplicationAttemptQuota`.
**API vẫn tắt mặc định và thiếu receipt/outbox production của 329**; thêm quota không tự mở API.

## Quy tắc

- Tối đa **5 lượt/IP trong cửa sổ trượt 60 phút**, tính chung mọi tin tuyển dụng.
- Ghi lượt trước UUID, query, media type, kích thước, biểu mẫu, CV, tin và budget parser.
  Sai dữ liệu, trùng hồ sơ, tin không nhận, bận hoặc lỗi lưu đều tiêu tốn lượt đã được ghi.
- GET, OPTIONS/preflight, CORS bị từ chối và intake chưa sẵn sàng không ghi lượt.
- Lượt thứ sáu trả 429; không ghi thêm và không gia hạn thời gian chờ.
- Lượt tại `t` hết hiệu lực khi `t <= thời điểm kiểm tra - 3600 giây`.
  Không reset theo giờ tròn hoặc khóa cố định 60 phút từ lần bị chặn.
- Những người dùng chung IP (NAT/Wi-Fi) dùng chung hạn mức. IPv6 tính theo địa chỉ,
  không gộp theo subnet. Quota IP không ngăn được việc đổi IP hoặc mọi dạng DDoS.

Ví dụ gửi lúc 09:00, 09:10, 09:20, 09:30, 09:40: lúc 09:45 bị chặn; đến 10:00
lượt 09:00 hết hiệu lực nên được gửi thêm một lượt, các lượt còn lại vẫn được tính.

## Xác định IP

`server.forward-headers-strategy=NONE` giữ `HttpServletRequest.getRemoteAddr()` là IP
socket peer. Resolver từ chối startup nếu cấu hình NATIVE/FRAMEWORK; không đặt thêm
RemoteIpValve hoặc filter khác thay IP peer trước filter quota.

`APPLICATION_INTAKE_TRUSTED_PROXIES` mặc định rỗng: lấy peer, bỏ qua mọi header forwarded.
Khi có proxy, cấu hình danh sách **IP hoặc CIDR cụ thể của proxy do mình kiểm soát**,
phân cách bằng dấu phẩy. Ví dụ môi trường thử: `10.20.0.5/32,2001:db8:abcd::5/128`.
Không dùng hostname, /0, hoặc tin toàn bộ dải private khi trong dải có client không tin cậy.
Tối đa 64 mục và 4096 ký tự; cấu hình lỗi dừng startup.

Chỉ peer khớp danh sách mới được cung cấp `X-Forwarded-For`. Resolver:

1. Đọc tất cả header XFF theo thứ tự, tối đa 2048 ký tự/16 địa chỉ.
2. Kiểm literal IPv4/IPv6, không DNS, hostname, port, scope ID hoặc brackets.
3. Duyệt phải sang trái, bỏ qua các proxy tin cậy; lấy hop không tin cậy đầu tiên.
   Nếu toàn bộ chain là proxy tin cậy thì lấy địa chỉ trái nhất.
4. Chuẩn hóa IPv6 và IPv4-mapped IPv6; `::ffff:192.0.2.7` và `192.0.2.7` chung quota.

Không dùng Forwarded, X-Real-IP, host hoặc Bearer token để đổi IP. Header thiếu/sai
từ peer được cấu hình tin cậy trả **400 APPLICATION_CLIENT_IP_INVALID** trước đọc body,
không ghi lượt vì chưa xác định được IP; không fallback sang một bucket chung của proxy.
Thông báo không echo header/IP.

Repo có Caddy `reverse_proxy backend:8080`; Caddy mặc định không tin các giá trị XFF từ
client theo [tài liệu Caddy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).
Khi triển khai, lấy đúng IP/CIDR Caddy trong mạng backend và truyền cấu hình ở môi trường
server; không lấy IP quan sát từ request làm cấu hình trust. Compose VPS hiện chưa truyền
biến mới: với volume cấu hình hiện có, đặt `app.application-intake.trusted-proxies=<IP/CIDR>`
trong `backend/application-vps.properties` của server rồi tạo lại backend. Không chỉ thêm
biến vào `.env` Compose mà cho rằng backend tự nhận được. Nếu Docker cấp IP động,
cần cố định mạng/IP hoặc cập nhật trust khi tạo lại container. Không bật intake khi chưa
kiểm chứng đường đi proxy. Lần triển khai này không thay cấu hình hoặc deploy VPS thật.

## PostgreSQL và giao dịch

Migration **V27** tạo hai bảng riêng:

| Bảng | Dữ liệu |
|---|---|
| `application_ip_quotas` | IP kiểu INET, thời điểm lượt gần nhất; hàng dùng để khóa theo IP |
| `application_ip_attempts` | Các lượt đã cho đi tiếp, IP và thời điểm TIMESTAMPTZ |

Không lưu email, CV, mã tra cứu hoặc liên kết hồ sơ. Dữ liệu hạn mức tồn tại qua restart
và được chia sẻ giữa các instance nối cùng database; không dùng bộ đếm trong RAM.

Giao dịch quota là **REQUIRES_NEW / READ_COMMITTED**, commit trước khi đọc body và tách
khỏi giao dịch hồ sơ/CV/outbox. Vì vậy rollback hồ sơ không hoàn trả lượt.
Xem [Spring propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
REQUIRES_NEW có thể cần thêm connection khi caller đã có giao dịch; pool phải đủ connection.
Trong luồng HTTP này filter ghi quota trước khi transaction hồ sơ bắt đầu.

Trong giao dịch: INSERT bucket nếu chưa có → SELECT FOR UPDATE theo IP → đọc
`clock_timestamp()` → xóa lượt `<= now - 1 giờ` → kiểm lượt còn hiệu lực → ghi lượt
và cập nhật bucket nếu còn hạn mức. Từ chối trả quyết định sau giao dịch, không ghi lượt.
Không dùng transaction_timestamp trước khi chờ khóa, tránh tính cửa sổ bằng thời điểm cũ;
xem [PostgreSQL datetime](https://www.postgresql.org/docs/16/functions-datetime.html).

Mọi request cùng IP tuần tự qua khóa hàng của database, gồm trường hợp tạo bucket đầu tiên;
IP khác có khóa riêng. Giới hạn chờ khóa 2 giây, statement 3 giây, giao dịch 5 giây.
Lỗi kiểm tra/ghi/commit quota trả **503 APPLICATION_QUOTA_UNAVAILABLE**, không xử lý hồ sơ
và không phơi SQL hoặc nguyên nhân nội bộ. Không coi request chưa ghi được là một lượt thành công.

Cleanup chạy mỗi 60 giây, tối đa 500 bucket hết hiệu lực/lượt, khóa SKIP LOCKED rồi xóa;
attempts được xóa theo FK cascade. Không chờ IP đang xử lý. Nếu bucket biến mất giữa
INSERT và SELECT do cleanup, admission thử lại tối đa ba lần rồi fail closed.
Tham khảo [PostgreSQL locking](https://www.postgresql.org/docs/16/explicit-locking.html).
Lượt quá hạn của IP còn hoạt động cũng được dọn khi IP đó gửi lần tiếp theo.
Cleanup lỗi chỉ log thông báo tĩnh, tự thử ở kỳ sau; nếu backlog lớn thì dữ liệu được dọn dần,
không cam kết mọi IP được xóa ngay sau phút thứ 60.

## Phản hồi HTTP

```http
HTTP/1.1 429 Too Many Requests
Cache-Control: no-store
Retry-After: 900
Content-Type: application/json
```

```json
{
  "code": "APPLICATION_RATE_LIMIT_EXCEEDED",
  "message": "Bạn đã gửi hồ sơ quá số lượt cho phép. Vui lòng thử lại sau.",
  "fieldErrors": {}
}
```

`Retry-After` là số giây nguyên làm tròn lên, tối thiểu 1, đến khi lượt lâu nhất hết hiệu lực.
Thời gian là gợi ý tại thời điểm kiểm tra; người dùng chung IP có thể dùng lượt trống trước.
CORS expose Retry-After để frontend ở origin được phép đọc được header. Không trả IP,
số liệu hồ sơ, email, mã/hash/link hoặc SQL trong lỗi.

## Kiểm thử

- `ApplicationClientIpResolverTest`: direct/trusted/CIDR, spoof, IPv6/mappedIPv4, giới hạn
  header, syntax, cấu hình trust và peer rewriting.
- `ApplicationIpQuotaIntegrationTest`: 5/6, cửa sổ và biên microsecond 60 phút, Retry-After,
  nhiều IP/instance, concurrent first requests, chờ khóa/timeout, giao dịch độc lập,
  cleanup/batch/race, migration V26→V27, HTTP lỗi/CORS/trùng/receipt rollback/DB lỗi.
- `ApplicationTrustedProxyQuotaIntegrationTest`: HTTP thật qua peer tin cậy, chain giả,
  IP độc lập, IPv4 mapped và header sai.
- Các test intake OFF/thiếu writer xác nhận không ghi quota; test intake cũ dùng quota
  adapter Primary riêng để tiếp tục kiểm hợp đồng API/parser/storage mà không tiêu tốn
  hạn mức thật qua nhiều ca. Test này không thay thế các test quota production ở trên.

Dùng Docker PostgreSQL riêng và schema riêng mỗi context như [storage](application-storage.md).
Không dùng database recruitment. Tests không xác minh durability khi crash nếu fsync off.
329 vẫn là task tiếp theo: không có outbox/email production và chưa nghiệm thu toàn S4-03.
