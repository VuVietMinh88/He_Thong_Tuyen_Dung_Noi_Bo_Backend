# Nhập danh sách nhân sự từ Excel

Phạm vi TKNHTTDNB1-170 (story TKNHTTDNB1-20). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`.

Nhập hàng loạt cũng là tạo tài khoản, nên dùng đúng quy tắc của `POST /accounts`: cần **vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`**. `SecurityConfiguration` kiểm trước, sau đó `StaffImportService` đọc lại phiên, vai trò và quyền từ database ở mỗi yêu cầu. Theo seed hiện tại chỉ ADMIN dùng được; HR_MANAGER chỉ có quyền đọc tài khoản nên nhận 403.

Hiện có API tải tệp mẫu. Các API đọc tệp và xem trước, báo lỗi theo từng dòng, nhập hàng loạt và báo cáo tổng kết thuộc các task tiếp theo của story (171–175), chưa có trong tài liệu này.

## GET /accounts/import/template

Tải tệp Excel mẫu. Không có tham số, không có body, không cần header `Accept` đặc biệt.

Thành công trả **200** với body là nội dung nhị phân của tệp `.xlsx` (không phải JSON):

| Header | Giá trị |
|---|---|
|Content-Type|`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`|
|Content-Disposition|`attachment; filename="mau-nhap-nhan-su.xlsx"`|
|Cache-Control|`no-store`|

CORS expose header `Content-Disposition`, nên frontend chạy ở origin khác vẫn đọc được tên tệp. Cách tải thường dùng: gọi API bằng `fetch` kèm Bearer, đọc body thành `Blob`, rồi tạo link tải với tên tệp lấy từ `Content-Disposition`.

| HTTP | Mã | Trường hợp |
|---|---|---|
|401|UNAUTHORIZED|Thiếu, sai hoặc hết hạn token; phiên đã thu hồi; tài khoản bị khóa hoặc chưa kích hoạt|
|401|SESSION_INVALID|Service kiểm lại thấy token vừa hết hạn sau khi qua bộ lọc|
|403|FORBIDDEN|Không có vai trò `ADMIN` hoặc thiếu `USER_ADMIN_WRITE_ALL`|

Response lỗi là JSON `{code, message, fieldErrors}`, có `Cache-Control: no-store` và không có `Content-Disposition`.

## Cấu trúc tệp mẫu

Tệp có hai sheet:

1. **`Nhân sự`** (sheet đầu tiên): chỉ có dòng tiêu đề ở dòng 1, dữ liệu nhập từ dòng 2. Dòng tiêu đề được cố định khi cuộn. Sáu cột dùng định dạng Text để Excel giữ số 0 đầu của số điện thoại và không tự đổi mã thành số. Tiêu đề nền cam là cột bắt buộc, nền xám là cột có thể để trống. Sheet này cố ý không có dòng ví dụ để không ai nhập nhầm một người mẫu.
2. **`Hướng dẫn`**: quy định chung, bảng mô tả từng cột kèm ví dụ, và bảng mã vai trò hợp lệ.

### Các cột (hợp đồng ổn định)

Tiêu đề dòng 1 và mã cột là hợp đồng giữa tệp và API. Đổi tiêu đề, thứ tự hoặc mã cột sẽ làm hỏng các tệp quản trị viên đã điền; nguồn duy nhất trong code là enum `StaffImportColumn`.

