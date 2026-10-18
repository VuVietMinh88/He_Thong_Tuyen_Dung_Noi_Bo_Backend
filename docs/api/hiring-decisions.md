# Quyết định tuyển dụng sau phỏng vấn

Phạm vi backend: TKNHTTDNB1-488, 489, 490. Hiring Manager xem bản tổng hợp đánh giá rồi chọn **đề xuất Offer** hoặc **Loại**. API ghi người quyết định, thời điểm, ghi chú, lý do loại và bản chụp đánh giá; đồng thời chuyển pipeline trong cùng một transaction.

## Quyền và điều kiện

- Vai trò `HIRING_MANAGER`, `HR_MANAGER` hoặc `ADMIN`, có cả quyền đọc `CANDIDATES` và `EVALUATIONS` còn hiệu lực.
- Hiring Manager chỉ quyết định hồ sơ thuộc phòng ban mình quản lý, bao gồm phòng ban con. Được gán làm recruiter ở phòng ban khác không mở rộng quyền quyết định này. HR/Admin tuân theo phạm vi giao nhau của hai quyền đọc.
- Hồ sơ đang ở `INTERVIEW`, yêu cầu tuyển dụng đang `APPROVED`.
- Buổi cuối là buổi **không bị hủy**, có `startsAt` mới nhất; nếu cùng giờ thì chọn theo UUID giảm dần. Buổi này phải có ít nhất một phiếu đang `SUBMITTED` của người phỏng vấn còn được phân công. Phiếu đang mở lại về `DRAFT` không được tính là đã nộp.
- Nếu người quyết định cũng được mời phỏng vấn, quy tắc chấm độc lập vẫn áp dụng: phải nộp phiếu của mình trước khi sử dụng đánh giá của người khác.
- Phiên bản bằng chứng `expectedEvidenceHash` phải khớp `evidenceHash` của bản tổng hợp vừa xem. Thay đổi lịch, người phỏng vấn hoặc trạng thái/phiên bản phiếu có thể làm hash thay đổi.

API này không cấp quyền viết `CANDIDATES` nói chung cho Hiring Manager. API chuyển giai đoạn cũ giữ nguyên quyền và quy tắc hiện tại.

## Gửi quyết định

`POST /api/v1/applications/{applicationId}/hiring-decisions`

Header: `Authorization: Bearer <accessToken>` và `Content-Type: application/json`.

```json
{
  "expectedCurrentStage": "INTERVIEW",
  "decision": "REJECT",
  "expectedEvidenceHash": "<evidenceHash gồm 64 ký tự hex từ bản tổng hợp>",
  "rejectionReasonId": "<UUID lý do loại đang hoạt động>",
  "note": "Kinh nghiệm chưa phù hợp yêu cầu của vị trí."
}
```

| Trường | Quy tắc |
| --- | --- |
| `expectedCurrentStage` | Bắt buộc, đúng `INTERVIEW`; dữ liệu cũ bị từ chối nếu hồ sơ đã chuyển giai đoạn. |
| `decision` | Bắt buộc, `OFFER` hoặc `REJECT`. `REJECT` chuyển pipeline sang `REJECTED`. |
| `expectedEvidenceHash` | Bắt buộc, 64 ký tự hex viết thường, lấy nguyên giá trị từ bản tổng hợp. |
| `rejectionReasonId` | Bắt buộc khi `REJECT`, phải thuộc danh mục `REJECTION_REASON` đang hoạt động; không gửi khi `OFFER`. |
| `note` | Không bắt buộc, văn bản thuần tối đa 500 ký tự; chuẩn hóa xuống dòng Windows, chuỗi chỉ có ký tự không nhìn thấy được xem như không gửi. |

Không nhận thêm trường như người quyết định, thời gian, điểm, giai đoạn đích. Server tự xác định các giá trị này.

Thành công: HTTP `200`, `Cache-Control: no-store`. Response gồm `decisionId`, `applicationId`, `interviewId`, `decision`, `stage`, `decidedAt`, `decidedById`, `decidedByName`, `evidenceHash`; thêm `note`, `rejectionReasonId`, `rejectionReasonName` nếu có. Response không chứa lại điểm hoặc nhận xét chi tiết.

Gửi lặp sau khi thành công trả `409 APPLICATION_STAGE_CHANGED`, không tạo lịch sử thứ hai. Frontend tải lại hồ sơ khi nhận `409`; không tự đổi hash rồi gửi lại quyết định khi người dùng chưa xem bằng chứng mới.

## Lỗi nghiệp vụ

| HTTP / mã | Cách xử lý |
| --- | --- |
| `400 VALIDATION_ERROR` | Sửa dữ liệu theo thông báo từng trường. |
| `400 REJECTION_REASON_REQUIRED` | Chọn lý do loại. |
| `400 REJECTION_REASON_NOT_ALLOWED` | Bỏ lý do loại khi đề xuất Offer. |
| `400 INVALID_REJECTION_REASON` / `REJECTION_REASON_INACTIVE` | Tải lại danh mục và chọn lý do hợp lệ. |
| `401` / `403 FORBIDDEN` | Đăng nhập lại hoặc kiểm tra quyền và phạm vi hồ sơ. |
| `403 EVALUATION_BLIND_REVIEW` | Nộp phiếu của chính mình trước. |
| `404 APPLICATION_NOT_FOUND` | Hồ sơ không tồn tại. |
| `409 APPLICATION_STAGE_CHANGED` | Tải lại pipeline; không ghi đè quyết định khác hoặc việc rút hồ sơ. |
| `409 HIRING_REQUISITION_INACTIVE` | Chỉ quyết định khi yêu cầu tuyển dụng đang hoạt động. |
| `409 HIRING_EVIDENCE_CHANGED` | Xem lại bản tổng hợp mới rồi quyết định. |
| `409 HIRING_EVALUATION_REQUIRED` | Chờ phiếu đã nộp ở buổi phỏng vấn cuối cùng. |
| `503 HIRING_DECISION_BUSY` | Thử lại sau; transaction bị từ chối không giữ một phần thay đổi. |

## Database và tính nhất quán

Migration mới `V60__create_hiring_decisions.sql` dùng chung hai nhánh lịch sử migration. Bảng `hiring_decisions` có một hàng cho mỗi hồ sơ, liên kết đúng sự kiện chuyển giai đoạn và buổi phỏng vấn cuối. Bản chụp các phiếu đã nộp lưu trong `evidence_snapshot`; sửa danh mục hoặc mở lại phiếu về sau không sửa bằng chứng đã lưu. Trigger từ chối cập nhật/xóa hàng quyết định.

Thứ tự khóa: tài khoản → phiên → yêu cầu tuyển dụng → hồ sơ → các buổi phỏng vấn → các phiếu đánh giá → lý do loại. Sau các bước chờ khóa, server kiểm tra lại phiên và quyền. Pipeline, sự kiện pipeline, quyết định và nhật ký đọc hồ sơ cùng commit hoặc cùng rollback. API không gọi service `REQUIRES_NEW` bên trong một transaction đang giữ khóa khác.

Không gửi email Offer, không tạo đề nghị lương và không cập nhật Jira trong chức năng này. Giao diện nhân viên cần nối API tổng hợp và endpoint quyết định.
