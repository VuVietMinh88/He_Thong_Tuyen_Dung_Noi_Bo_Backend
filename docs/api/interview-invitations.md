# Lời mời phỏng vấn

Các API dùng `/api/v1`. Thời gian JSON là ISO-8601 có múi giờ, ví dụ `2026-11-20T09:00:00+07:00`. Email và trang phản hồi mặc định hiển thị giờ Việt Nam. [Đặt lịch đơn/hàng loạt](interviews.md) dùng cùng quy tắc quyền, trùng lịch và trạng thái hồ sơ.

## Tạo và gửi

Khi bật chức năng, tạo lịch đơn hoặc xác nhận một đợt lịch hàng loạt sẽ lưu lịch, lời mời và hàng đợi email trong cùng giao dịch. Nếu một bước lưu thất bại, toàn bộ giao dịch bị hủy. API thành công có nghĩa là **đã xếp hàng gửi**, không bảo đảm hộp thư đã nhận email.

Ứng viên nhận đúng vị trí, vòng, giờ bắt đầu/kết thúc, địa điểm hoặc link họp, hướng dẫn chuẩn bị và link phản hồi riêng. Mỗi người phỏng vấn có một email riêng, không có token phản hồi của ứng viên. `preparationInstructions` không bắt buộc; bỏ trống dùng hướng dẫn chuẩn bị mặc định.

`POST /interviews/{id}/invitation`, body `{"expectedVersion":0}`, trả `204`: gửi lời mời cho lịch đã tồn tại hoặc thay lời mời đã hết hạn. Cần quyền INTERVIEWS READ/WRITE và quyền đọc hồ sơ tương ứng. Gửi lại khi lời mời hiện tại còn hiệu lực không tạo thêm thư và không xóa phản hồi đã có. Phiên bản cũ trả 409. Không gửi cho lịch đã bắt đầu, đã hủy, đã chốt đánh giá hoặc hồ sơ/yêu cầu ngừng xử lý.

Đổi lịch thu hồi link cũ, tạo lời mời mới và trả trạng thái về `PENDING`. Gửi thông báo cho ứng viên, người phỏng vấn hiện tại và người bị gỡ khỏi lịch. Hủy lịch thu hồi link cũ và gửi thông báo hủy; email hủy/gỡ người không chứa link họp hoặc link xác nhận cũ.

## Ứng viên phản hồi

Backend cung cấp trang `GET /public/interview-invitations/reply` và script `/reply.js`. Đặt `INTERVIEW_INVITATION_PAGE_URL` thành URL công khai của trang này. Link email có dạng `https://.../reply#token=<mã riêng>`; fragment được xóa khỏi thanh địa chỉ trước khi gọi API. Không chuyển token sang query string, log, cookie hay localStorage. Mở trang chỉ đọc thông tin, không tự xác nhận.

`POST /public/interview-invitations/lookup`:

```json
{"token":"<mã riêng trong email>"}
```

Trả `positionTitle`, `roundName`, `startsAt`, `endsAt`, `mode`, `room`, `meetingUrl`, `preparationInstructions`, `status`, `respondedAt`, `expiresAt`. Không trả CV, email ứng viên, dữ liệu đánh giá hoặc các hồ sơ khác.

`POST /public/interview-invitations/respond`:

```json
{"token":"<mã riêng trong email>","action":"CONFIRM","note":null}
```

Hoặc `action="REQUEST_RESCHEDULE"`, `note` là lý do/khung giờ mong muốn, tối đa 2000 ký tự. Lý do phải có ký tự nhìn thấy. Xuống dòng Windows được chuẩn hóa. Khi xác nhận tham dự, ghi chú là tùy chọn.

Trả `{"status":"CONFIRMED","respondedAt":"..."}` hoặc `RESCHEDULE_REQUESTED`. Phản hồi đầu tiên được chốt; gửi lại cùng hành động và ghi chú chuẩn hóa trả kết quả cũ. Thay phản hồi trả `409 INTERVIEW_INVITATION_ALREADY_RESPONDED`; người tuyển dụng xử lý đề nghị bằng cách sửa lịch, hệ thống phát hành lời mời mới.