| Cột | Tiêu đề (dòng 1) | Mã cột | Bắt buộc | Trường tài khoản | Quy tắc | Ví dụ |
|---|---|---|---|---|---|---|
|A|Email|`email`|Có|`email`|Email đăng nhập, tối đa 254 ký tự, chưa có tài khoản nào dùng. Bỏ khoảng trắng đầu/cuối và đổi về chữ thường|`nguyen.van.an@example.com`|
|B|Họ và tên|`fullName`|Có|`fullName`|Tối đa 255 ký tự|`Nguyễn Văn An`|
|C|Vai trò|`roles`|Có|`roles`|Một hoặc nhiều mã vai trò bên dưới, viết in hoa, cách nhau bằng dấu phẩy|`RECRUITER, INTERVIEWER`|
|D|Mã phòng ban|`departmentCode`|Không|`departmentId` (tìm theo `departments.code`)|Mã của phòng ban đang áp dụng, đúng chữ hoa/thường như danh mục phòng ban; để trống nếu chưa gán|`HR`|
|E|Số điện thoại|`phone`|Không|`phone`|Di động 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02; có thể ghi `+84` thay số 0 đầu (giống [hồ sơ cá nhân](profile.md))|`0912345678`|
|F|Chức danh hiển thị|`displayTitle`|Không|`displayTitle`|Chữ tự do tối đa 120 ký tự; không phải mã trong [danh mục chức danh](positions.md)|`Chuyên viên tuyển dụng`|

Quy tắc của các cột lấy theo `POST /accounts` và `PUT /accounts/{id}` trong [quản trị tài khoản](accounts.md). Mã cột là tên ổn định để API sau này báo lỗi theo từng ô; giao diện hiển thị tiêu đề tiếng Việt tương ứng.

### Mã vai trò hợp lệ

Sheet hướng dẫn liệt kê sáu vai trò nội bộ theo thứ tự `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Tên tiếng Việt được đọc từ cột `display_name` của bảng `roles` (V3) mỗi lần tải, nên luôn trùng với tên trong hệ thống. `CANDIDATE` không phải vai trò nội bộ nên không có trong danh sách.

### Quy định chung ghi trong sheet hướng dẫn

1. Chỉ nhập dữ liệu ở sheet đầu tiên `Nhân sự`; hệ thống chỉ đọc sheet đầu tiên.
2. Giữ nguyên dòng tiêu đề: không đổi tên, không đổi thứ tự, không thêm hoặc xóa cột.
3. Từ dòng 2, mỗi dòng là một nhân sự; tối đa 500 nhân sự trong một tệp.
4. Cột có tiêu đề nền cam là bắt buộc; cột nền xám có thể để trống.
5. Chỉ nhập giá trị, không dùng công thức; lưu tệp dạng `.xlsx`, dung lượng tối đa 2 MB.
6. Mỗi tài khoản được tạo ở trạng thái chờ kích hoạt và nhận email mời kích hoạt, giống khi tạo từng tài khoản.

Task 170 chỉ công bố các quy định này trong tệp mẫu. Việc từ chối tệp quá 2 MB hoặc quá 500 dòng, so khớp dòng tiêu đề và kiểm tra từng dòng do API đọc/nhập tệp ở các task sau thực hiện, dùng chung hằng số `StaffImportTemplate.MAX_DATA_ROWS`, `StaffImportTemplate.MAX_FILE_SIZE_MB` và enum `StaffImportColumn`.

Lưu ý cho task tải tệp lên: hiện `application.properties` chưa cấu hình multipart, mà Spring Boot mặc định từ chối tệp lớn hơn 1 MB (`spring.servlet.multipart.max-file-size`). Khi thêm API nhận tệp, phải nâng `spring.servlet.multipart.max-file-size` và `spring.servlet.multipart.max-request-size` lên ít nhất 2 MB và có test tải một tệp từ 1 đến 2 MB; nếu không, tệp đúng quy định 5 vẫn bị từ chối.

## An toàn, database và phạm vi

Mọi ô trong tệp mẫu là ô chữ: không có công thức, macro hay liên kết ngoài. Tệp được tạo mới trong bộ nhớ ở mỗi yêu cầu, không ghi ra đĩa và không chứa dữ liệu tài khoản hoặc phòng ban, chỉ có tên các vai trò.

Không thêm migration; chỉ đọc bảng `roles` của V3. Backend dùng thư viện Apache POI `poi-ooxml` 5.5.1 để tạo tệp `.xlsx`.
