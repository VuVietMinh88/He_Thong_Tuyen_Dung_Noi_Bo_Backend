# Gửi offer và nhận phản hồi của ứng viên

Phần backend cho TKNHTTDNB1-506–510. Offer được tạo và phê duyệt qua các API nội bộ trước khi gửi. Toàn bộ thay đổi ở worktree local; tài liệu này mô tả cách hoạt động của mã nguồn, không xác nhận đã triển khai lên VPS.

## Gửi offer đã duyệt

```http
POST /api/v1/offers/{id}/send
Authorization: Bearer <accessToken>
Content-Type: application/json

{"expectedVersion": 3}
```

Người gửi phải có vai trò `RECRUITER`, `HR_MANAGER` hoặc `ADMIN`, quyền đọc/ghi offer và phạm vi đọc phù hợp với hồ sơ ứng viên theo `OfferAccess`. Backend kiểm tra lại quyền và phiên sau khi chờ khóa dữ liệu. Chỉ offer `APPROVED` còn hạn mới được gửi; hồ sơ phải ở `OFFER`, yêu cầu tuyển dụng ở `APPROVED`, chức danh còn hoạt động. Ứng viên, tên, email và chức danh hiện tại phải khớp dữ liệu đã duyệt.

Request chỉ nhận `expectedVersion`, là số nguyên không âm bằng phiên bản offer vừa đọc. Số 3 trong ví dụ là minh họa, không phải giá trị cố định; gửi thành công tăng phiên bản lên 4. Email, điều kiện tuyển dụng và người nhận được lấy từ bản offer đã duyệt, không lấy từ nội dung request. Backend lưu mã phản hồi và email vào hàng đợi trong cùng giao dịch với việc chuyển offer sang `SENT`.

Response HTTP 200:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "status": "SENT",
  "deliveryStatus": "PENDING",
  "version": 4,
  "sentAt": "2030-01-01T03:00:00Z"
}
```

`SENT` trên offer nghĩa là đã xếp hàng để gửi. `deliveryStatus` cho biết tiến độ gửi email: `PENDING`, `PROCESSING`, `RETRY_WAIT`, `SENT`, `FAILED` hoặc `CANCELLED`. API xem offer hiển thị trạng thái này; chưa xếp hàng thì là `NOT_QUEUED`. SMTP chấp nhận email chưa chứng minh ứng viên đã đọc hoặc thư đã vào hộp thư đến.

Gửi lại với phiên bản cũ trả HTTP 409 `OFFER_VERSION_CONFLICT`. Offer chưa duyệt trả `OFFER_NOT_APPROVED`; điều kiện hết hạn trả `OFFER_TERMS_EXPIRED`; người nhận hoặc chức danh thay đổi trả `OFFER_RECIPIENT_CHANGED`. Hệ thống không tạo thêm một email cho cùng offer khi người dùng bấm gửi lặp.

## Liên kết phản hồi

Email chứa điều kiện tuyển dụng và đường dẫn dạng:

```text
https://<backend-domain>/api/v1/public/offers/reply#token=<mã phản hồi>
```

Mã phản hồi gồm 43 ký tự ngẫu nhiên, chỉ cấp quyền xem và trả lời đúng offer. Database lưu SHA-256 của mã; bản rõ chỉ nằm trong nội dung email được mã hóa AES-GCM khi chờ gửi. Bản mã được ràng buộc với ID hàng đợi, ID offer và phiên bản khóa.

Trang phản hồi tự đọc mã từ phần sau dấu `#`, xóa phần này khỏi thanh địa chỉ trước khi gọi API và chỉ giữ mã trong bộ nhớ của trang. Trang không lưu mã vào cookie hoặc browser storage. Mở email hoặc mở trang chưa tạo phản hồi; chỉ thao tác bấm Chấp nhận/Từ chối mới gửi quyết định.

Các API public không cần đăng nhập, nhận mã trong JSON và có giới hạn request:

| Phương thức | Đường dẫn | Nội dung JSON |
| --- | --- | --- |
| POST | `/api/v1/public/offers/lookup` | `{"token":"..."}` |
| POST | `/api/v1/public/offers/respond` | `{"token":"...","action":"ACCEPT","note":null}` |

