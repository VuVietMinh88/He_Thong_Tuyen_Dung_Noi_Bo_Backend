# API lịch phỏng vấn

Backend của các task TKNHTTDNB1-428–432, 435–438, 441–444; thao tác đổi/hủy hỗ trợ 453–454.
Mọi API dùng `Authorization: Bearer <accessToken>` và trả `Cache-Control: no-store`.
Lịch gắn với **một hồ sơ ứng tuyển (`applicationId`)**, không chỉ với ứng viên, vì một người có thể ứng tuyển nhiều vị trí.

| Phương thức | Đường dẫn | Mục đích |
| --- | --- | --- |
| POST | `/api/v1/applications/{id}/interviews` | Đặt lịch, trả 201 |
| GET | `/api/v1/applications/{id}/interviews?page=0&size=20` | Các lịch của hồ sơ |
| GET | `/api/v1/interviews/{id}` | Chi tiết một lịch |
| GET | `/api/v1/interviews/me?page=0&size=20` | Các lịch mình đang được mời |
| POST | `/api/v1/interviews/conflicts` | Xem trước xung đột |
| PUT | `/api/v1/interviews/{id}` | Đổi lịch |
| POST | `/api/v1/interviews/{id}/cancel` | Hủy có lý do |
| POST | `/api/v1/interviews/bulk/preview` | Xem trước toàn bộ lịch hàng loạt |
| POST | `/api/v1/interviews/bulk` | Xác nhận lưu cả đợt, trả 201 |

API đọc cần `INTERVIEWS_READ_ALL` hoặc `INTERVIEWS_READ_SCOPED`. API ghi và xem trước xung đột cần cả quyền đọc và ghi `INTERVIEWS`.
Đối với thao tác quản lý, backend còn kiểm tra quyền đọc ứng viên và phạm vi của **đúng hồ sơ** bằng `CandidateAccess`: recruiter được phân công hiện tại, trưởng bộ phận trong phạm vi quản lý, hoặc người có `CANDIDATES_READ_ALL`.
Quyền `INTERVIEWS_*_ALL` riêng lẻ không cho recruiter mở hồ sơ ngoài phạm vi.
Nếu quyền đọc hoặc ghi `INTERVIEWS` bị giới hạn `SCOPED`, thao tác tương ứng còn phải nằm trong phạm vi phân công hoặc phòng ban quản lý, kể cả khi người dùng có `CANDIDATES_READ_ALL`.

Người đang nằm trong danh sách phỏng vấn có thể xem chi tiết lịch và lịch cá nhân. Việc được mời không tự cấp quyền đọc CV, hồ sơ đầy đủ, email, lương hoặc điểm đánh giá. Người đã bị bỏ khỏi danh sách mất quyền xem theo lời mời.

## Đặt lịch

```json
{
  "roundName": "Vòng chuyên môn",
  "startsAt": "2026-11-01T09:00:00+07:00",
  "durationMinutes": 60,
  "mode": "ONSITE",
  "room": "Phòng họp A",
  "meetingUrl": null,
  "preparationInstructions": "Mang theo CV và bài tập đã chuẩn bị.",
  "participantIds": ["11111111-1111-1111-1111-111111111111"],
  "overrideConflicts": false
}
```

Thay UUID bằng tài khoản thật và chọn thời gian tương lai. `startsAt` bắt buộc có múi giờ; response dùng UTC (`Z`).
Thời lượng từ 1 đến 480 phút; từ 1 đến 20 người, không trùng. Người phỏng vấn phải có tài khoản đang hoạt động và quyền ghi `EVALUATIONS`.
`ONSITE` cần `room` và không có `meetingUrl`; `ONLINE` cần URL HTTPS hợp lệ và không có `room`. Server chỉ lưu URL, không truy cập URL đó.
`roundName` tối đa 120 ký tự; phòng 200; URL 2000. Tạo lịch không tự đổi giai đoạn hồ sơ.
Yêu cầu tuyển dụng phải đang `APPROVED`; hồ sơ `REJECTED`, `WITHDRAWN`, `HIRED` không được đặt/đổi lịch.

`preparationInstructions` là hướng dẫn chuẩn bị tối đa 2000 ký tự. Khi bỏ trống, backend dùng “Vui lòng chuẩn bị CV và các thông tin liên quan đến kinh nghiệm của bạn.”; nội dung được lưu theo lịch để email dùng đúng hướng dẫn.

