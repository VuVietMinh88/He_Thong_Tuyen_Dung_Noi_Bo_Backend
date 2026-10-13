# Nhập danh sách nhân sự từ Excel

Phạm vi TKNHTTDNB1-170–172 (story TKNHTTDNB1-20). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`.

Nhập hàng loạt cũng là tạo tài khoản, nên dùng đúng quy tắc của `POST /accounts`: cần **vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`**. `SecurityConfiguration` kiểm trước, sau đó `StaffImportService` đọc lại phiên, vai trò và quyền từ database ở mỗi yêu cầu. Theo seed hiện tại chỉ ADMIN dùng được; HR_MANAGER chỉ có quyền đọc tài khoản nên nhận 403.

Hiện có API tải tệp mẫu (170) và API đọc tệp để xem trước (171), trong đó mỗi dòng được kiểm tra giá trị và báo lỗi theo từng ô (172). Nhập hàng loạt bỏ qua dòng lỗi (173) và báo cáo tổng kết (174) thuộc các task tiếp theo của story, chưa có trong tài liệu này.

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

Quy tắc của các cột lấy theo `POST /accounts` và `PUT /accounts/{id}` trong [quản trị tài khoản](accounts.md). Mã cột là tên ổn định mà API xem trước dùng trong trường `column` của lỗi từng ô; giao diện hiển thị tiêu đề tiếng Việt tương ứng.

### Mã vai trò hợp lệ

Sheet hướng dẫn liệt kê sáu vai trò nội bộ theo thứ tự `ADMIN`, `HR_MANAGER`, `RECRUITER`, `HIRING_MANAGER`, `INTERVIEWER`, `APPROVER`. Tên tiếng Việt được đọc từ cột `display_name` của bảng `roles` (V3) mỗi lần tải, nên luôn trùng với tên trong hệ thống. `CANDIDATE` không phải vai trò nội bộ nên không có trong danh sách.

### Quy định chung ghi trong sheet hướng dẫn

1. Chỉ nhập dữ liệu ở sheet đầu tiên `Nhân sự`; hệ thống chỉ đọc sheet đầu tiên.
2. Giữ nguyên dòng tiêu đề: không đổi tên, không đổi thứ tự, không thêm hoặc xóa cột.
3. Từ dòng 2, mỗi dòng là một nhân sự; tối đa 500 nhân sự trong một tệp.
4. Cột có tiêu đề nền cam là bắt buộc; cột nền xám có thể để trống.
5. Chỉ nhập giá trị, không dùng công thức; lưu tệp dạng `.xlsx`, dung lượng tối đa 2 MB.
6. Mỗi tài khoản được tạo ở trạng thái chờ kích hoạt và nhận email mời kích hoạt, giống khi tạo từng tài khoản.