Hai API dùng chung giới hạn 120 request trong mỗi cửa sổ 15 phút theo IPv4 hoặc nhóm địa chỉ IPv6 `/64`; request bị từ chối do token sai vẫn được tính vào giới hạn. Vượt giới hạn trả HTTP 429 `OFFER_RATE_LIMIT_EXCEEDED` kèm `Retry-After`. JSON tối đa 8.192 byte. Mỗi tiến trình backend mặc định cho tối đa 4 request public đang xử lý đồng thời; vượt mức trả HTTP 503 `OFFER_BUSY`. Không gửi query string trên hai đường dẫn POST này.

`lookup` trả `positionTitle`, `salary`, `allowance`, `startDate`, `responseDeadline`, `status`, `respondedAt`. Lương và phụ cấp là số VND mỗi tháng. Kết quả không chứa email, mã phản hồi, ID hồ sơ, dải lương nội bộ, giải trình hoặc lịch sử duyệt.

`respond` nhận `action` là `ACCEPT` hoặc `DECLINE`; `note` không bắt buộc, tối đa 2.000 ký tự. Backend chuẩn hóa xuống dòng và khoảng trắng đầu/cuối. Cả hai lựa chọn đều cho phép để trống ý kiến.

Response có `status` (`ACCEPTED` hoặc `DECLINED`) và `respondedAt`. Gửi lại đúng lựa chọn cùng ý kiến đã chuẩn hóa trả lại kết quả cũ mà không ghi thêm lịch sử. Gửi lựa chọn hoặc ý kiến khác trả HTTP 409 `OFFER_ALREADY_RESPONDED`.

Mã không hợp lệ, hết hạn, đã thu hồi hoặc không còn khớp hồ sơ đều trả HTTP 400 `OFFER_LINK_INVALID`. Liên kết chỉ còn dùng được khi chưa đến `responseDeadline`; ngay đúng thời điểm hạn chót cũng không nhận phản hồi mới. Sau khi trả lời, liên kết có thể hiển thị kết quả và chấp nhận retry cùng nội dung trong khoảng hiệu lực còn lại.

## Phản hồi và pipeline

Chấp nhận chuyển offer sang `ACCEPTED` và hồ sơ từ `OFFER` sang `HIRED`. Từ chối chuyển offer sang `DECLINED` và hồ sơ từ `OFFER` sang `REJECTED`. Cả trạng thái offer, giai đoạn hồ sơ và hai loại lịch sử đều được lưu trong cùng giao dịch; nếu bất kỳ bước ghi nào lỗi, toàn bộ giao dịch được hủy.

Lịch sử giai đoạn ghi tác nhân `CANDIDATE` và liên kết đến đúng offer của hồ sơ. Ý kiến đầy đủ được lưu trên offer và lịch sử offer; lý do trên lịch sử giai đoạn dùng mô tả ngắn cố định để giữ đúng giới hạn 500 ký tự của bảng cũ. Đường xử lý ứng viên từ chối offer không dùng lý do loại do nhân viên chọn trong danh mục.

Các thao tác đồng thời dùng cùng thứ tự khóa: yêu cầu tuyển dụng → hồ sơ → offer → mã phản hồi. Thời gian nghiệp vụ được đọc lại sau khi chờ khóa. Vì vậy, hai phản hồi trái ngược hoặc phản hồi đua với tác vụ hết hạn chỉ có một kết quả được ghi.

Offer đã chấp nhận vẫn giữ ràng buộc một offer hiệu lực cho ứng viên. Các trạng thái `DECLINED`, `REJECTED`, `EXPIRED`, `CANCELLED` giải phóng ràng buộc này.

## Hết hạn và thu hồi quyền phản hồi

Tác vụ định kỳ xử lý tối đa 100 offer mỗi lượt. Các trạng thái nháp, chờ sửa, chờ duyệt, đã duyệt hoặc đã gửi đều chuyển sang `EXPIRED` khi hết hạn mà chưa được trả lời. Tác vụ không đổi giai đoạn hồ sơ: hồ sơ đang ở `OFFER` vẫn giữ `OFFER`, còn hồ sơ đã chuyển giai đoạn khác thì giữ giai đoạn đó. Recruiter thấy trạng thái offer hết hạn trong API xem offer. Tác vụ không thay đổi offer đã chấp nhận hoặc đã bị từ chối.