Response gồm `id`, `applicationId`, `roundName`, `startsAt`, `endsAt`, `mode`, `room`, `meetingUrl`, `status`, `version`, `participantIds`, `createdBy`, `createdAt`, `updatedAt`, `everSubmittedAt`, `preparationInstructions`.
`status` là `SCHEDULED` hoặc `CANCELLED`. Danh sách trả `{items,totalElements,page,size}`, sắp giờ bắt đầu giảm dần rồi ID; mặc định 20, tối đa 100 dòng, page từ 0 đến 1.000.000. Lịch cá nhân hiện bao gồm cả lịch cũ/hủy để người dùng xem lịch sử.

## Trùng lịch

POST `/api/v1/interviews/conflicts` dùng cùng các trường đặt lịch, thêm `applicationId`; khi sửa thêm `interviewId` để bỏ qua chính lịch đó.
Response là mảng `{interviewId,startsAt,endsAt,resourceType,participantId,room}`; `resourceType` là `INTERVIEWER` hoặc `ROOM`. Không trả tên/email ứng viên, đường dẫn họp hay điểm của lịch đang xung đột.
Hai khoảng `[bắt đầu, kết thúc)` giao nhau mới là trùng: 09:00–10:00 và 10:00–11:00 được phép. Phòng được chuẩn hóa Unicode, khoảng trắng và chữ hoa/thường trước khi so sánh.

Kết quả xem trước chỉ có giá trị tại thời điểm đọc. Khi lưu, backend kiểm tra lại trong transaction, dùng khóa chung cho thao tác xếp lịch để hai yêu cầu đồng thời không cùng vượt qua kiểm tra.
Mặc định xung đột trả 409 `INTERVIEW_CONFLICT`. Chỉ người có **vai trò RECRUITER** cùng quyền/phạm vi cần thiết được gửi `overrideConflicts:true` và `overrideReason` không rỗng (tối đa 2000 ký tự).
Ngoại lệ lưu người xác nhận, thời gian và lý do trong sự kiện. HR/ADMIN đơn lẻ không ghi đè xung đột.

## Đổi và hủy

PUT dùng đầy đủ body đặt lịch và thêm `expectedVersion` bằng `version` đang hiển thị. Server tăng phiên bản sau khi lưu. Client cũ nhận 409 `INTERVIEW_VERSION_CHANGED` và phải tải lại.

```json
{"expectedVersion": 0, "reason": "Ứng viên xin hủy buổi phỏng vấn"}
```

Body trên dành cho POST `/api/v1/interviews/{id}/cancel`. Lịch hủy vẫn giữ lịch sử; không xóa bản ghi.
Có thể hủy lịch sau khi hồ sơ bị rút/loại hoặc yêu cầu tuyển dụng dừng, để giải phóng thời gian đã giữ của phòng và người phỏng vấn. Các kiểm tra quyền, phiên bản và khóa sau đánh giá vẫn áp dụng.
Nếu đã có phiếu đánh giá nộp, đổi/hủy trả 409 `INTERVIEW_EVALUATION_SUBMITTED`. Marker `everSubmittedAt` được giữ kể cả khi recruiter mở lại phiếu; việc mở lại phiếu không mở khóa lịch.

Các lỗi khác: 400 `VALIDATION_ERROR`, 401 phiên hết hiệu lực, 403 thiếu quyền/ngoài phạm vi, 404 `INTERVIEW_NOT_FOUND` hoặc `APPLICATION_NOT_FOUND`, 409 `INTERVIEW_APPLICATION_INACTIVE`/`INTERVIEW_CANCELLED`, 503 `INTERVIEW_BUSY` khi không lấy được khóa trong thời gian giới hạn.

## Đặt lịch hàng loạt

Gửi cùng body cho API xem trước và xác nhận. Mỗi `applicationId` nhận một buổi riêng, theo **đúng thứ tự** trong mảng; các buổi nối tiếp nhau, không có thời gian nghỉ tự động.

