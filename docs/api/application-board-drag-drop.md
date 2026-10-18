# Kéo thả thẻ Kanban

Kanban dùng chung [API chuyển giai đoạn](application-stages.md) `POST /api/v1/applications/{applicationId}/stage-changes`. Không có đường dẫn riêng cho phép bỏ qua kiểm tra quyền hoặc luật chuyển trạng thái.

Ví dụ kéo thẻ từ Hồ sơ mới sang Sàng lọc:

```json
{
  "expectedCurrentStage": "NEW",
  "targetStage": "SCREENING"
}
```

FE lấy `applicationId` và `stage` từ thẻ trong [response bảng](application-board.md), rồi gửi Access Token cùng request. Không dùng `candidateId` làm ID thao tác vì một người có thể ứng tuyển nhiều yêu cầu và mỗi hồ sơ có giai đoạn riêng.

1. Giữ giai đoạn cũ trong `expectedCurrentStage`; `targetStage` là cột đích.
2. Khi backend trả 200, tải lại bảng theo bộ lọc/trang hiện tại để nhận cả thẻ và số lượng mới. Nếu cập nhật giao diện trước khi có kết quả, giữ dữ liệu cũ để phục hồi khi request lỗi.
3. Nếu trả 409 `APPLICATION_STAGE_CHANGED`, một người khác hoặc ứng viên đã thay đổi hồ sơ. Tải lại bảng; không tự đổi `expectedCurrentStage` rồi gửi lại thao tác cũ.
4. Nếu trả 403, tài khoản không có quyền ghi hoặc hồ sơ đã ngoài phân công. Việc nhìn thấy thẻ chỉ chứng minh quyền đọc; không tự suy ra quyền kéo thả.
5. Những lỗi nghiệp vụ khác theo hợp đồng API chuyển giai đoạn. FE cần hiển thị thông báo lỗi và giữ trạng thái thực tế từ backend.

Backend kiểm tra cùng quyền đọc/ghi, phạm vi hồ sơ, giai đoạn mong đợi và luật chuyển của API giai đoạn. Thao tác thành công cập nhật hồ sơ và thêm một lịch sử STAFF trong cùng transaction; request lặp với giai đoạn cũ trả 409 và không thêm lịch sử thứ hai. Luật lý do loại và ngoại lệ bỏ qua phỏng vấn tiếp tục nằm trong API giai đoạn; FE dùng đúng các field của hợp đồng đó khi hiển thị các hộp thoại tương ứng.
