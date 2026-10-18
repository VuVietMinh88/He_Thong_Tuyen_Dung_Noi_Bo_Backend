# Bảng Kanban ứng viên

API nội bộ dùng Access Token hiện có. Quyền `CANDIDATES_READ_ALL` đọc mọi hồ sơ; `CANDIDATES_READ_SCOPED` đọc hồ sơ của yêu cầu tuyển dụng đang được phân công hoặc thuộc phòng ban mình quản lý (gồm phòng ban con). Quyền ghi không tự cấp quyền đọc.

## Đọc bảng

`GET /api/v1/application-board?positionId=<uuid>&requisitionId=<uuid>&page=0&size=20`

- `positionId` là ID danh mục vị trí; `requisitionId` là ID một yêu cầu tuyển dụng cụ thể. Cả hai đều tùy chọn; khi truyền cùng nhau, API lấy phần giao của hai bộ lọc và phạm vi được phép.
- Không truyền bộ lọc: người có quyền ALL thấy tất cả; người có quyền SCOPED chỉ thấy yêu cầu tuyển dụng hiện được phân công PRIMARY hoặc SUPPORTING. Không có phân công thì bảng rỗng. Người quản lý phòng ban có thể chọn một yêu cầu thuộc phòng ban mình để xem.
- `page` bắt đầu từ 0, tối đa 1.000.000; `size` từ 1 đến 100, mặc định 20. Trang áp dụng riêng cho từng cột. `total` luôn là toàn bộ số hồ sơ phù hợp trong cột, không phải số thẻ trên trang.
- Trả đủ sáu cột theo thứ tự `NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`, kể cả cột rỗng. Hồ sơ `WITHDRAWN` không còn nằm trên bảng tuyển dụng.
- Thẻ sắp xếp theo `receivedAt` giảm dần rồi UUID giảm dần để ổn định khi trùng thời gian. Đây là phân trang theo offset; khi dữ liệu đổi giữa hai lần gọi, FE nên tải lại bảng.

Ví dụ một cột trong response:

```json
{
  "stage": "NEW",
  "stageName": "Hồ sơ mới",
  "total": 31,
  "hasNext": true,
  "cards": [{
    "applicationId": "uuid-ho-so-ung-tuyen",
    "candidateId": "uuid-ung-vien",
    "requisitionId": "uuid-yeu-cau-tuyen-dung",
    "positionId": "uuid-vi-tri",
    "fullName": "Nguyễn Văn A",
    "jobTitle": "Lập trình viên Java",
    "receivedAt": "2026-10-17T03:00:00Z",
    "stage": "NEW"
  }]
}
```

Response ngoài cùng gồm `positionId`, `requisitionId`, `assignedOnly`, `page`, `size`, `columns`. Thẻ không chứa email, điện thoại, CV, token tra cứu hoặc ghi chú nội bộ. Mỗi hồ sơ thực sự trả về được ghi nhật ký đọc `PROFILE`; số lượng và trang rỗng không làm lộ thông tin cá nhân. Việc từ chối bộ lọc xảy ra trước khi tìm hồ sơ nên không tạo nhật ký đọc hồ sơ.

## Lỗi

| HTTP | Ý nghĩa |
| --- | --- |
| 400 | ID, trang hoặc kích thước trang không hợp lệ |
| 401 | Token/phiên đăng nhập không hợp lệ |
| 403 | Thiếu quyền đọc hoặc bộ lọc cụ thể ngoài phạm vi |
| 404 | Vị trí hoặc yêu cầu tuyển dụng không tồn tại |

Tất cả response dùng `Cache-Control: no-store`. Thay đổi phân công có hiệu lực ở yêu cầu đọc tiếp theo. Các truy vấn đếm, lấy thẻ và ghi nhật ký trong cùng một transaction có snapshot nhất quán.