Mã hết hạn sau tối đa 7 ngày, hoặc ngay giờ bắt đầu lịch nếu sớm hơn. Mã sai/hết hạn/thu hồi, lịch hủy, phiên bản không còn hiện hành, hồ sơ đã rút/bị loại/đã tuyển hoặc yêu cầu ngừng xử lý đều trả `400 INTERVIEW_INVITATION_INVALID`. Không gửi token trong URL API. Chỉ nhận JSON tối đa 8 KiB. Giới hạn độc lập 120 yêu cầu/15 phút theo IP đã băm (IPv6 theo /64), `429` kèm `Retry-After`; cấu hình thiếu hoặc hệ thống bận trả `503`. Tất cả kết quả dùng `Cache-Control: no-store`.

## Nhà tuyển dụng xem phản hồi

`GET /interviews/{id}/invitation` trả `status`, `respondedAt`, `note`, `expiresAt`. Chưa tạo lời mời trả `NOT_SENT` và các trường còn lại null. Phạm vi đọc là giao của INTERVIEWS READ và CANDIDATES READ; chỉ được mời làm người phỏng vấn không tự cấp quyền xem ghi chú riêng của ứng viên. Link và token không được trả qua API này.

## Cấu hình và vận hành dự kiến

Các biến mới (cần tự cấu hình ở môi trường muốn dùng; chưa thay `.env` thật):

| Biến | Ý nghĩa |
|---|---|
| `INTERVIEW_INVITATIONS_ENABLED` | Mặc định `false`; `true` bật tạo lời mời và API phản hồi. Đặt lịch hàng loạt cần bật chức năng. |
| `INTERVIEW_INVITATIONS_DELIVERY_ENABLED` | Mặc định `false`; chỉ `true` mới chạy SMTP. Có thể lưu hàng đợi khi tạm tắt gửi. |
| `INTERVIEW_INVITATION_ENCRYPTION_KEY` | Base64 của 32 byte ngẫu nhiên, dùng riêng cho hàng đợi này. Không dùng JWT secret hoặc mật khẩu SMTP. |
| `INTERVIEW_INVITATION_KEY_ID` | Mặc định `v1`; không đổi/xóa key khi còn thư chờ. Chưa hỗ trợ key ring để giải mã khóa cũ. |
| `INTERVIEW_INVITATION_PAGE_URL` | HTTPS tới trang phản hồi trên backend; HTTP chỉ cho localhost/loopback. Không chứa query/fragment. |
| `INTERVIEW_INVITATION_TIME_ZONE` | Mặc định `Asia/Ho_Chi_Minh`, dùng khi viết email. Trang phản hồi hiển thị giờ Việt Nam. |
| `INTERVIEW_INVITATION_POLL_DELAY_MS` | Mặc định `60000`, mỗi lượt xử lý tối đa 10 thư. |

SMTP dùng `MAIL_*` hiện có. Khi thử local, giữ delivery `false` hoặc dùng máy chủ SMTP giả trên loopback. Không tự bật gửi thật.

V57 thêm hướng dẫn chuẩn bị, khóa chống lặp theo người tạo/requestId và hạn mức API công khai. V58 thêm lời mời và outbox; không tự gửi thư cho các lịch lịch sử. Token lưu dạng hash; địa chỉ, nội dung thư và token gốc trong outbox được mã hóa AES-GCM, ràng buộc với đúng bản ghi. Xóa payload mã hóa khi gửi xong/hủy/lỗi cuối.

Lỗi SMTP tạm thời thử lại sau 1, 5, 15, 60, 360 phút, tối đa 6 lần. Địa chỉ bị từ chối hoặc payload hỏng dừng sớm. Worker kiểm tra lại lịch/người nhận/quyền trước khi gửi, dùng lease riêng để worker cũ không ghi đè kết quả worker mới. SMTP hoạt động ngoài giao dịch database: có thể có thư trùng nếu tiến trình chết sau khi SMTP nhận thư. Không thể thu hồi thư đã được SMTP nhận; link cũ vẫn bị vô hiệu hóa khi đổi/hủy lịch.

Nếu cần dừng gửi, đặt delivery `false`; giữ key để xử lý thư đang chờ sau đó. Không sửa/xóa migration đã áp dụng. Việc chạy thật, cấu hình reverse proxy và triển khai VPS nằm ngoài đợt làm local này.