```json
{
  "requestId": "33333333-3333-3333-3333-333333333333",
  "applicationIds": [
    "11111111-1111-1111-1111-111111111111",
    "22222222-2222-2222-2222-222222222222"
  ],
  "roundName": "Vòng chuyên môn",
  "windowStart": "2026-11-01T09:00:00+07:00",
  "windowEnd": "2026-11-01T11:00:00+07:00",
  "durationMinutes": 60,
  "mode": "ONSITE",
  "room": "Phòng họp A",
  "participantIds": ["44444444-4444-4444-4444-444444444444"],
  "preparationInstructions": "Mang theo CV và bài tập Java.",
  "overrideConflicts": false
}
```

Trong ví dụ này hồ sơ thứ nhất nhận 09:00–10:00, hồ sơ thứ hai nhận 10:00–11:00. Khung giờ phải không quá 24 giờ và đủ cho toàn bộ thời lượng. Có thể chọn từ 1 đến 50 hồ sơ khác nhau, từ nhiều yêu cầu tuyển dụng, miễn người thao tác được truy cập từng hồ sơ. Cùng một ứng viên có hai hồ sơ vẫn nhận hai buổi riêng.

Xem trước trả `{canCreate,slots}`. Mỗi phần tử `slots` gồm `applicationId`, `startsAt`, `endsAt`, `conflicts`; các xung đột dùng cùng cấu trúc ở mục trên. `canCreate:false` nghĩa là có trùng lịch chưa được xác nhận ghi đè. API xem trước không tạo lịch, thư mời, token hay bản ghi đợt; nhật ký truy cập hồ sơ vẫn được ghi.

Khi xác nhận, backend kiểm tra lại tất cả hồ sơ, quyền, người tham gia và xung đột trong **một transaction**. Nếu bất kỳ hồ sơ nào lỗi, cả đợt không lưu. Lịch, sự kiện và thư trong hàng đợi cùng được lưu hoặc cùng bị hủy; email được xử lý sau khi transaction hoàn tất. Cấu hình lời mời phải sẵn sàng trước cả xem trước và tạo đợt để không âm thầm bỏ thư mời.

Quy tắc ghi đè giống lịch đơn: chỉ `RECRUITER` có đúng quyền/phạm vi được gửi `overrideConflicts:true` cùng lý do. Chỉ buổi thực sự trùng mới ghi sự kiện có cờ ghi đè. Những cuộc gọi đồng thời khóa các tài khoản, yêu cầu tuyển dụng và hồ sơ theo thứ tự ổn định, rồi kiểm tra tài nguyên dùng chung; thứ tự khóa không làm thay đổi thứ tự buổi trả về.

Tạo thành công trả 201 với `{requestId,items}`, trong đó `items` là các `InterviewView` theo thứ tự đầu vào. Frontend tạo một UUID `requestId` khi chuẩn bị đợt để xem trước, giữ nguyên khi xác nhận; nếu mất kết nối hoặc thử lại, gửi **cùng UUID và cùng dữ liệu**. Backend trả lại kết quả đã lưu, không thêm lịch hay thư lần nữa. Khóa yêu cầu thuộc riêng người thao tác; không dùng chung giữa người dùng.

Đổi nội dung hoặc thứ tự hồ sơ nhưng giữ UUID cũ trả 409 `INTERVIEW_BULK_REQUEST_CHANGED`; người dùng phải xem trước lại và tạo UUID mới. Thử lại vẫn kiểm tra phiên đăng nhập, quyền hiện tại và phạm vi từng hồ sơ. Nếu được phép, kết quả là bản chụp lúc tạo ban đầu, kể cả khi lịch đã qua giờ hoặc về sau được đổi/hủy; dùng API chi tiết để đọc trạng thái hiện tại.

Migration V55 tạo lịch, thành viên và lịch sử; V56 chuẩn bị bảng phiếu đánh giá. Cả hai nằm trong `database/migrations-shared`, dùng được sau hai lịch sử database cũ mà không đổi checksum của migration đã chạy.
Các thao tác lịch có sự kiện riêng trong [timeline hồ sơ](application-timeline.md). Migration V57 bổ sung hướng dẫn chuẩn bị, khóa thử lại của đợt và hạn mức API công khai; V58 thêm lời mời và hàng đợi email. Tạo/đổi/hủy lịch gọi bộ phận thông báo trong cùng transaction; cấu hình gửi lời mời và phản hồi được mô tả riêng tại [API lời mời phỏng vấn](interview-invitations.md).
