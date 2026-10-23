# So sánh và tổng hợp đánh giá

Tài liệu cho các task 472–474 và 487. Hai API cần cả `CANDIDATES_READ_*` và `EVALUATIONS_READ_*`; người dùng chỉ được xem hồ sơ nằm trong phạm vi của cả hai quyền. Recruiter phụ trách hoặc quản lý phòng ban được đọc theo phân công hiện tại. Việc từng phụ trách hồ sơ không cấp quyền đọc sau khi phân công thay đổi. Response có `Cache-Control: no-store` và việc truy cập hồ sơ được ghi nhật ký.

## Tổng hợp một hồ sơ

`GET /api/v1/applications/{id}/evaluation-summary`

Trả về `applicationId`, `latestInterviewId`, `evidenceHash`, `aggregate` và `forms`. Mảng `forms` chứa những phiếu **hiện ở trạng thái `SUBMITTED`** của các buổi chưa hủy, kèm người đánh giá, kết luận, điểm và nhận xét. Phiếu nháp hoặc đã mở lại để sửa không được dùng, dù trước đó từng được nộp. Khi không có phiếu đủ điều kiện, mảng trả về rỗng và điểm tổng trung bình là `null`.

`aggregate` gồm `submittedCount`, `averageTotal` và `criteria`. `averageTotal` là trung bình cộng của các tổng điểm đã tính theo trọng số và lưu trên từng phiếu, làm tròn đến 2 chữ số thập phân theo quy tắc `HALF_UP`. Mỗi tiêu chí trả về số phiếu đóng góp và điểm trung bình. Nếu một phiếu không có tiêu chí đó, phiếu này không đóng góp vào phép tính của tiêu chí, thay vì được tính là 0 điểm. Các tiêu chí khác phiên bản nội dung, khung đánh giá hoặc trọng số được trả thành nhóm riêng, kể cả khi có cùng `criterionId`.

Người đang được mời mà chưa nộp phiếu của mình không thể xem điểm của người khác ở buổi đó. API trả HTTP 403 với mã `EVALUATION_BLIND_REVIEW`. Quy tắc này vẫn áp dụng khi người đó đồng thời là quản lý hoặc recruiter. Truy cập hồ sơ ngoài phạm vi được phép trả HTTP 403; hồ sơ không tồn tại trả HTTP 404.

`evidenceHash` là mã SHA-256 của trạng thái lịch, người được mời và các phiên bản phiếu. Frontend giữ giá trị này cùng dữ liệu đang hiển thị, rồi gửi lại trong trường `expectedEvidenceHash` khi gọi API quyết định tuyển dụng (`hiring-decisions`). Nếu dữ liệu đã thay đổi, API từ chối quyết định với HTTP 409 để người dùng tải lại và xem dữ liệu mới. Mã hash này chỉ dùng để phát hiện thay đổi; nó không cấp quyền truy cập.

## So sánh theo chức danh

`GET /api/v1/positions/{id}/candidate-comparison?page=0&size=20`

Trả về `positionId`, `items`, `totalElements`, `page` và `size`. Mỗi dòng trong `items` gồm `applicationId`, `candidateId`, `requisitionId`, `candidateName`, `finalInterviewId` và `aggregate`. Trường `aggregate` có cấu trúc như trên nhưng chỉ tính phiếu của buổi cuối. Bảng so sánh không trả nhận xét hoặc kết luận riêng của từng người đánh giá.

Để xuất hiện trong bảng, hồ sơ phải ở giai đoạn `INTERVIEW`, thuộc chức danh đang xem, nằm trong phạm vi được đọc và có ít nhất 1 phiếu hiện ở trạng thái `SUBMITTED` của buổi cuối. Cấu trúc dữ liệu hiện chưa có cờ đánh dấu vòng cuối, nên phiên bản này chọn **buổi chưa hủy có `startsAt` mới nhất**; nếu trùng thời gian, dùng ID để xác định thứ tự. Nếu đã xếp vòng mới nhưng chưa có phiếu được nộp ở vòng đó, API không lấy điểm vòng cũ để thay thế. Đây là quy ước triển khai, không phải một trường được bổ sung vào Jira.

Nếu người xem chưa đủ điều kiện xem điểm ở buổi cuối, hồ sơ đó bị bỏ khỏi cả danh sách lẫn tổng số. Phiếu chưa nộp ở vòng cũ không làm ẩn điểm vòng cuối mà người xem đã được phép đọc. Kết quả được sắp xếp theo điểm tổng trung bình giảm dần; nếu bằng điểm, dùng `applicationId` để xác định thứ tự. `page` nhận giá trị từ 0 đến 100.000; `size` từ 1 đến 100.

API giới hạn tối đa 1.000 hồ sơ ở giai đoạn `INTERVIEW` trong phạm vi được đọc cho một chức danh. Nếu vượt giới hạn, API trả HTTP 400 với mã `COMPARISON_TOO_LARGE`.

Không thể coi các tổng thuộc khung đánh giá khác nhau là hoàn toàn tương đương: API vẫn trả các nhóm tiêu chí và trọng số riêng để người quyết định thấy sự khác biệt. Xem [quyết định tuyển dụng](hiring-decisions.md) cho bước tiếp theo.
