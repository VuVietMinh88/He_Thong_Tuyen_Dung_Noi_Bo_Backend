# API phiếu đánh giá phỏng vấn

Hợp đồng triển khai local của TKNHTTDNB1-459–463 và 466–469. Cần nghiệm thu bằng bộ test của nhánh trước khi phát hành.
Mọi request dùng access token nội bộ; response có `Cache-Control: no-store`.

| Phương thức | Đường dẫn | Hành vi |
| --- | --- | --- |
| POST | `/api/v1/interviews/{id}/evaluations/me` | Tạo phiếu của mình từ khung năng lực, hoặc trả phiếu đã có; không cần body |
| GET | `/api/v1/interviews/{id}/evaluations/me` | Đọc phiếu của mình, không tự tạo dữ liệu |
| PUT | `/api/v1/interviews/{id}/evaluations/me` | Lưu nháp |
| POST | `/api/v1/interviews/{id}/evaluations/me/submit` | Lưu đầy đủ câu trả lời và nộp phiếu trong một transaction |
| GET | `/api/v1/interviews/{id}/evaluations` | Xem các phiếu đã nộp của người khác sau khi mình đã nộp |
| GET | `/api/v1/interviews/{id}/evaluations/{formId}/status` | Recruiter lấy trạng thái và phiên bản để mở lại phiếu, không đọc câu trả lời |
| POST | `/api/v1/interviews/{id}/evaluations/{formId}/reopen` | Recruiter mở lại phiếu có lý do |

Tất cả thành công trả 200. Đọc phiếu riêng hoặc phiếu người khác cần quyền đọc `EVALUATIONS`; tạo/lưu/nộp cần quyền đọc và ghi `EVALUATIONS`. Với các API phiếu này, người thao tác phải đang được mời trong đúng buổi. Quyền `ALL` không cho HR/ADMIN nộp thay người khác hoặc bỏ qua điều kiện được mời. Xóa người khỏi danh sách mời sẽ thu hồi quyền truy cập theo buổi đó. Hai API trạng thái và mở lại dành cho recruiter dùng quyền/phạm vi được mô tả ở mục mở lại.
Lời mời không tự cấp quyền xem toàn bộ hồ sơ/CV hoặc dải lương của chức danh.

## Tạo và lưu phiếu

POST tạo phiếu chụp lại mã/tên khung, mã/tên/mô tả tiêu chí, trọng số và thứ tự từ khung năng lực đang ACTIVE của chức danh thuộc hồ sơ.
Các lần gọi lại trả phiếu đã có. Việc sửa khung hoặc chuyển chức danh sang khung khác sau đó không thay các tiêu chí/trọng số của phiếu này.

PUT lưu nháp và POST nộp cùng dùng body:

```json
{
  "expectedVersion": 0,
  "items": [
    {"criterionId": "11111111-1111-1111-1111-111111111111", "score": 4, "comment": "Giải thích rõ cách xử lý tình huống"},
    {"criterionId": "22222222-2222-2222-2222-222222222222", "score": 3, "comment": null}
  ],
  "conclusion": "CONSIDER"
}
```

Lấy `criterionId` và `version` từ response phiếu; không tự dùng tiêu chí của khung hiện tại. Mỗi tiêu chí xuất hiện tối đa một lần. Điểm là số nguyên 1–5; nhận xét tối đa 2000 ký tự.
Lưu nháp thay toàn bộ câu trả lời: tiêu chí bỏ khỏi `items` sẽ trở lại chưa chấm, không giữ âm thầm điểm cũ. Nháp cho phép điểm/kết luận chưa nhập.
Nộp phải đủ mọi tiêu chí, mọi điểm và kết luận `HIRE` (Nên tuyển), `CONSIDER` (Cân nhắc) hoặc `NO_HIRE` (Không nên tuyển). Chỉ nộp sau thời điểm kết thúc buổi; lịch đã hủy không nhận nộp phiếu.