Nếu hồ sơ rời giai đoạn `OFFER`, yêu cầu tuyển dụng không còn `APPROVED`, chức danh không còn hoạt động hoặc thông tin định danh người nhận thay đổi, offer còn hoạt động và chưa hết hạn được chuyển sang `CANCELLED`. Nếu đồng thời đã đến hạn, trạng thái `EXPIRED` được ưu tiên. Mã cũ bị thu hồi, các email còn chờ được hủy và xóa bản mã. API public và worker email kiểm tra các điều kiện này ngay cả trước khi tác vụ định kỳ chạy.

Tác vụ bảo trì chạy độc lập với việc bật email. Điều này giúp offer nháp hết hạn không giữ chỗ mãi trong ràng buộc một offer hiệu lực trên mỗi ứng viên.

## Cấu hình và vận hành local

| Thuộc tính | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `app.offers.enabled` | `false` | Bật API gửi và phản hồi offer |
| `app.offers.delivery-enabled` | `false` | Cho worker kết nối SMTP |
| `app.offers.page-url` | `http://localhost:8080/api/v1/public/offers/reply` | URL trang phản hồi cố định; HTTPS hoặc HTTP loopback |
| `app.offers.encryption-key` | Rỗng | Base64 của khóa ngẫu nhiên 32 byte, cấu hình riêng ngoài Git |
| `app.offers.key-id` | `v1` | Định danh phiên bản khóa |
| `app.offers.time-zone` | `Asia/Ho_Chi_Minh` | Múi giờ hiển thị nội dung email |
| `app.offers.poll-delay-ms` | `60000` | Khoảng chờ giữa các lượt gửi |
| `app.offers.max-concurrent` | `4` | Số request public đồng thời trên mỗi tiến trình, cấu hình từ 1 đến 16 |
| `app.offers.expiry-delay-ms` | `60000` | Khoảng chờ giữa các lượt bảo trì |

Email dùng địa chỉ gửi `app.mail.from` và cấu hình SMTP hiện có. Ngày nghiệp vụ dùng `BusinessCalendar` của dự án. Nếu tính năng gửi/phản hồi chưa bật hoặc chưa có khóa, API trả HTTP 503 `OFFER_UNAVAILABLE`.

Worker không giữ transaction khi kết nối SMTP. Mỗi hàng đợi có lease riêng; chỉ worker còn giữ lease được gia hạn hoặc ghi kết quả. Trước khi gửi, worker kiểm tra lại trạng thái offer, điều kiện hồ sơ, người nhận, thời hạn và nội dung đã duyệt. Một hàng đợi có tối đa 6 lượt xử lý, gồm lượt đầu và tối đa 5 lượt thử lại; lỗi SMTP tạm thời có các khoảng chờ lần lượt 60 giây, 5 phút, 15 phút, 1 giờ và 6 giờ. Việc nhận lại một lease hết hạn cũng tính là một lượt. Mã lỗi lưu trong database là mã cố định, không chứa địa chỉ nhận hoặc nội dung thư.

Mỗi hàng đợi dùng Message-ID ổn định qua các lần thử. Nếu tiến trình dừng sau khi SMTP nhận thư nhưng trước khi ghi thành công, lần thử lại vẫn có thể gửi trùng; hệ thống không tuyên bố đảm bảo gửi đúng một lần. Một thay đổi xảy ra sau lần kiểm tra cuối và sau khi SMTP đã nhận thư cũng không thể thu hồi email đã gửi; liên kết trong email vẫn được kiểm tra lại khi ứng viên mở hoặc phản hồi.

Các kiểm thử dùng PostgreSQL tạm, dữ liệu giả và SMTP loopback: `OfferResponseIntegrationTest`, `OfferDeliveryIntegrationTest`, `OfferDeliveryCryptoTest`, `OfferDeliveryWorkerTest`. Kết quả chạy thực tế được root ghi trong tài liệu bàn giao của worktree.
