# Khóa và mở khóa tài khoản

Jira TKNHTTDNB1-162–166, story 19. Admin khóa tài khoản để chặn truy cập và thu hồi mọi phiên của người đó. Các URL dưới đây dùng tiền tố `/api/v1`.

Người gọi phải có **ADMIN và USER_ADMIN_WRITE_ALL**, gửi Bearer access token. Backend kiểm lại quyền, trạng thái tài khoản và phiên sau khi khóa bản ghi để tránh sử dụng quyền cũ khi phải chờ request khác.

## PUT /accounts/{id}/lock

`id` là UUID tài khoản đích. JSON bắt buộc:

```json
{"reason": "Nhân sự đã nghỉ việc, cần bàn giao công việc."}
```

`reason` bỏ khoảng trắng đầu/cuối, không trống, tối đa 500 ký tự. Trường JSON lạ như `enabled` hoặc `lockedBy` bị từ chối với HTTP 400, không cho client chọn người/thời điểm khóa. Admin không được tự khóa tài khoản của mình: HTTP 409 `SELF_ACCOUNT_LOCK`.

Thành công trả HTTP 200, `Cache-Control: no-store`:

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "status": "ADMINISTRATIVELY_LOCKED",
  "lockReason": "Nhân sự đã nghỉ việc, cần bàn giao công việc.",
  "lockedAt": "2026-10-06T08:00:00Z",
  "lockedBy": "00000000-0000-0000-0000-000000000002",
  "handoverWarning": "Vui lòng rà soát và bàn giao các vị trí tuyển dụng do tài khoản này phụ trách (nếu có)."
}
```

PUT lặp lại trên tài khoản đang khóa giữ người, thời điểm và lý do của lần khóa đầu; vẫn bảo đảm các phiên và reset link bị thu hồi. Đây không phải API sửa lý do khóa. Metadata mô tả lần khóa đang có, chưa phải lịch sử audit.

Sau khi transaction thành công, login bị từ chối; các access token và refresh token cũ không còn dùng được. Mọi phiên chưa bị thu hồi của **tài khoản đích** được thu hồi; phiên người khác giữ nguyên. Reset link chưa dùng bị vô hiệu hóa. API không đổi mật khẩu, vai trò, thông tin hồ sơ, trạng thái kích hoạt hay khóa 15 phút do nhập sai.

`handoverWarning` là lời nhắc luôn trả khi khóa. Backend chưa có module vị trí tuyển dụng nên chưa truy vấn số lượng/danh sách vị trí thực tế hoặc thực hiện bàn giao. Frontend hiển thị lời nhắc; nghiệp vụ xác định vị trí cần bàn giao còn phụ thuộc module đó.

## DELETE /accounts/{id}/lock

Không cần body. Chỉ bỏ khóa hành chính, trả HTTP 200 cùng cấu trúc trên: `lockReason`, `lockedAt`, `lockedBy`, `handoverWarning` là `null`. `status` thể hiện trạng thái còn lại: ACTIVE/PENDING_ACTIVATION/DISABLED/TEMPORARILY_LOCKED. Gọi lại trên tài khoản không bị khóa vẫn trả HTTP 200.

Mở khóa không phục hồi phiên/reset link đã thu hồi: người dùng cần đăng nhập lại hoặc yêu cầu reset link mới. Không tự kích hoạt tài khoản pending, không bỏ khóa 15 phút và không thay password/roles/profile.

Với tài khoản chưa kích hoạt, link kích hoạt được giữ. Khi đang khóa, gọi activate bị từ chối với HTTP 400 mà không tiêu thụ link; sau mở khóa, link còn hạn và chưa dùng có thể kích hoạt bình thường. Link hết hạn vẫn bị từ chối theo chính sách 24 giờ hiện có; API gửi lại lời mời chưa có trong nhóm này.

## Trạng thái và lỗi

GET `/accounts`, GET `/accounts/{id}` và bộ lọc `status` hỗ trợ thêm `ADMINISTRATIVELY_LOCKED`. Trạng thái này ưu tiên hơn các trạng thái khác khi `admin_locked_at` khác `NULL`; response AccountView hiện có giữ cấu trúc cũ, không chứa lý do khóa. Thông tin lý do/người/thời điểm có trong response của API khóa.

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Lý do trống/quá dài hoặc UUID sai|
|400|INVALID_JSON|Body sai định dạng hoặc có trường không hỗ trợ|
|401|Lỗi xác thực/phiên|Thiếu/sai/hết token hoặc người gọi bị khóa/phiên thu hồi|
|403|FORBIDDEN|Thiếu ADMIN hoặc quyền ghi quản trị|
|404|ACCOUNT_NOT_FOUND|Tài khoản đích không tồn tại|
|409|SELF_ACCOUNT_LOCK|Admin tự khóa tài khoản mình|

Lỗi login khi bị khóa vẫn dùng thông báo xác thực chung; không tiết lộ lý do khóa qua endpoint công khai. CORS dùng PUT/DELETE hiện có cho các origin được cấu hình.

## Migration và vận hành local

V6 thêm ba cột nullable: `admin_locked_at`, `admin_lock_reason` kiểu VARCHAR(500), `admin_locked_by` kiểu UUID tham chiếu tài khoản người khóa. Cả ba phải cùng `NULL` hoặc cùng có giá trị; DB kiểm lý do không trống. Tài khoản cũ mặc định không bị khóa hành chính. Không sửa V1–V5, không cần tạo database hay sao chép lại `.env`.

Sao lưu trước khi khởi động backend mới; Flyway tự áp dụng V6 và các migration còn thiếu. Kiểm thử tự động dùng PostgreSQL tạm. Không hạ về bản backend cũ bỏ qua trạng thái khóa mới khi còn tài khoản đang khóa; cần kế hoạch tương thích và kiểm tra dữ liệu trước rollback.
