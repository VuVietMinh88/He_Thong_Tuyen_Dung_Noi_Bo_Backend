# API duyệt yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-256 (chọn cấp duyệt theo phòng ban và hạn mức), story TKNHTTDNB1-30 (S3-01). Cấu hình luồng phê duyệt (task 254, 255) nằm ở [API cấu hình luồng phê duyệt](approval-flows.md).

Tiêu chí của story S3-01:

- "Khai báo chuỗi cấp duyệt theo phòng ban và theo mức lương đề xuất": đã có (254, 255).
- "Yêu cầu vượt hạn mức tự động thêm cấp duyệt cao hơn": đã có (256, mục "Chọn cấp duyệt").
- "Cấu hình sai (không có cấp duyệt nào khớp) bị chặn lưu": đã có. Cấp 1 không được có ngưỡng (255), nên mọi mức lương đều khớp ít nhất cấp 1.

Task 256 chưa thêm API. Phần chọn cấp được dùng khi gửi duyệt.

## Chọn cấp duyệt

`ApprovalChainSelector` chọn chuỗi duyệt cho một yêu cầu:

1. Lấy cấu hình luồng duyệt của **đúng** phòng ban của yêu cầu, ở phiên bản hiện hành. Không lấy cấu hình của phòng ban cấp trên, không có cấu hình mặc định cho cả công ty. Phòng ban chưa có cấu hình thì trả 409 `APPROVAL_FLOW_NOT_CONFIGURED`.
2. So `proposedSalaryMax` của yêu cầu (lương tháng tối đa của một người, VND) với ngưỡng của từng cấp:
   - cấp không có ngưỡng luôn áp dụng;
   - cấp có ngưỡng chỉ áp dụng khi lương **lớn hơn** ngưỡng; bằng ngưỡng thì không thêm cấp.

   Vì cấp 1 không có ngưỡng và các ngưỡng sau không giảm, các cấp được chọn luôn là cấp 1 đến cấp k. Lương càng cao thì càng thêm cấp cao hơn. Quy tắc này dùng chung với `POST /approval-flows/preview` (`ApprovalChain`).
3. Kiểm mỗi cấp được chọn có người duyệt được (mục "Ai được duyệt một cấp"), và mỗi cấp có **một người khác nhau**, vì một người chỉ duyệt một cấp trong một lượt duyệt. Nếu không thì trả 409 `APPROVAL_LEVEL_UNAVAILABLE`, kèm tên các cấp không duyệt được, ví dụ "cấp 1 (Trưởng bộ phận)". Nếu không đủ người duyệt thì yêu cầu sẽ kẹt mãi ở một cấp, nên hệ thống chặn ngay lúc gửi.

Phần kiểm ở bước 3 đọc tài khoản mà không khóa. Mỗi lần duyệt, người duyệt được kiểm lại trên tài khoản đã khóa.

## Ai được duyệt một cấp

Mỗi cấp của cấu hình chọn **một tài khoản** (`approverUserId`) hoặc **một vai trò** (`approverRole`).

| Cấp chỉ định | Ai được duyệt |
|---|---|
|Một tài khoản|Chỉ tài khoản đó|
|Vai trò `APPROVER`, hoặc vai trò có `REQUISITIONS_WRITE_ALL` (theo seed: `HR_MANAGER`, `ADMIN`)|Bất kỳ ai giữ vai trò đó, với yêu cầu của mọi phòng ban|
|Vai trò khác (theo seed: `HIRING_MANAGER`, `RECRUITER`)|Người giữ vai trò đó **và** phụ trách phòng ban của yêu cầu hoặc một phòng ban cấp trên|

Ngoài ra người duyệt phải:

- được phép đăng nhập: đã kích hoạt, không bị quản trị khóa. Khóa tạm vì nhập sai mật khẩu không tính;
- có quyền ghi (`REQUISITIONS_WRITE_ALL` hoặc `_SCOPED`) **và** quyền xem (`REQUISITIONS_READ_ALL` hoặc `_SCOPED`) yêu cầu tuyển dụng. Quyền ghi không bao gồm quyền đọc;
- không phải người tạo yêu cầu, không phải người gửi duyệt lượt này;
- chưa duyệt cấp khác trong cùng lượt duyệt;
- với cấp chỉ định vai trò: không phải người được một cấp khác của cùng chuỗi chỉ định đích danh. Nhờ vậy cấp chỉ định đích danh luôn còn người duyệt.

`HR_MANAGER` và `ADMIN` không có quyền duyệt thay. Họ chỉ duyệt cấp chỉ định vai trò của họ hoặc tài khoản của họ.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend. Gồm:

- so ngưỡng bằng `proposedSalaryMax`, lớn hơn hẳn ngưỡng; không so số lượng người hay chi phí cả năm;
- phòng ban chưa có cấu hình thì không gửi duyệt được, không dùng cấu hình của phòng ban cấp trên;
- các cấp duyệt lần lượt, mỗi cấp một người quyết định; tối đa 20 cấp như cấu hình cho phép, chưa giới hạn 3 cấp như giả định của PO;
- người duyệt theo vai trò như bảng trên; `HR_MANAGER` và `ADMIN` không duyệt thay;
- người tạo và người gửi duyệt không tự duyệt, mỗi người chỉ duyệt một cấp trong một lượt.
