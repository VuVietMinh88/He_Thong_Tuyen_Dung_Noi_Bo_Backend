# Lịch cá nhân của người phỏng vấn

Backend của TKNHTTDNB1-477–479. Dùng access token nội bộ; mọi response thành công có `Cache-Control: no-store`.

| Phương thức | Đường dẫn | Mục đích |
| --- | --- | --- |
| GET | `/api/v1/interviews/me/calendar` | Lịch chuẩn bị phỏng vấn và trạng thái phiếu riêng |
| GET | `/api/v1/interviews/{id}/cv` | CV của đúng hồ sơ gắn với buổi đang được mời |

Hai API cần quyền đọc `INTERVIEWS`, quyền đọc và ghi `EVALUATIONS`, cùng tài khoản/phiên đăng nhập còn hiệu lực. Backend đọc lại các quyền hiện tại. Phạm vi là **người dùng đang được mời trong buổi đó**; quyền ALL không thay điều kiện được mời. Lịch đã hủy và thành viên đã bị bỏ khỏi danh sách không còn cấp quyền qua hai API này.

Quyền chuẩn bị chỉ còn hiệu lực khi hồ sơ ở `NEW`, `SCREENING` hoặc `INTERVIEW` và yêu cầu tuyển dụng ở `APPROVED`. Khi hồ sơ chuyển sang `OFFER`, `HIRED`, `REJECTED`, `WITHDRAWN` hoặc yêu cầu tuyển dụng không còn `APPROVED`, API lịch bỏ toàn bộ các buổi của hồ sơ khỏi kết quả và tổng số, kể cả khi xem lịch sử hay bật `includePending`; API CV trả 403. Quy tắc này vẫn áp dụng nếu buổi phỏng vấn và danh sách người được mời còn được lưu để giữ lịch sử.

API `/api/v1/interviews/me` cũ giữ hợp đồng cũ. API mới không tự tạo phiếu đánh giá hoặc thay đổi giai đoạn hồ sơ.

## Xem lịch

Ví dụ xem các buổi sắp tới:

```http
GET /api/v1/interviews/me/calendar?page=0&size=20
Authorization: Bearer <accessToken>
```

Mặc định lấy các buổi đang diễn ra hoặc bắt đầu trong 30 ngày tiếp theo. Trả theo giờ bắt đầu tăng dần, rồi ID để phân trang ổn định.

| Tham số | Quy tắc |
| --- | --- |
| `from`, `to` | Thời điểm ISO 8601 có múi giờ, khoảng không quá 366 ngày, `from < to` |
| `includePending` | Mặc định `false`; `true` bổ sung các buổi trong 90 ngày qua mà phiếu của mình chưa nộp, nếu không chỉ định `from` |
| `page` | Mặc định 0, từ 0 đến 1.000.000 |
| `size` | Mặc định 20, từ 1 đến 100 |

Khoảng thời gian dùng điều kiện giao nhau: `endsAt > from` và `startsAt < to`. Buổi kết thúc đúng `from` hoặc bắt đầu đúng `to` không nằm trong khoảng.
Khi tự gửi `from`, API hiển thị lịch của mình vẫn đủ điều kiện chuẩn bị trong khoảng đó, gồm cả buổi đã nộp phiếu; đây là cách xem lại lịch sử hoặc tìm phiếu chưa nộp quá 90 ngày. `to` mặc định vẫn là hiện tại cộng 30 ngày, nên nên gửi cả hai mốc khi xem một tuần/tháng cũ. Khoảng tối đa 366 ngày vẫn áp dụng khi dùng `includePending`.

