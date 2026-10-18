# API duyệt yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-256 (chọn cấp duyệt theo phòng ban và hạn mức) và TKNHTTDNB1-257 (giữ phiên bản phê duyệt cho yêu cầu đang chạy), story TKNHTTDNB1-30 (S3-01). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`. Cấu hình luồng phê duyệt (task 254, 255) nằm ở [API cấu hình luồng phê duyệt](approval-flows.md).

Tiêu chí của story S3-01:

- "Khai báo chuỗi cấp duyệt theo phòng ban và theo mức lương đề xuất": đã có (254, 255).
- "Yêu cầu vượt hạn mức tự động thêm cấp duyệt cao hơn": đã có (256, mục "Chọn cấp duyệt").
- "Cấu hình sai (không có cấp duyệt nào khớp) bị chặn lưu": đã có. Cấp 1 không được có ngưỡng (255), nên mọi mức lương đều khớp ít nhất cấp 1.
- "Luồng đang chạy không bị ảnh hưởng khi cấu hình thay đổi": đã có (257, mục "Bản chụp chuỗi duyệt").

Task 256 chọn cấp duyệt, task 257 thêm `POST /requisitions/{id}/submit` (gửi duyệt) và chụp chuỗi duyệt lúc gửi.

## Trạng thái của yêu cầu tuyển dụng

| Mã | Tên | Ý nghĩa |
|---|---|---|
|`DRAFT`|Nháp|Đã lưu, chưa gửi duyệt (hoặc mới sao chép)|
|`PENDING_APPROVAL`|Chờ duyệt|Đã gửi duyệt, đang chờ cấp hiện tại|
|`RETURNED`|Cần bổ sung|Người duyệt yêu cầu bổ sung thông tin|
|`APPROVED`|Đã duyệt|Mọi cấp của lượt duyệt đã duyệt; tin tuyển dụng được tạo từ yêu cầu ở trạng thái này|
|`REJECTED`|Bị từ chối|Kết thúc; muốn thử lại thì sao chép yêu cầu|

V19 thêm bốn trạng thái mới cùng lúc. Task 257 chỉ dùng `DRAFT → PENDING_APPROVAL`; các bước còn lại đến từ quyết định duyệt.

Mỗi trạng thái được xếp sẵn vào các nhóm sau. `RequisitionStatus` ghi danh sách các nhóm, và test không biên dịch được cho tới khi một trạng thái mới được xếp nhóm.

| Mã | Sửa (`PUT`) | Gửi duyệt | Phân công recruiter | Tính vào định biên | Chặn xóa phòng ban (còn mở) | Sao chép |
|---|---|---|---|---|---|---|
|`DRAFT`|Có|Có|Có|Có|Có|Có|
|`PENDING_APPROVAL`|Không|Không|Có|Có|Có|Có|
|`RETURNED`|Không|Không|Có|Có|Có|Có|
|`APPROVED`|Không|Không|Có|Có|Có|Có|
|`REJECTED`|Không|Không|Không|Không|Không (vẫn chặn bằng khóa ngoại, 409 `DEPARTMENT_IN_USE`)|Có|

- Sửa yêu cầu không ở trạng thái sửa được trả 409 `REQUISITION_NOT_DRAFT`.
- Yêu cầu bị từ chối trả lại chỗ trong định biên. Không có bước nào đưa yêu cầu từ trạng thái không tính về trạng thái tính, nên gửi duyệt và quyết định duyệt không phải kiểm định biên lại.
- Bản sao luôn là `DRAFT` và **không** có lịch sử duyệt: lịch sử gắn với `id` của yêu cầu.
- Gửi duyệt và quyết định duyệt không đổi `updatedAt`, vốn là thời điểm sửa nội dung gần nhất.

## Gửi duyệt

`POST /requisitions/{id}/submit` gửi yêu cầu đã lưu đi duyệt. Body không bắt buộc:

```json
{ "comment": "Cần gấp cho dự án mới" }
```