API xem trước (mục dưới) áp dụng quy định 1, 2, 3 và 5: chỉ đọc sheet đầu tiên, so khớp dòng tiêu đề, từ chối tệp quá 500 dòng hoặc quá 2 MB và từ chối công thức. Sau đó API kiểm tra giá trị từng ô theo cột "Quy tắc" ở trên (mục [Kiểm tra từng dòng](#kiểm-tra-từng-dòng)). Tệp mẫu và API đọc dùng chung hằng số `StaffImportTemplate.MAX_DATA_ROWS`, `StaffImportTemplate.MAX_FILE_SIZE_MB` và enum `StaffImportColumn`.

## POST /accounts/import/preview

Đọc tệp quản trị viên đã điền và trả các dòng để xem trước khi nhập; mỗi dòng được đánh dấu hợp lệ hoặc không, kèm lỗi của từng ô. API **không tạo gì**: không tạo tài khoản, không gửi email mời, không ghi tệp hay dữ liệu đọc được vào database hoặc ra đĩa. Gửi lại cùng tệp cho cùng kết quả, miễn là tài khoản và phòng ban trong hệ thống chưa đổi.

Request là `multipart/form-data` có một trường tệp tên **`file`**. Ví dụ ở frontend:

```js
const form = new FormData();
form.append('file', input.files[0]);
const response = await fetch(`${apiBaseUrl}/api/v1/accounts/import/preview`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form,
});
```

Không tự đặt header `Content-Type`: trình duyệt tự thêm `multipart/form-data; boundary=...`. Frontend **phải** kiểm tra `file.size <= 2 * 1024 * 1024` trước khi gửi và báo lỗi ngay cho người dùng: tệp tới khoảng 10 MB vẫn nhận được JSON `413 FILE_TOO_LARGE`, nhưng tệp lớn hơn nhiều có thể bị server đóng kết nối, khi đó `fetch` chỉ báo lỗi mạng (ví dụ `ERR_CONNECTION_RESET`) và không có JSON để đọc.

### Server kiểm tra theo thứ tự

1. Phiên, vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL` như tải tệp mẫu. Người không có quyền nhận 401/403 trước khi server xem tới tệp.
2. Có trường `file` và tệp không rỗng.
3. Tệp không lớn hơn 2 MB (2 × 1024 × 1024 byte). Tệp đúng 2 MB vẫn được nhận.
4. Tên tệp kết thúc bằng `.xlsx` (không phân biệt hoa/thường).
5. Nội dung sau khi giải nén không quá 10 MB. Tệp `.xlsx` là tệp zip; giới hạn này chặn tệp nhỏ nhưng giải nén ra rất lớn (zip bomb) trước khi đọc vào bộ nhớ. Theo ước tính, tệp mẫu điền đủ 500 dòng chỉ cỡ dưới 1 MB khi giải nén.
6. Mở được như một workbook `.xlsx`. Tệp `.xls` cũ, tệp `.csv` đổi đuôi, tệp có mật khẩu hoặc tệp hỏng đều bị từ chối.
7. Chỉ đọc **sheet đầu tiên** theo thứ tự trong tệp, không phụ thuộc tên sheet; các sheet khác bị bỏ qua.
8. Dòng 1 phải đúng sáu tiêu đề ở A1–F1 theo thứ tự của bảng cột ở trên; từ G1 trở đi phải trống. Server bỏ qua khoảng trắng đầu/cuối và khác biệt cách mã hóa dấu tiếng Việt (Unicode NFC/NFD), nhưng phân biệt chữ hoa/thường. Mã cột (`email`, `fullName`...) không thay được tiêu đề tiếng Việt.
9. Ô có công thức trong cột A–F (kể cả dòng tiêu đề) làm cả tệp bị từ chối; server không tính công thức. Ô ngoài cột A–F không được đọc.
10. Dòng có sáu ô A–F đều trống hoặc chỉ có khoảng trắng bị bỏ qua và không được đếm. Phải còn ít nhất 1 và không quá 500 dòng nhân sự.
11. Kiểm tra giá trị của từng dòng (mục [Kiểm tra từng dòng](#kiểm-tra-từng-dòng)). Dòng sai không làm hỏng cả tệp: response vẫn là 200 và dòng đó được đánh dấu `valid: false`.

Bước 1–10 sai thì cả tệp bị từ chối với một mã lỗi ở bảng [Lỗi](#lỗi), không có danh sách dòng.

### Response 200

```json
{
  "totalRows": 3,
  "validRows": 1,
  "invalidRows": 2,
  "rows": [
    {
      "rowNumber": 2,
      "email": "nguyen.van.an@example.com",
      "fullName": "Nguyễn Văn An",
      "roles": ["RECRUITER", "INTERVIEWER"],
      "departmentCode": "HR",
      "phone": "0912345678",
      "displayTitle": "Chuyên viên tuyển dụng",
      "valid": true,
      "errors": []
    },
    {
      "rowNumber": 4,
      "email": "tran.thi.binh@example.com",
      "fullName": null,
      "roles": ["HR_MANAGER", "BOSS"],
      "departmentCode": "OLD",
      "phone": null,
      "displayTitle": null,
      "valid": false,
      "errors": [
        {
          "rowNumber": 4,
          "column": "fullName",
          "cell": "B4",
          "code": "REQUIRED",
          "message": "Họ và tên là bắt buộc."
        },
        {
          "rowNumber": 4,
          "column": "roles",
          "cell": "C4",
          "code": "ROLE_UNKNOWN",
          "message": "Mã vai trò không hợp lệ: BOSS. Chỉ dùng các mã: ADMIN, HR_MANAGER, RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER."
        },
        {
          "rowNumber": 4,
          "column": "departmentCode",
          "cell": "D4",
          "code": "DEPARTMENT_INACTIVE",
          "message": "Phòng ban mã \"OLD\" đã ngừng áp dụng, không gán được nhân sự mới."
        }
      ]
    },
    {
      "rowNumber": 5,
      "email": "le.van.cuong@example.com",
      "fullName": "Lê Văn Cường",
      "roles": ["INTERVIEWER"],
      "departmentCode": null,
      "phone": "912345678",
      "displayTitle": null,
      "valid": false,
      "errors": [
        {
          "rowNumber": 5,
          "column": "email",
          "cell": "A5",
          "code": "EMAIL_ALREADY_EXISTS",
          "message": "Email đã được sử dụng cho một tài khoản nội bộ."
        },
        {
          "rowNumber": 5,
          "column": "phone",
          "cell": "E5",
          "code": "PHONE_INVALID",
          "message": "Số điện thoại không đúng định dạng: cần số di động 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02. Nếu Excel làm mất số 0 ở đầu, hãy định dạng ô là Text rồi gõ lại."
        }
      ]
    }
  ]
}
```

Ví dụ trên có dòng 3 để trống nên bị bỏ qua; tài khoản `le.van.cuong@example.com` đã có sẵn trong hệ thống và phòng ban `OLD` đã ngừng áp dụng. Header `Cache-Control: no-store`.

| Trường | Ý nghĩa và cách chuẩn hóa |
|---|---|
|totalRows|Số dòng nhân sự đọc được, bằng số phần tử của `rows` và bằng `validRows + invalidRows`|
|validRows|Số dòng không có lỗi|
|invalidRows|Số dòng có ít nhất một lỗi|
|rowNumber|Số dòng Excel hiển thị bên trái sheet (dòng tiêu đề là 1), để quản trị viên tìm lại dòng|
|email|Bỏ khoảng trắng đầu/cuối, đổi về chữ thường, giống `POST /accounts`|
|fullName|Bỏ khoảng trắng đầu/cuối|
|roles|Tách theo dấu phẩy, bỏ khoảng trắng và mục rỗng, đổi sang chữ in hoa, bỏ mã lặp, giữ thứ tự. Ô trống cho mảng rỗng `[]`|
|departmentCode|Bỏ khoảng trắng đầu/cuối, giữ nguyên chữ hoa/thường|
|phone|Bỏ khoảng trắng đầu/cuối; `+84` ở đầu đổi thành `0`, giống hồ sơ cá nhân|
|displayTitle|Bỏ khoảng trắng đầu/cuối|
|valid|`true` khi dòng không có lỗi, tức là bước nhập sẽ tạo tài khoản cho dòng này|
|errors|Danh sách lỗi theo thứ tự cột A→F, mỗi cột tối đa một lỗi. Dòng hợp lệ có mảng rỗng `[]`, không bao giờ `null`|

Ô trống trả `null` (riêng `roles` là `[]`). Ô kiểu số, ngày hoặc TRUE/FALSE được đọc theo chữ Excel hiển thị: mã phòng ban gõ dạng số `101` trả `"101"`, không thành `101.0`. Số điện thoại gõ dạng số (ô không ở định dạng Text) đã mất số 0 đầu ngay trong Excel, nên server nhận `912345678` và báo `PHONE_INVALID`.

Giá trị sai vẫn được trả nguyên như đã đọc (sau khi chuẩn hóa) để quản trị viên đối chiếu với lỗi.

### Kiểm tra từng dòng

Server kiểm tra **mọi** dòng, nên quản trị viên thấy toàn bộ lỗi của tệp trong một lần thay vì sửa từng lỗi một. Quy tắc giống tạo một tài khoản bằng `POST /accounts` (email, họ tên, vai trò) và sửa hồ sơ bằng `PUT /accounts/{id}` (phòng ban, số điện thoại, chức danh) trong [quản trị tài khoản](accounts.md). Mỗi cột chỉ báo **quy tắc đầu tiên** bị vi phạm, theo thứ tự trong bảng.

| Cột | Mã lỗi | Khi nào |
|---|---|---|
|A `email`|`REQUIRED`|Ô trống|
||`TOO_LONG`|Dài hơn 254 ký tự|
||`EMAIL_INVALID`|Sai định dạng. Server dùng chính ràng buộc `@Email` của `CreateAccountRequest`, nên nhận đúng những email mà `POST /accounts` nhận|
||`EMAIL_ALREADY_EXISTS`|Đã có tài khoản dùng email này, ở bất kỳ trạng thái nào (đang hoạt động, chờ kích hoạt, bị khóa). Cùng mã với lỗi 409 của `POST /accounts`|
||`EMAIL_DUPLICATED_IN_FILE`|Email (sau khi bỏ khoảng trắng và đổi chữ thường) có ở hơn một dòng của tệp. **Mọi** dòng trùng đều bị đánh dấu, message nêu các dòng còn lại, ví dụ `dòng 4, 6`, để quản trị viên tự chọn giữ dòng nào|
|B `fullName`|`REQUIRED`|Ô trống hoặc chỉ có khoảng trắng|
||`TOO_LONG`|Dài hơn 255 ký tự|
|C `roles`|`REQUIRED`|Ô trống hoặc chỉ có dấu phẩy|
||`ROLE_UNKNOWN`|Có mã không thuộc sáu vai trò nội bộ (kể cả `CANDIDATE`). Message nêu các mã sai và danh sách mã hợp lệ|
|D `departmentCode`|`TOO_LONG`|Dài hơn 50 ký tự|
||`DEPARTMENT_NOT_FOUND`|Không có phòng ban mang **đúng** mã này; `hr` khác `HR`|
||`DEPARTMENT_INACTIVE`|Phòng ban đã ngừng áp dụng (`active = false`); giống `PUT /accounts/{id}`, không gán nhân sự mới vào phòng ban này|
|E `phone`|`PHONE_INVALID`|Không phải di động 10 chữ số bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02 (sau khi đổi `+84` đầu thành `0`). Có khoảng trắng giữa các số cũng sai|
|F `displayTitle`|`TOO_LONG`|Dài hơn 120 ký tự|

Các cột D, E, F không bắt buộc: ô trống không có lỗi. Độ dài đếm theo cách của Java (`String.length()`), giống `@Size` của API tạo và sửa tài khoản.

Mỗi lỗi trong `errors` có các trường:

| Trường | Ý nghĩa |
|---|---|
|rowNumber|Số dòng Excel, trùng `rowNumber` của dòng chứa lỗi|
|column|Mã cột ổn định: `email`, `fullName`, `roles`, `departmentCode`, `phone`, `displayTitle`|
|cell|Địa chỉ ô, ví dụ `A5`; gõ vào ô Name Box của Excel để nhảy tới đúng ô|
|code|Mã lỗi ổn định trong bảng trên, để frontend lọc hoặc tô màu|
|message|Câu tiếng Việt hiển thị được ngay. Message không lặp lại số dòng; giao diện có thể ghép thành `Dòng 5, ô A5: Email đã được sử dụng...`|

Email đã có tài khoản và phòng ban được kiểm tra bằng hai truy vấn chỉ đọc cho cả tệp (bảng `user_accounts` và `departments`), không phải một truy vấn mỗi dòng. Kết quả phản ánh database **tại lúc xem trước**: nếu ai đó tạo tài khoản hoặc ngừng áp dụng phòng ban ngay sau đó, xem trước lần sau sẽ báo lỗi mới. Vì vậy bước nhập thật (task 173) phải kiểm tra lại, không tin kết quả xem trước cũ.

Xem trước cho biết một email đã có tài khoản hay chưa. Chỉ người có vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL` gọi được API này; người khác nhận 401/403 trước khi server đọc tệp, nên không dò được email.

### Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|IMPORT_FILE_REQUIRED|Không có trường `file`, tệp 0 byte, hoặc body không phải multipart (ví dụ gửi JSON)|
|400|IMPORT_FILE_INVALID|Tên tệp không kết thúc `.xlsx`; không mở được như `.xlsx`; nội dung giải nén vượt 10 MB|
|400|IMPORT_HEADER_INVALID|Dòng 1 của sheet đầu tiên không đúng tiêu đề mẫu. Message nêu ô sai đầu tiên, ví dụ `ô B1 phải là "Họ và tên"`|
|400|IMPORT_FORMULA_NOT_ALLOWED|Ô trong cột A–F có công thức. Message nêu địa chỉ ô, ví dụ `D3`|
|400|IMPORT_FILE_EMPTY|Không có dòng nhân sự nào sau dòng 1|
|400|IMPORT_TOO_MANY_ROWS|Hơn 500 dòng nhân sự|
|400|INVALID_MULTIPART|Body multipart hỏng, ví dụ bị cắt giữa chừng|
|413|FILE_TOO_LARGE|Tệp lớn hơn 2 MB (hoặc cả request lớn hơn 3 MB). Server từ chối ngay khi thấy vượt giới hạn, không mở workbook. Chỉ chắc chắn nhận được JSON này khi request không quá khoảng 10 MB; lớn hơn nữa thì kết nối có thể bị đóng (xem đoạn dưới bảng)|
|401|UNAUTHORIZED, SESSION_INVALID|Như tải tệp mẫu|
|403|FORBIDDEN|Như tải tệp mẫu|

Response lỗi là JSON `{code, message, fieldErrors}` có `Cache-Control: no-store`; `message` là tiếng Việt, hiển thị được ngay cho người dùng. Gặp một lỗi trong bảng này thì cả tệp không được đọc, không có danh sách dòng.

Giới hạn tải lên nằm trong `application.properties`: `spring.servlet.multipart.max-file-size=2MB`, `max-request-size=3MB` (chừa chỗ cho phần form bao quanh tệp) và `file-size-threshold=2MB` để tệp tải lên nằm trong bộ nhớ, không ghi ra thư mục tạm, vì tệp chứa dữ liệu cá nhân. `INVALID_MULTIPART` và `FILE_TOO_LARGE` của tầng multipart do `ApiExceptionHandler` trả, nên dùng chung cho các API tải tệp sau này.

Khi từ chối một request chưa nhận hết, Tomcat vẫn đọc bỏ phần body còn lại để kết nối không bị ngắt giữa chừng và client đọc được response 413. `server.tomcat.max-swallow-size=10MB` giới hạn phần đọc bỏ này (mặc định của Tomcat chỉ 2 MB, khi đó tệp 5 MB đã bị ngắt kết nối thay vì nhận JSON). Phần còn lại vượt 10 MB thì Tomcat đóng kết nối để không tốn băng thông cho tệp quá lớn, nên frontend vẫn phải tự kiểm tra dung lượng trước khi gửi. Giới hạn này áp dụng cho mọi API, không riêng API nhập nhân sự.

## An toàn, database và phạm vi

Mọi ô trong tệp mẫu là ô chữ: không có công thức, macro hay liên kết ngoài. Tệp được tạo mới trong bộ nhớ ở mỗi yêu cầu, không ghi ra đĩa và không chứa dữ liệu tài khoản hoặc phòng ban, chỉ có tên các vai trò.

Khi đọc tệp tải lên, server không tính công thức và không chạy macro. Ngoài giới hạn 2 MB và 10 MB sau giải nén, Apache POI vẫn áp dụng các kiểm tra mặc định của `ZipSecureFile`, ví dụ từ chối tỉ lệ nén bất thường. Xem trước không mở transaction nên không giữ kết nối database trong lúc đọc tệp; hai truy vấn kiểm tra email và phòng ban chỉ chạy sau khi đọc xong.

Không thêm migration. Tải tệp mẫu chỉ đọc bảng `roles` của V3; xem trước chỉ đọc phiên và quyền của người gọi, cột `email` của `user_accounts` và cột `code`, `active` của `departments`. Backend dùng thư viện Apache POI `poi-ooxml` 5.5.1 để tạo và đọc tệp `.xlsx`.
