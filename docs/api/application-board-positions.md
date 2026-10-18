# Chọn vị trí trên Kanban

`GET /api/v1/application-board/positions?page=0&size=50`

Gửi Access Token như các API nội bộ. Chỉ người có quyền đọc `CANDIDATES` được gọi; phạm vi giống API hồ sơ ứng viên. Không có phân công hoặc phòng ban quản lý phù hợp thì danh sách rỗng. API có thể trả yêu cầu tuyển dụng chưa nhận hồ sơ nào.

Response gồm `page`, `size`, `total`, `hasNext`, `items`. Mỗi phần tử có:

| Field | Cách dùng |
| --- | --- |
| `requisitionId` | Một yêu cầu tuyển dụng cụ thể; dùng làm giá trị dropdown |
| `positionId`, `positionName` | Vị trí trong danh mục; nhiều yêu cầu có thể chung vị trí |
| `departmentId`, `departmentName` | Giúp phân biệt yêu cầu tuyển dụng của các phòng ban |
| `assignedToMe` | Người hiện tại đang là recruiter chính hoặc hỗ trợ |
| `defaultSelected` | Yêu cầu này thuộc bộ lọc mặc định: ALL luôn đúng; SCOPED đúng khi được phân công |

Danh sách sắp theo tên vị trí, tên phòng ban rồi ID yêu cầu. `page` từ 0 đến 1.000.000, `size` từ 1 đến 100. `total` là tổng số yêu cầu đọc được, độc lập với trang.

FE gọi [API dữ liệu bảng](application-board.md) không truyền filter để nhận bảng mặc định. Khi chọn một lựa chọn, truyền `requisitionId`; khi muốn gom các yêu cầu cùng vị trí, truyền `positionId`. Backend vẫn lọc từng yêu cầu theo quyền, không vì chung vị trí mà cho đọc hồ sơ ngoài phân công. Quản lý phòng ban chưa được phân công recruiter có thể dùng danh sách này chọn yêu cầu mình được đọc.

Mỗi lần gọi kiểm tra quyền/phân công hiện tại. Sau khi bàn giao recruiter, người cũ mất lựa chọn và mất quyền gọi trực tiếp filter tương ứng. API danh sách không trả hồ sơ, lương hoặc thông tin liên hệ và không tạo nhật ký đọc hồ sơ. Response không được cache. Lỗi 400/401/403 theo hợp đồng API chung.