Server tính `totalScore = Σ(score × weight) / 100`, làm tròn hai chữ số thập phân theo HALF_UP. Nếu chưa chấm đủ, tổng là `null`.
Ví dụ điểm 4 với trọng số 60% và điểm 3 với trọng số 40% cho tổng **3,60/5**. Tổng điểm do server tính; không nhận tổng từ frontend làm kết quả.

Response: `id`, `interviewId`, `applicationId`, `evaluatorId`, `framework:{id,code,name}`, `status`, `version`, `items` chứa snapshot và câu trả lời, `conclusion`, `totalScore`, `firstSubmittedAt`, `submittedAt`, `createdAt`, `updatedAt`.
Phiên bản tăng khi ghi. Request dùng phiên bản cũ nhận 409 `EVALUATION_VERSION_CHANGED`; tải lại dữ liệu trước khi sửa tiếp.

## Khóa, mở lại và giữ độc lập khi chấm

Phiếu đã nộp có `status=SUBMITTED`; sửa trực tiếp nhận 409 `EVALUATION_LOCKED`.
Chỉ người có vai trò **RECRUITER**, quyền đọc/ghi `INTERVIEWS` và quyền đọc/phạm vi đúng hồ sơ mới mở lại được. Recruiter không cần là người được mời trong buổi. Trước khi mở lại, gọi `GET /api/v1/interviews/{id}/evaluations/{formId}/status` để lấy `version` hiện tại, rồi gửi số đó bằng `expectedVersion`. API trạng thái dùng cùng điều kiện quyền/phạm vi với thao tác mở lại và chỉ trả sáu field metadata được liệt kê dưới đây. Body mở lại:

```json
{"expectedVersion": 2, "reason": "Người phỏng vấn cần bổ sung nhận xét còn thiếu"}
```

Thời hạn mở lại là **trước khi đủ 24 giờ từ lần nộp đầu tiên**. Nộp lại không kéo dài hạn này. Backend giữ người mở, thời gian, lý do và nội dung từng lần nộp trong lịch sử riêng.
Response mở lại chỉ có `id`, `interviewId`, `status`, `version`, `firstSubmittedAt`, `updatedAt`. Không trả điểm, nhận xét hay kết luận qua API này; người tuyển dụng mở phiếu không vì thế được bỏ qua điều kiện xem đánh giá.
Sau khi mở lại, phiếu trở về DRAFT; người chấm không xem được đánh giá người khác cho đến khi nộp lại.
Trước khi phiếu của mình có trạng thái SUBMITTED, gọi API xem phiếu của người khác nhận 403 `EVALUATION_BLIND_REVIEW`; server không trả một phần điểm hay nhận xét.

Nộp phiếu đặt marker `everSubmittedAt` trên lịch phỏng vấn. Mở lại phiếu không xóa marker này: lịch vẫn khóa đổi/hủy để đánh giá không bị gắn sang một buổi khác.
Các thao tác nộp, sửa lịch và mở lại dùng khóa transaction để yêu cầu đồng thời không vượt qua trạng thái khóa hoặc ghi đè bằng phiên bản cũ.

Timeline hồ sơ chỉ chứa sự kiện nộp/mở lại cùng ID buổi, ID phiếu, người và thời gian. Điểm, nhận xét, kết luận và bản chụp lần nộp được bảo vệ trong API đánh giá.

Các lỗi chính: 400 dữ liệu sai; 401 phiên không còn hợp lệ; 403 thiếu quyền/không được mời/chưa nộp phiếu riêng; 404 không có lịch/phiếu; 409 phiên bản cũ, phiếu khóa, buổi chưa kết thúc, quá hạn mở lại hoặc trạng thái không phù hợp. Lỗi khung năng lực dùng mã hiện có của [API tiêu chí](evaluation-criteria.md).
Nếu chờ khóa dữ liệu quá lâu, trả 503 `EVALUATION_BUSY`. Frontend có thể tải lại phiếu và cho người dùng thử lại; không coi request này là đã lưu thành công.