Response có `{items,totalElements,page,size,from,to,includePending}`. Mỗi phần tử:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "applicationId": "22222222-2222-2222-2222-222222222222",
  "candidateName": "Nguyễn Văn A",
  "positionId": "33333333-3333-3333-3333-333333333333",
  "positionName": "Lập trình viên Java",
  "roundName": "Vòng chuyên môn",
  "startsAt": "2026-11-01T02:00:00Z",
  "endsAt": "2026-11-01T03:00:00Z",
  "mode": "ONSITE",
  "room": "Phòng 1",
  "meetingUrl": null,
  "evaluationState": "NOT_STARTED",
  "evaluationSubmitted": false,
  "needsSubmission": false,
  "cvAvailable": true,
  "cvUrl": "/api/v1/interviews/11111111-1111-1111-1111-111111111111/cv"
}
```

`evaluationState` chỉ xét phiếu của người đang đăng nhập:

| Giá trị | Ý nghĩa |
| --- | --- |
| `NOT_STARTED` | Chưa tạo phiếu |
| `DRAFT` | Đã tạo hoặc lưu nháp, chưa từng nộp |
| `SUBMITTED` | Phiếu hiện đang ở trạng thái đã nộp |
| `REOPENED` | Phiếu từng nộp đã được recruiter mở lại và chưa nộp lại |

`evaluationSubmitted` bằng `true` chỉ khi trạng thái là `SUBMITTED`. Frontend có thể đánh dấu “Chưa nộp” khi giá trị này là `false`, kể cả trước giờ phỏng vấn. `needsSubmission` chỉ bật khi buổi đã kết thúc và phiếu hiện chưa nộp; dùng để hiện việc cần làm ngay. Người khác đã nộp hay chưa không đổi các cờ này. Kết quả không chứa điểm, nhận xét, kết luận, email, số điện thoại hoặc dải lương.

## Mở CV đúng buổi được mời

`cvUrl` là đường dẫn tương đối của backend và **vẫn cần Bearer token**; đây không phải link công khai. Frontend gọi API bằng token, nhận byte rồi tạo Blob URL: PDF xem trước, DOCX tải xuống. Nếu `cvAvailable=false` thì `cvUrl=null` và giao diện không hiện nút mở.

CV được trả với media type đã lưu, `Content-Length`, tên tệp đã xử lý an toàn, `X-Content-Type-Options: nosniff`, CSP `default-src 'none'; sandbox` và `Cache-Control: no-store`. PDF có `Content-Disposition: inline`; DOCX là `attachment`.

Lời mời cấp quyền xem **đúng CV của hồ sơ gắn với buổi đó** để chuẩn bị phỏng vấn. Người được mời không cần được phân công làm recruiter hoặc trưởng phòng. Backend không mở rộng `CandidateAccess`: API hồ sơ ứng viên, CV theo application và hồ sơ khác của cùng ứng viên vẫn dùng phạm vi cũ. Kể cả HR có quyền ALL cũng phải được mời để dùng đường dẫn CV dành cho buổi; HR có thể dùng API CV quản lý hiện có khi phù hợp.

Mỗi lần xem lịch ghi nhật ký PROFILE cho từng hồ sơ thực sự xuất hiện trong trang, tối đa một dòng cho cùng hồ sơ trong cùng response. Mở CV ghi GRANTED/CV cho đúng hồ sơ. Từ chối CV của buổi tồn tại do không còn được mời, lịch bị hủy hoặc hồ sơ/yêu cầu tuyển dụng không còn đủ điều kiện ghi DENIED/CV sau khi transaction đọc kết thúc. Phạm vi nhật ký là SCOPED theo lời mời, không phải cấp thêm quyền đọc ứng viên. Không đọc byte CV trước khi kiểm tra quyền, lời mời và điều kiện hồ sơ/yêu cầu tuyển dụng.

Lỗi chính: 400 `VALIDATION_ERROR` cho khoảng thời gian/phân trang/UUID sai; 401 thiếu hoặc hết hiệu lực phiên; 403 `FORBIDDEN` khi thiếu quyền hoặc không được mời/lịch đã hủy/hồ sơ hay yêu cầu tuyển dụng không còn đủ điều kiện; 404 `INTERVIEW_NOT_FOUND` cho buổi không tồn tại; 404 `APPLICATION_CV_NOT_FOUND` khi đúng buổi và đủ điều kiện nhưng hồ sơ không có CV.

Không thêm bảng, migration hay cấu hình môi trường. Các kiểm thử thuộc `PersonalInterviewCalendarIntegrationTest`; kết quả chạy thật được ghi ở tài liệu kiểm chứng chung của nhánh.