`comment` là ghi chú cho người duyệt, tối đa 2.000 ký tự, không chứa ký tự NUL; để trống, hoặc không có ký tự nhìn thấy được (chỉ gồm khoảng trắng, kể cả khoảng trắng không ngắt U+00A0, xuống dòng, hoặc ký tự vô hình như U+200B), thì lưu `null`. Ghi chú có ký tự nhìn thấy được lưu đúng như gửi, giữ xuống dòng. Trường khác (ví dụ `status`) trả `INVALID_JSON`. Muốn đổi nội dung thì `PUT` trước rồi mới gửi.

Response 200 là yêu cầu tuyển dụng (cùng dạng `GET /requisitions/{id}`) với `status: "PENDING_APPROVAL"`.

Quyền: `REQUISITIONS_WRITE_ALL` hoặc `REQUISITIONS_WRITE_SCOPED`, theo cùng phạm vi như sửa yêu cầu. Vì response trả cả yêu cầu, người gọi còn phải **xem** được yêu cầu đó (giống sao chép): người chỉ có quyền ghi bị 403, kể cả với `{id}` không tồn tại.

Yêu cầu phải đủ thông tin; thiếu thì trả 400 `REQUISITION_INCOMPLETE` với `fieldErrors` gồm **mọi** trường còn thiếu:

| Trường | Thông báo |
|---|---|
|`proposedSalaryMin`|Vui lòng nhập lương đề xuất tối thiểu trước khi gửi duyệt.|
|`proposedSalaryMax`|Vui lòng nhập lương đề xuất tối đa trước khi gửi duyệt.|
|`neededBy`|Vui lòng chọn ngày cần người trước khi gửi duyệt.|
|`jobDescription`|Vui lòng nhập mô tả công việc trước khi gửi duyệt.|
|`candidateRequirements`|Vui lòng nhập yêu cầu ứng viên trước khi gửi duyệt.|

Rồi các kiểm tra của lần lưu chạy lại với giá trị hiện tại: ngày cần người không ở quá khứ, chức danh và phòng ban còn áp dụng, giải trình khi lương đề xuất ngoài dải chuẩn **hiện tại** của chức danh.

### Thứ tự kiểm

