# Nhập danh sách nhân sự từ Excel

Phạm vi TKNHTTDNB1-170–175 (story TKNHTTDNB1-20). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`.

Nhập hàng loạt cũng là tạo tài khoản, nên cả ba API dùng đúng quy tắc của `POST /accounts`: cần **vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`**. Theo seed hiện tại chỉ ADMIN dùng được; HR_MANAGER chỉ có quyền đọc tài khoản nên nhận 403. Chi tiết ở mục [Quyền truy cập](#quyền-truy-cập).

Hiện có API tải tệp mẫu (170), API đọc tệp để xem trước (171), trong đó mỗi dòng được kiểm tra giá trị và báo lỗi theo từng ô (172), API nhập thật: tạo tài khoản cho dòng hợp lệ, bỏ qua dòng lỗi (173), trả báo cáo tổng kết gồm số dòng thành công, số dòng bị bỏ qua và lý do của từng dòng bị bỏ qua (174), và chỉ người quản trị tài khoản nội bộ mới được xem trước và nhập (175).

## Quyền truy cập

Quyền được kiểm hai lớp, giống các API ghi tài khoản khác:

1. **Bộ lọc URL** trong `SecurityConfiguration`: ba URL `/accounts/import/**` cần đồng thời `ROLE_ADMIN` và `PERM_USER_ADMIN_WRITE_ALL`, như `POST /accounts`. Thiếu một trong hai là 403, controller không chạy.
2. **Service kiểm lại**: trước khi mở tệp, `StaffImportService` gọi `AccountProvisioningService.requireCreateAccess`, tức đúng phần kiểm quyền mà `POST /accounts` dùng. Phần này khóa dòng tài khoản rồi dòng phiên của người gọi (cùng thứ tự với các service tài khoản khác), sau đó đọc lại từ database: tài khoản đã kích hoạt và không bị khóa, phiên chưa đăng xuất, token chưa hết hạn, còn vai trò `ADMIN` và còn quyền `USER_ADMIN_WRITE_ALL`. Việc kiểm này chạy trong một transaction ngắn và commit ngay, nên không giữ khóa trong lúc đọc tệp. Khi nhập thật, mỗi dòng hợp lệ còn được kiểm lại lần nữa lúc tạo tài khoản.

Khóa ở lớp 2 có tác dụng khi một thay đổi đang được lưu đúng lúc request tới. Ví dụ Admin khác đang gỡ vai trò `ADMIN` hoặc khóa tài khoản của người gọi: request chờ thay đổi đó lưu xong rồi mới kiểm, nên thấy kết quả mới và trả 403 hoặc 401, không dùng quyền cũ mà bộ lọc đã thấy.

| Người gọi | Kết quả với cả ba API |
|---|---|
|Có vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`, phiên còn hợp lệ|Được dùng|
|HR_MANAGER (xem được `GET /accounts` nhưng không tạo được tài khoản)|403 `FORBIDDEN`|
|RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER, hoặc có nhiều vai trò trong số này nhưng không có `ADMIN`|403 `FORBIDDEN`|
|Vai trò khác `ADMIN` dù được cấp thêm `USER_ADMIN_WRITE_ALL`|403 `FORBIDDEN`|
|Có vai trò `ADMIN` nhưng `USER_ADMIN_WRITE_ALL` đã bị gỡ khỏi vai trò này|403 `FORBIDDEN`, kể cả với token cấp trước đó|
|Mất vai trò `ADMIN` hoặc quyền `USER_ADMIN_WRITE_ALL` trong lúc request đang chờ|403 `FORBIDDEN`|
|Bị khóa tài khoản hoặc đã đăng xuất trong lúc request đang chờ|401 `SESSION_INVALID`|
|Không gửi token, token sai hoặc hết hạn, phiên đã thu hồi, tài khoản bị khóa từ trước|401 `UNAUTHORIZED`|

Khi bị từ chối ở bước kiểm quyền này (401/403), server chưa đọc tệp: response chỉ là JSON lỗi `{code, message, fieldErrors}` (`fieldErrors` rỗng), không có dòng nào của tệp, không cho biết email nào đã có tài khoản; không tài khoản nào được tạo và không email nào được gửi. Riêng nhập thật còn có thể dừng với 401/403 giữa chừng, sau khi đã tạo một số dòng (xem [Dòng hợp lệ nhưng thất bại lúc tạo](#dòng-hợp-lệ-nhưng-thất-bại-lúc-tạo)). Frontend có thể ẩn chức năng nhập khi `GET /auth/me` không có vai trò `ADMIN` hoặc `GET /auth/permissions` không có `USER_ADMIN_WRITE_ALL`, nhưng server vẫn luôn tự kiểm như trên.

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
|401|SESSION_INVALID|Service kiểm lại sau khi qua bộ lọc thấy token vừa hết hạn, phiên vừa bị thu hồi hoặc tài khoản vừa bị khóa|
|403|FORBIDDEN|Không có vai trò `ADMIN` hoặc thiếu `USER_ADMIN_WRITE_ALL`, kể cả khi vừa mất trong lúc request chờ (xem [Quyền truy cập](#quyền-truy-cập))|

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

1. Phiên, vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL`, kiểm lại sau khi khóa tài khoản và phiên của người gọi (mục [Quyền truy cập](#quyền-truy-cập)). Người không có quyền nhận 401/403 trước khi server xem tới tệp.
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

Email đã có tài khoản và phòng ban được kiểm tra bằng hai truy vấn chỉ đọc cho cả tệp (bảng `user_accounts` và `departments`), không phải một truy vấn mỗi dòng. Kết quả phản ánh database **tại lúc xem trước**: nếu ai đó tạo tài khoản hoặc ngừng áp dụng phòng ban ngay sau đó, xem trước lần sau sẽ báo lỗi mới. Vì vậy bước nhập thật ([`POST /accounts/import`](#post-accountsimport)) đọc và kiểm tra lại tệp, không tin kết quả xem trước cũ.

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

## POST /accounts/import

Nhập thật: tạo tài khoản cho các dòng hợp lệ và bỏ qua các dòng lỗi. Request giống hệt xem trước: `multipart/form-data` với một trường tệp tên **`file`**, cùng giới hạn tệp và cùng quyền (vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL`). Frontend thường gọi xem trước rồi mới cho bấm nhập, nhưng server không cần và không nhận kết quả xem trước.

```js
const form = new FormData();
form.append('file', input.files[0]);
const response = await fetch(`${apiBaseUrl}/api/v1/accounts/import`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${accessToken}` },
  body: form,
});
```

### Server xử lý theo thứ tự

1. Phiên, vai trò `ADMIN` và quyền `USER_ADMIN_WRITE_ALL` như xem trước, kể cả khi tệp không có dòng hợp lệ nào. Người không có quyền nhận 401/403 trước khi server xem tới tệp.
2. Đọc tệp đúng như bước 2–10 của [xem trước](#server-kiểm-tra-theo-thứ-tự). Gặp lỗi cấp tệp (bảng [Lỗi](#lỗi)) thì cả tệp bị từ chối và **chưa tạo tài khoản nào**, kể cả cho các dòng đúng.
3. Kiểm tra lại mọi dòng như bước 11 của xem trước, theo database **lúc nhập**. Từ lúc xem trước, có thể đã có người tạo tài khoản trùng email hoặc ngừng áp dụng một phòng ban.
4. Đi lần lượt từng dòng theo thứ tự trong tệp:
   - Dòng có lỗi: bỏ qua, không ghi gì, không gửi email. Dòng vào danh sách `skipped` của báo cáo cùng đúng các lỗi mà xem trước báo cho dòng đó.
   - Dòng hợp lệ: tạo tài khoản bằng chính `AccountProvisioningService` của `POST /accounts`, trong **transaction riêng của dòng đó**. Transaction khóa và kiểm lại phiên, vai trò, quyền của người nhập; khóa phòng ban theo mã (`SELECT ... FOR SHARE`) và kiểm phòng ban còn áp dụng; kiểm email chưa có tài khoản; lưu tài khoản ở trạng thái chờ kích hoạt cùng vai trò, phòng ban, số điện thoại và chức danh; tạo token kích hoạt; cuối cùng gửi email mời giống `POST /accounts`. Dòng xong thì được commit ngay, trước khi sang dòng sau, và vào danh sách `created`.
5. Trả [báo cáo tổng kết](#response-200-báo-cáo-tổng-kết): số dòng thành công, số dòng bị bỏ qua và lý do của từng dòng bị bỏ qua.

Tài khoản tạo từ tệp giống hệt tài khoản tạo bằng `POST /accounts` rồi sửa hồ sơ bằng `PUT /accounts/{id}`: trạng thái `PENDING_ACTIVATION`, mật khẩu tạm và link kích hoạt (hạn theo `ACCOUNT_ACTIVATION_TTL`) chỉ có trong email gửi cho người đó.

### Dòng hợp lệ nhưng thất bại lúc tạo

Giữa bước 3 và lúc tạo một dòng, dữ liệu vẫn có thể đổi. Khi đó chỉ transaction của dòng ấy bị hủy; tài khoản của các dòng trước và sau không bị ảnh hưởng. Dòng đó vào `skipped` với đúng một lý do (cột giữa của bảng; xem thêm bảng [Lý do bỏ qua](#lý-do-bỏ-qua)).

| Tình huống lúc tạo dòng | Lý do trong báo cáo | Kết quả |
|---|---|---|
|Email vừa được dùng cho một tài khoản khác, kể cả khi hai Admin nhập cùng lúc (ràng buộc unique `user_accounts_email_key` quyết định ai tạo trước)|`EMAIL_ALREADY_EXISTS`, ô cột A|Tài khoản đã có giữ nguyên tên, vai trò, mật khẩu; không gửi email. Tiếp tục dòng sau|
|Phòng ban vừa ngừng áp dụng hoặc đổi mã|`INVALID_DEPARTMENT`, ô cột D|Không tạo gì. Tiếp tục dòng sau|
|Máy chủ thư trả lời nhưng **từ chối địa chỉ** người nhận (mã SMTP 5xx cho địa chỉ đó, ví dụ hộp thư không tồn tại)|`EMAIL_ADDRESS_REFUSED`, ô cột A|Tài khoản của dòng đó bị hủy như `POST /accounts`. Máy chủ thư vẫn hoạt động với địa chỉ khác nên tiếp tục dòng sau|
|**Máy chủ thư không hoạt động**: không kết nối được, đăng nhập SMTP sai, hết thời gian chờ, lỗi tạm thời (mã 4xx) hoặc lỗi khác|`ACCOUNT_EMAIL_UNAVAILABLE`, không gắn ô|Tài khoản của dòng đó bị hủy như `POST /accounts`. Server **dừng tạo**: `stoppedAtRow` là số dòng này; mỗi dòng hợp lệ sau đó vào `skipped` với lý do `NOT_ATTEMPTED` (không thử, không ghi gì, không gửi email); các dòng lỗi sau đó vẫn vào `skipped` với lỗi dữ liệu của chúng|
|Phiên hết hạn hoặc bị thu hồi, Admin bị khóa, mất vai trò `ADMIN` hoặc quyền `USER_ADMIN_WRITE_ALL`|Không có báo cáo|Request dừng ngay với 401 `SESSION_INVALID` hoặc 403 `FORBIDDEN`. Tài khoản đã tạo trước đó vẫn giữ|

Server phân biệt hai trường hợp gửi email lỗi theo lỗi mà thư viện gửi thư trả về (`AccountInvitationMailSender.refusedRecipient`). Từ chối địa chỉ là vấn đề của **riêng dòng đó**: máy chủ đã trả lời ngay nên thử dòng sau không tốn thời gian chờ. Máy chủ thư không hoạt động thì các dòng sau gần như chắc chắn cũng lỗi, và mỗi lần thử có thể chờ hết thời gian chờ SMTP (tới 5 giây mỗi bước), làm request 500 dòng kéo dài hàng chục phút, nên server dừng.

Xem trước không biết máy chủ thư có nhận một địa chỉ hay không, nên dòng bị từ chối địa chỉ vẫn hiện là hợp lệ khi xem trước; chỉ báo cáo nhập mới cho biết lý do `EMAIL_ADDRESS_REFUSED`. Khi đó hãy kiểm tra lại email của dòng.

**Nhập lại cùng tệp là an toàn.** Dòng đã tạo ở lần trước nay có email đã tồn tại nên bị bỏ qua với lý do `EMAIL_ALREADY_EXISTS`: không tạo trùng, không gửi email lần hai, không đổi mật khẩu tạm. Vì vậy sau khi request bị dừng (`stoppedAtRow` khác `null`, 401, 403, mất kết nối), Admin chỉ cần tải lại đúng tệp đó khi hệ thống đã ổn: dòng `stoppedAtRow` và các dòng có lý do `NOT_ATTEMPTED` sẽ được thử lại.

### Response 200: báo cáo tổng kết

Response là báo cáo tổng kết của lần nhập (TKNHTTDNB1-174): đếm số dòng, liệt kê các dòng đã tạo tài khoản và các dòng bị bỏ qua kèm lý do. Ví dụ một tệp có dòng 3 sai dữ liệu và máy chủ thư từ chối địa chỉ ở dòng 4:

```json
{
  "totalRows": 4,
  "createdCount": 2,
  "skippedCount": 2,
  "stoppedAtRow": null,
  "created": [
    { "rowNumber": 2, "email": "nguyen.van.an@example.com", "accountId": "8d6f3b8e-2f5c-4f8e-9a51-6f1d2c7b9e40" },
    { "rowNumber": 5, "email": "le.van.cuong@example.com", "accountId": "1f0c7a52-93d4-4b7e-8c1a-5e2b9d3f6a17" }
  ],
  "skipped": [
    {
      "rowNumber": 3,
      "email": "tran.thi.binh@example.com",
      "errors": [
        { "rowNumber": 3, "column": "fullName", "cell": "B3", "code": "REQUIRED", "message": "Họ và tên là bắt buộc." },
        { "rowNumber": 3, "column": "departmentCode", "cell": "D3", "code": "DEPARTMENT_INACTIVE", "message": "Phòng ban mã \"OLD\" đã ngừng áp dụng, không gán được nhân sự mới." }
      ]
    },
    {
      "rowNumber": 4,
      "email": "pham.thi.dung@example.com",
      "errors": [
        { "rowNumber": 4, "column": "email", "cell": "A4", "code": "EMAIL_ADDRESS_REFUSED", "message": "Máy chủ thư từ chối địa chỉ email này nên không gửi được email mời; chưa tạo tài khoản. Hãy kiểm tra lại email." }
      ]
    }
  ]
}
```

Khi máy chủ thư ngừng hoạt động ở dòng 3 của một tệp 4 dòng (dòng 2 đã tạo xong trước đó, dòng 4 sai email):

```json
{
  "totalRows": 4,
  "createdCount": 1,
  "skippedCount": 3,
  "stoppedAtRow": 3,
  "created": [
    { "rowNumber": 2, "email": "an@example.com", "accountId": "8d6f3b8e-2f5c-4f8e-9a51-6f1d2c7b9e40" }
  ],
  "skipped": [
    {
      "rowNumber": 3,
      "email": "binh@example.com",
      "errors": [
        { "rowNumber": 3, "column": null, "cell": null, "code": "ACCOUNT_EMAIL_UNAVAILABLE", "message": "Máy chủ thư không hoạt động nên không gửi được email mời; chưa tạo tài khoản. Việc nhập dừng ở dòng này; hãy nhập lại tệp khi máy chủ thư hoạt động." }
      ]
    },
    {
      "rowNumber": 4,
      "email": "sai-email",
      "errors": [
        { "rowNumber": 4, "column": "email", "cell": "A4", "code": "EMAIL_INVALID", "message": "Email không đúng định dạng, ví dụ đúng: nguyen.van.an@example.com." }
      ]
    },
    {
      "rowNumber": 5,
      "email": "cuong@example.com",
      "errors": [
        { "rowNumber": 5, "column": null, "cell": null, "code": "NOT_ATTEMPTED", "message": "Dòng hợp lệ nhưng chưa được nhập vì việc nhập đã dừng ở dòng 3 do máy chủ thư không hoạt động. Nhập lại tệp khi máy chủ thư hoạt động để tạo tài khoản này." }
      ]
    }
  ]
}
```

Header `Cache-Control: no-store`. Response là 200 kể cả khi không dòng nào được tạo hoặc server dừng giữa chừng.

| Trường | Ý nghĩa |
|---|---|
|totalRows|Số dòng nhân sự đọc được, bằng `totalRows` của xem trước cùng tệp và luôn bằng `createdCount + skippedCount`. Dòng trống không được đếm|
|createdCount|Số dòng thành công, tức số tài khoản đã tạo; bằng số phần tử của `created`|
|skippedCount|Số dòng bị bỏ qua (không tạo tài khoản, không gửi email); bằng số phần tử của `skipped`|
|stoppedAtRow|Số dòng Excel mà server không gửi được email mời vì máy chủ thư không hoạt động, rồi dừng tạo. `null` khi mọi dòng hợp lệ đều đã được thử. Giao diện nên báo rõ, ví dụ `Máy chủ thư lỗi ở dòng 3; các dòng sau chưa được nhập. Hãy nhập lại tệp sau.`|
|created|Các dòng đã tạo tài khoản chờ kích hoạt và gửi email mời. Mảng rỗng `[]` khi không tạo được dòng nào, không bao giờ `null`|
|created[].rowNumber|Số dòng Excel hiển thị bên trái sheet, giống `rowNumber` của xem trước|
|created[].email|Email như xem trước (bỏ khoảng trắng đầu/cuối, đổi về chữ thường)|
|created[].accountId|Id của tài khoản mới, dùng được với `GET /accounts/{id}`|
|skipped|Các dòng không được tạo tài khoản. Mảng rỗng `[]` khi mọi dòng đều được tạo, không bao giờ `null`|
|skipped[].rowNumber|Số dòng Excel|
|skipped[].email|Email như xem trước; `null` khi ô trống|
|skipped[].errors|Lý do bỏ qua, **luôn có ít nhất một phần tử**. Mỗi phần tử có các trường `rowNumber`, `column`, `cell`, `code`, `message` như lỗi của xem trước|

Mỗi dòng nhân sự có ở **đúng một** trong hai danh sách. Cả `created` và `skipped` đều theo thứ tự dòng trong tệp (số dòng tăng dần); trong một dòng, lỗi dữ liệu theo thứ tự cột A→F như xem trước. Vì vậy cùng tệp và cùng dữ liệu trong hệ thống luôn cho báo cáo theo cùng thứ tự.

### Lý do bỏ qua

| Nguồn | Mã | `column` / `cell` | Khi nào | Nhập lại cùng tệp |
|---|---|---|---|---|
|Lỗi dữ liệu|Các mã ở [Kiểm tra từng dòng](#kiểm-tra-từng-dòng): `REQUIRED`, `TOO_LONG`, `EMAIL_INVALID`, `EMAIL_ALREADY_EXISTS`, `EMAIL_DUPLICATED_IN_FILE`, `ROLE_UNKNOWN`, `DEPARTMENT_NOT_FOUND`, `DEPARTMENT_INACTIVE`, `PHONE_INVALID`|Ô có lỗi|Dòng sai khi server kiểm tra lại lúc nhập (bước 3). `errors` giống hệt `errors` mà xem trước trả cho dòng đó vào cùng thời điểm, có thể nhiều lỗi (mỗi cột tối đa một)|Vẫn bị bỏ qua cho tới khi sửa tệp hoặc dữ liệu hệ thống|
|Đổi trong lúc nhập|`EMAIL_ALREADY_EXISTS`|`email`, ô cột A|Email vừa được dùng cho tài khoản khác sau khi server kiểm tra, ví dụ hai Admin nhập cùng lúc. Cùng mã với lỗi dữ liệu và với lỗi 409 của `POST /accounts`; message nói rõ là vừa xảy ra trong lúc nhập|Vẫn bị bỏ qua (email đã có tài khoản)|
||`INVALID_DEPARTMENT`|`departmentCode`, ô cột D|Phòng ban vừa ngừng áp dụng hoặc đổi mã. Cùng mã với lỗi 400 của `PUT /accounts/{id}`|Bị bỏ qua với `DEPARTMENT_NOT_FOUND` hoặc `DEPARTMENT_INACTIVE`|
|Gửi email mời|`EMAIL_ADDRESS_REFUSED`|`email`, ô cột A|Máy chủ thư từ chối địa chỉ người nhận|Được thử lại, nhưng nhiều khả năng lại bị từ chối nếu không sửa email|
||`ACCOUNT_EMAIL_UNAVAILABLE`|`null` / `null`|Máy chủ thư không hoạt động ở dòng này; dòng này là `stoppedAtRow`. Cùng mã với lỗi 503 của `POST /accounts`|Được thử lại|
||`NOT_ATTEMPTED`|`null` / `null`|Dòng hợp lệ nằm sau `stoppedAtRow` nên chưa được thử. Message nêu dòng đã dừng|Được thử lại|

Lý do thuộc nhóm "đổi trong lúc nhập" và "gửi email mời" luôn là lỗi duy nhất của dòng. `column` và `cell` chỉ là `null` khi lý do không nằm ở ô nào (hai mã cuối); lỗi của xem trước luôn có `column` và `cell`. Giao diện có thể ghép `Dòng 4, ô A4: <message>`, hoặc `Dòng 5: <message>` khi `cell` là `null`.

Báo cáo không chứa mật khẩu tạm, token kích hoạt hay câu trả lời gốc của máy chủ thư (mã SMTP, tên máy chủ). Server không lưu báo cáo: nó chỉ có trong response này, nên muốn xem lại sau thì frontend phải tự giữ hoặc cho người dùng tải xuống.

### Lỗi và thời gian xử lý

Lỗi cấp tệp và lỗi quyền giống hệt [bảng Lỗi của xem trước](#lỗi) (400 `IMPORT_*`, `INVALID_MULTIPART`, 413 `FILE_TOO_LARGE`, 401, 403); khi gặp chúng chưa có tài khoản nào được tạo. Riêng 401 `SESSION_INVALID` và 403 `FORBIDDEN` còn có thể xảy ra giữa chừng như bảng ở trên.

Mỗi dòng hợp lệ được băm mật khẩu tạm (BCrypt) và gửi một email trong lúc request còn chờ, nên tệp nhiều dòng có thể mất từ vài chục giây tới vài phút (chưa đo trên máy thật). Phiên và token được kiểm lại ở mỗi dòng, mà access token chỉ sống 15 phút, nên frontend nên lấy token mới bằng `POST /auth/refresh` ngay trước khi nhập, hiện trạng thái đang xử lý, chặn bấm nhập lần hai và đặt thời gian chờ đủ dài. Nếu client ngắt kết nối giữa chừng, server có thể vẫn chạy tiếp tới hết tệp; xem lại danh sách tài khoản hoặc nhập lại cùng tệp như hướng dẫn ở trên.

## An toàn, database và phạm vi

Mọi ô trong tệp mẫu là ô chữ: không có công thức, macro hay liên kết ngoài. Tệp được tạo mới trong bộ nhớ ở mỗi yêu cầu, không ghi ra đĩa và không chứa dữ liệu tài khoản hoặc phòng ban, chỉ có tên các vai trò.

Khi đọc tệp tải lên, server không tính công thức và không chạy macro. Ngoài giới hạn 2 MB và 10 MB sau giải nén, Apache POI vẫn áp dụng các kiểm tra mặc định của `ZipSecureFile`, ví dụ từ chối tỉ lệ nén bất thường. Xem trước không mở transaction nên không giữ kết nối database trong lúc đọc tệp; hai truy vấn kiểm tra email và phòng ban chỉ chạy sau khi đọc xong.

Không thêm migration. Cả ba API khóa ngắn dòng `user_accounts` và `auth_sessions` của chính người gọi để kiểm quyền rồi commit ngay; ngoài ra tải tệp mẫu chỉ đọc bảng `roles` của V3, xem trước chỉ đọc quyền của người gọi, cột `email` của `user_accounts` và cột `code`, `active` của `departments`. Nhập thật ghi các bảng mà `POST /accounts` vẫn ghi (`user_accounts`, `user_roles`, `account_activation_tokens`), thêm các cột hồ sơ `department_id`, `phone`, `display_title` của V5, và chỉ khóa đọc (`FOR SHARE`) dòng `departments` được dùng. Tệp và nội dung tệp không được lưu ở đâu. Backend dùng thư viện Apache POI `poi-ooxml` 5.5.1 để tạo và đọc tệp `.xlsx`.
