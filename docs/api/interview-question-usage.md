# Câu hỏi trong buổi phỏng vấn — TKNHTTDNB1-482–484

Phần này dùng ngân hàng câu hỏi có sẵn, không tạo ngân hàng thứ hai. Người phỏng vấn mở phiếu đánh giá của mình, xem câu hỏi thuộc các tiêu chí của phiếu, rồi đánh dấu câu đã hỏi. Vòng sau xem lại câu đã hỏi để tránh lặp lại.

## Quyền và phạm vi

- Mọi API yêu cầu tài khoản và phiên đăng nhập còn hiệu lực, quyền đọc `INTERVIEWS`, đọc và ghi `EVALUATIONS`, cùng tư cách người được mời đang hoạt động trong đúng buổi phỏng vấn.
- Có quyền HR hoặc quản trị không tự cấp quyền xem câu hỏi của một buổi mà mình không được mời. Buổi không tồn tại và buổi ngoài phạm vi cùng trả `404 INTERVIEW_NOT_FOUND`.
- Phiếu đánh giá đóng băng các tiêu chí của chức danh lúc tạo. Vì vậy, trước khi xem hoặc đánh dấu câu hỏi, gọi API sẵn có `POST /api/v1/interviews/{id}/evaluations/me`. Nếu chưa có phiếu, trả `409 QUESTION_EVALUATION_REQUIRED`; GET không tự tạo phiếu.
- Tên tiêu chí lấy từ phiếu đã đóng băng. Việc đổi khung năng lực của chức danh sau đó không thay đổi câu hỏi đủ điều kiện của phiếu cũ.
- Không trả điểm, nhận xét đánh giá của đồng nghiệp hoặc thông tin liên hệ ứng viên. Tất cả phản hồi thành công có `Cache-Control: no-store`.

## Xem câu hỏi và đáp án gợi ý

`GET /api/v1/interviews/{id}/questions?criterionId={uuid}&page=0&size=20`

`criterionId` không bắt buộc. `page` từ 0 đến 100000, `size` từ 1 đến 100. Tiêu chí không khớp trả trang rỗng. Thứ tự cố định theo tên tiêu chí, ID tiêu chí và ID câu hỏi.

```json
{
  "items": [{
    "questionId": "00000000-0000-0000-0000-000000000001",
    "criterionId": "00000000-0000-0000-0000-000000000002",
    "criterionName": "Kỹ thuật",
    "content": "Giải thích transaction",
    "answerHint": "Tính nguyên tử và mức cô lập",
    "difficulty": "MEDIUM",
    "asked": false,
    "askedBy": null,
    "askedAt": null
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Câu chưa hỏi phải đang hoạt động trong ngân hàng và thuộc tiêu chí của phiếu. Câu đã hỏi vẫn xuất hiện bằng bản chụp lịch sử, ngay cả khi bị chuyển tiêu chí, ngừng dùng hoặc xóa khỏi ngân hàng. `answerHint` có thể là `null` nếu ngân hàng chưa có đáp án gợi ý.

## Đánh dấu đã hỏi

`POST /api/v1/interviews/{id}/questions/{questionId}/asked`

```json
{"expectedVersion": 0}
```

`expectedVersion` là phiên bản **lịch phỏng vấn**, lấy từ API lịch có sẵn, không phải phiên bản phiếu đánh giá. Chỉ chấp nhận trường này, kiểu số nguyên không âm. Không nhận `askedBy`, nội dung câu hỏi hay thời gian từ client.

Thành công trả HTTP 200 với một câu hỏi có `asked=true`, người đánh dấu và thời gian máy chủ. Cả hội đồng thấy cùng một dấu đã hỏi. Gửi lại cùng câu trong cùng lịch không tạo bản ghi mới và giữ nguyên người/thời gian đầu tiên. Không có API bỏ dấu hoặc sửa lịch sử.

Điều kiện trước khi ghi:

- Lịch vẫn `SCHEDULED`, đúng phiên bản đang hiển thị.
- Yêu cầu tuyển dụng vẫn `APPROVED`, hồ sơ chưa `REJECTED`, `WITHDRAWN`, `HIRED` hoặc `ONBOARDED`.
- Phiếu của người gọi đang nháp và chưa từng nộp. Mở lại phiếu đã nộp không mở lại quyền sửa câu đã hỏi.
- Câu đang hoạt động và thuộc tiêu chí trong phiếu của người gọi; câu đã đánh dấu được trả lại bằng bản chụp cũ.

Lỗi đáng chú ý: `INTERVIEW_VERSION_CHANGED`, `INTERVIEW_CANCELLED`, `INTERVIEW_APPLICATION_INACTIVE`, `EVALUATION_LOCKED` (409); `INTERVIEW_QUESTION_NOT_FOUND` (404); `INTERVIEW_QUESTIONS_BUSY` (503, có thể thử lại).

## Lịch sử các vòng trước

`GET /api/v1/interviews/{id}/question-history?criterionId={uuid}&page=0&size=20`

Trả cùng cấu trúc phân trang. Mỗi phần tử gồm `interviewId`, `roundName`, `interviewStartsAt` và `question` có cấu trúc như trên. Tên vòng, thời gian bắt đầu, nội dung, đáp án và tên tiêu chí được chụp khi đánh dấu.

Lịch sử chỉ gồm các buổi **cùng application**, khác buổi đang xem, bắt đầu trước và kết thúc không muộn hơn giờ bắt đầu buổi đang xem; bỏ buổi đã hủy. Không lấy hồ sơ khác của cùng một ứng viên và không lấy vòng tương lai hoặc vòng chồng giờ. Không bắt buộc người gọi từng tham gia vòng trước; việc được mời vào vòng đang xem cấp quyền đọc phần câu hỏi lịch sử này. Lịch sử không chứa kết quả đánh giá.

## Database và kiểm thử

`V59__create_interview_question_usage.sql` thêm một bảng dữ liệu lịch sử. Khóa `(interview_id, question_id)` ngăn đánh dấu lặp. Khóa ngoại kép giữ đúng hồ sơ của lịch; ID câu hỏi và tiêu chí không có khóa ngoại tới ngân hàng để lịch sử không bị mất khi dữ liệu nguồn thay đổi. Trigger từ chối sửa bản ghi đã lưu. Không chuyển đổi hoặc phát sinh lịch sử giả cho các buổi cũ.

Service khóa tài khoản/phiên, yêu cầu tuyển dụng, hồ sơ, lịch, phiếu và câu hỏi trước khi ghi; kiểm tra lại phiên và quyền sau khi chờ khóa. Không gửi email và không thay đổi điểm đánh giá.

Kiểm thử tập trung: `InterviewQuestionUsageIntegrationTest` (HTTP thực + PostgreSQL dùng riêng cho test). Chủ phiên chạy Maven một lượt cùng các tính năng liên quan; không chạy trên database thật.