1. Quyền ở `SecurityConfiguration` (401/403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors.comment`.
3. Khóa tài khoản người gọi, rồi phiên; kiểm lại hạn token, phiên và quyền ghi (401 `SESSION_INVALID` / 403).
4. Khóa dòng yêu cầu, kiểm lại người gọi. Không có quyền xem yêu cầu nào thì 403 ngay.
5. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
6. Phòng ban của yêu cầu nằm trong phạm vi ghi và xem của người gọi (403).
7. Trạng thái cho phép gửi duyệt (409 `REQUISITION_NOT_SUBMITTABLE`). Bấm gửi hai lần thì lần sau nhận lỗi này.
8. Đủ thông tin (400 `REQUISITION_INCOMPLETE`).
9. Ngày cần người không ở quá khứ (400 `NEEDED_BY_IN_PAST`).
10. Khóa `FOR SHARE` chức danh rồi phòng ban: tồn tại và còn áp dụng (400 `INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`). Kiểm lại người gọi và phạm vi.
11. Giải trình khi ngoài dải chuẩn (400 `SALARY_JUSTIFICATION_REQUIRED`).
12. Chọn chuỗi duyệt (409 `APPROVAL_FLOW_NOT_CONFIGURED`, 409 `APPROVAL_LEVEL_UNAVAILABLE`).
13. Ghi lượt duyệt mới và chuyển yêu cầu sang `PENDING_APPROVAL`.

Định biên không được kiểm lại: yêu cầu đã được tính từ khi lưu và không xin thêm gì.

## Bản chụp chuỗi duyệt

Mỗi lần gửi duyệt mở một **lượt duyệt** (round). Lượt đầu là 1. Backend ghi một dòng `SUBMITTED` vào lịch sử duyệt (bảng `requisition_workflow_events`, V19) gồm:

- phiên bản cấu hình được dùng (`flow_id`, `flow_version`);
- số cấp áp dụng (`level_count`): lượt duyệt gồm các cấp 1 đến `level_count` của phiên bản đó;
- lương đề xuất tối đa lúc gửi (`salary_basis`), con số đã quyết định số cấp;
- người gửi, thời điểm gửi và ghi chú.

Phiên bản cấu hình đã niêm phong không bao giờ đổi hay bị xóa (V18), và dòng lịch sử tham chiếu tới nó bằng khóa ngoại, nên dòng này **chính là** bản chụp chuỗi duyệt. HR lưu phiên bản cấu hình mới thì các lượt duyệt đang chạy vẫn theo phiên bản cũ; chỉ lần gửi duyệt sau mới dùng phiên bản mới. Trong lúc chờ duyệt, yêu cầu không sửa được, nên lương và phòng ban đã quyết định số cấp cũng không đổi.

## Khóa và đồng thời

- Gửi duyệt khóa theo thứ tự như sửa yêu cầu: tài khoản người gọi, phiên, dòng yêu cầu, rồi `FOR SHARE` chức danh và phòng ban. Người gọi được kiểm lại sau mỗi lần chờ khóa: token hết hạn trả 401, bị gỡ quyền trả 403, bị Admin khóa hay đăng xuất trả 401.
- Lưu phiên bản cấu hình mới khóa dòng phòng ban `FOR UPDATE`. Vì vậy một lần gửi duyệt hoặc dùng phiên bản đã có trước đó, hoặc chờ tới khi phiên bản mới lưu xong rồi dùng phiên bản mới; không bao giờ dùng phiên bản đang lưu dở.
- Hai lần gửi duyệt cùng lúc chạy lần lượt trên dòng yêu cầu; lần sau trả 409 `REQUISITION_NOT_SUBMITTABLE`. Database có lớp chặn cuối: mỗi lượt duyệt chỉ có một dòng `SUBMITTED`, và số thứ tự (`revision`) không trùng trong một yêu cầu.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|`comment` quá 2.000 ký tự hoặc chứa ký tự NUL; `{id}` không phải UUID|
|400|`INVALID_JSON`|JSON sai hoặc có trường không được phép|
|400|`REQUISITION_INCOMPLETE`|Gửi duyệt khi còn thiếu thông tin (`fieldErrors` liệt kê đủ)|
|400|`NEEDED_BY_IN_PAST`, `INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`, `SALARY_JUSTIFICATION_REQUIRED`|Như khi lưu yêu cầu|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn hoặc phiên bị thu hồi, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền (kể cả bị gỡ quyền trong lúc chờ); không có quyền xem; yêu cầu ngoài phạm vi|
|404|`REQUISITION_NOT_FOUND`|Không có yêu cầu với `{id}`|
|409|`REQUISITION_NOT_SUBMITTABLE`|Yêu cầu không ở trạng thái gửi duyệt được (đã gửi rồi chẳng hạn)|
|409|`APPROVAL_FLOW_NOT_CONFIGURED`|Phòng ban chưa có cấu hình luồng duyệt|
|409|`APPROVAL_LEVEL_UNAVAILABLE`|Có cấp không có người duyệt hợp lệ, hoặc không đủ người khác nhau cho các cấp|

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
- người tạo và người gửi duyệt không tự duyệt, mỗi người chỉ duyệt một cấp trong một lượt;
- ai sửa được yêu cầu thì gửi duyệt được (người có quyền ghi theo phạm vi phòng ban), không chỉ người tạo;
- khi gửi duyệt bắt buộc có cả hai mức lương, ngày cần người, mô tả công việc và yêu cầu ứng viên;
- không kiểm định biên lại khi gửi duyệt hay khi duyệt, vì yêu cầu đã được tính từ lúc lưu;
- recruiter vẫn được phân công khi yêu cầu chờ duyệt, cần bổ sung hoặc đã duyệt; không phân công khi đã bị từ chối;
- gửi duyệt và duyệt không đổi `updatedAt`; bản sao không mang trạng thái hay lịch sử duyệt.
