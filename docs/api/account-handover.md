# API công việc cần bàn giao khi khóa tài khoản

Góp ý số 10 "Khóa tài khoản: danh sách bàn giao công việc". Phần 1: xem tài khoản còn phụ trách những việc gì. Phần 2: giao từng yêu cầu tuyển dụng của tài khoản cho một recruiter khác. Phần 3: giao từng phòng ban mà tài khoản đang làm trưởng phòng cho một trưởng phòng mới. Trước khi Admin [khóa một tài khoản](account-locking.md), hộp thoại khóa liệt kê các việc này để giao từng việc cho đúng người nhận. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response, thành công hay lỗi, đều có `Cache-Control` chứa `no-store`.

`GET` chỉ đọc: nó không bàn giao và không khóa bản ghi. `POST` bàn giao yêu cầu tuyển dụng và phòng ban nhưng không khóa tài khoản. Không API nào ở đây ngăn việc khóa tài khoản: khóa vẫn là thao tác bảo mật khẩn cấp, làm được kể cả khi còn việc chưa bàn giao. Riêng lệnh khóa (hoặc đổi vai trò) của tài khoản cần bàn giao, của một người nhận hay của Admin đang bàn giao, nếu gửi trong lúc một lần `POST` đang chạy, sẽ chờ lần bàn giao đó kết thúc (một transaction ngắn) rồi mới chạy, không bị từ chối ([Khóa và đồng thời](#khóa-và-đồng-thời)).

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /accounts/{id}/handover-items`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL` **và** `REQUISITIONS_READ_ALL`|ADMIN|
|`POST /accounts/{id}/handover`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL` **và** `REQUISITIONS_READ_ALL` **và** `REQUISITIONS_WRITE_ALL`; khi body có `departments`, thêm `ORGANIZATION_WRITE_ALL`|ADMIN|

- Quyền như API khóa tài khoản, thêm `REQUISITIONS_READ_ALL` vì danh sách gồm yêu cầu tuyển dụng của mọi phòng ban. Không thêm mã quyền mới.
- `POST` cần thêm `REQUISITIONS_WRITE_ALL`, quyền [phân công recruiter](requisition-recruiters.md). Nó vẫn cần `REQUISITIONS_READ_ALL`, vì response trả lại danh sách còn lại như `GET`: quyền ghi không bao gồm quyền đọc, như `POST /requisitions/{id}/assign`.
- `POST` có `departments` (không rỗng) cần thêm `ORGANIZATION_WRITE_ALL`, quyền đổi trưởng phòng của `PUT /departments/{id}` ([API phòng ban](departments.md)). `SecurityConfiguration` không đọc body nên không kiểm quyền này; `AccountHandoverService` kiểm sau khi khóa, cùng các quyền khác. Thiếu quyền thì 403 cho cả request, kể cả các mục yêu cầu tuyển dụng đi kèm. Request chỉ có `requisitions` không cần quyền này. Theo seed, ADMIN có quyền này.
- `SecurityConfiguration` kiểm mọi điều kiện của từng API, trừ `ORGANIZATION_WRITE_ALL` ở trên. `AccountHandoverService` kiểm lại hạn token, phiên, tài khoản người gọi còn được truy cập, vai trò và quyền (đọc lại từ DB) ở mỗi request; `POST` kiểm lại sau mỗi lần chờ khóa. Vì vậy, khi quyền bị thu hồi, request kế tiếp với cùng token nhận 403.
- HR_MANAGER (chỉ có `USER_ADMIN_READ_ALL`) nhận 403 ở cả hai API, dù có `REQUISITIONS_WRITE_ALL` và `ORGANIZATION_WRITE_ALL`: HR vẫn đổi recruiter của từng yêu cầu bằng `POST /requisitions/{id}/assign` và đổi trưởng phòng bằng `PUT /departments/{id}`. Cấp thêm `USER_ADMIN_WRITE_ALL` cho HR_MANAGER cũng không đủ, vì còn thiếu vai trò `ADMIN`.

## GET /accounts/{id}/handover-items

`id` là UUID của tài khoản cần bàn giao, thường là người sắp bị khóa. Không có body hay tham số. Trả 200:

```json
{
  "userId": "00000000-0000-0000-0000-000000000011",
  "fullName": "Nguyễn Văn A",
  "status": "ACTIVE",
  "requisitions": [
    {
      "requisitionId": "6f0c1f9e-8f3e-4b67-9a39-2c1d3f4e5a6b",
      "positionCode": "BE-01",
      "positionName": "Lập trình viên Backend",
      "departmentId": "00000000-0000-0000-0000-000000000101",
      "departmentName": "Phòng CNTT",
      "status": "APPROVED",
      "role": "PRIMARY",
      "headcount": 2,
      "neededBy": "2026-12-01",
      "assignedAt": "2026-10-01T03:00:00Z"
    }
  ],
  "managedDepartments": [
    {"departmentId": "00000000-0000-0000-0000-000000000101", "code": "IT", "name": "Phòng CNTT", "active": true}
  ],
  "approvalSteps": [
    {
      "flowId": "00000000-0000-0000-0000-000000000201",
      "departmentId": "00000000-0000-0000-0000-000000000101",
      "departmentName": "Phòng CNTT",
      "flowName": "Luồng duyệt CNTT",
      "version": 3,
      "position": 2
    }
  ],
  "pendingApprovals": [
    {
      "requisitionId": "0b9d6c55-3a43-4f0e-9a8e-6c1f1f5d2e7a",
      "positionCode": "QA-01",
      "positionName": "Kiểm thử phần mềm",
      "departmentId": "00000000-0000-0000-0000-000000000101",
      "departmentName": "Phòng CNTT",
      "round": 1,
      "level": 2,
      "current": true,
      "flowId": "00000000-0000-0000-0000-000000000201",
      "flowVersion": 2,
      "waitingSince": "2026-10-14T02:30:00Z"
    }
  ]
}
```

- `userId`, `fullName`, `status`: tài khoản cần bàn giao. `status` có cùng giá trị như `GET /accounts/{id}`: `ACTIVE`, `TEMPORARILY_LOCKED`, `PENDING_ACTIVATION`, `DISABLED`, `ADMINISTRATIVELY_LOCKED`.
- Bốn mảng **luôn có**, có thể rỗng. Cả bốn rỗng nghĩa là tài khoản không còn việc nào thuộc các loại dưới đây (backend chưa có module phỏng vấn và offer, xem mục `requisitions`).
- Tài khoản đã bị khóa vẫn xem được, để bàn giao nốt phần còn lại sau khi khóa.
- Tên chức danh, phòng ban và luồng duyệt được đọc lúc trả, nên đổi tên thì hiện tên mới.
- Cả bốn danh sách được đọc trong cùng một snapshot (REPEATABLE READ). Nhờ vậy response không trộn dữ liệu trước và sau một lần bàn giao đang commit.

### requisitions

Các yêu cầu tuyển dụng mà tài khoản đang là recruiter chính (`role: PRIMARY`) hoặc recruiter hỗ trợ (`role: SUPPORTING`) theo [phân công recruiter](requisition-recruiters.md).

- Chỉ gồm các trạng thái còn đổi được recruiter: `DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`. Đây cũng là tập trạng thái mà `POST /requisitions/{id}/assign` nhận; backend dùng chung một định nghĩa. Yêu cầu `FILLED`, `CANCELLED`, `REJECTED` không hiện vì không bàn giao được nữa.
- Recruiter chính đứng trước recruiter hỗ trợ. Trong mỗi nhóm, yêu cầu tạo gần nhất đứng trước.
- `assignedAt` là lúc tài khoản nhận vai trò đó. `neededBy` có thể là `null` với bản nháp chưa điền ngày.
- Tin tuyển dụng (và sau này hồ sơ ứng viên) đi theo đội recruiter của yêu cầu (`RecruiterAssignmentAccess`), nên không có mục riêng. Bàn giao yêu cầu cũng chuyển luôn quyền soạn tin của yêu cầu đó. Backend chưa có module phỏng vấn và offer, nên API chưa liệt kê hai loại việc này.

### managedDepartments

Các phòng ban mà tài khoản là trưởng phòng trực tiếp (`managerUserId`) và vẫn còn được dùng, xếp theo mã:

- mọi phòng ban **đang hoạt động** (`active: true`);
- phòng ban **đã ngừng áp dụng** (`active: false`) nhưng vẫn còn yêu cầu tuyển dụng chưa đóng của chính nó (`DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`, cùng tập trạng thái chặn xóa phòng ban). Ngừng áp dụng phòng ban không đóng các yêu cầu này: trưởng phòng vẫn xem chúng theo phạm vi (`REQUISITIONS_*_SCOPED`) và, nếu có vai trò `HIRING_MANAGER` hoặc `RECRUITER`, vẫn quyết định các cấp duyệt theo vai trò đó của chúng.

Phòng ban đã ngừng áp dụng mà không còn yêu cầu chưa đóng **của chính nó** thì không hiện. Thường thì không còn việc gì để bàn giao. Ngoại lệ là phòng ban cha đã ngừng áp dụng nhưng còn phòng ban con cháu đang hoạt động hoặc có yêu cầu chưa đóng: trưởng phòng của nó vẫn giữ phạm vi trên các phòng ban đó (xem dưới). Danh sách này không hiện phòng ban cha như vậy, và `POST /accounts/{id}/handover` trả 409 cho nó; muốn chuyển phạm vi đó, đổi trưởng phòng tại `PUT /departments/{id}`.

- Không gồm phòng ban con: mỗi phòng ban con có trưởng phòng riêng. Quyền xem yêu cầu theo phạm vi (`REQUISITIONS_*_SCOPED`) của trưởng phòng vẫn tính cả cây con, nên đổi trưởng phòng của phòng ban cha cũng chuyển phạm vi của các phòng ban con.
- Bàn giao bằng `departments` của [`POST /accounts/{id}/handover`](#kết-quả-của-từng-phòng-ban): chỉ các phòng ban có trong danh sách này, kể cả phòng ban đã ngừng áp dụng còn yêu cầu chưa đóng. Hoặc đổi trưởng phòng tại `PUT /departments/{id}` ([API phòng ban](departments.md)): làm được với mọi phòng ban, kể cả phòng ban đã ngừng áp dụng.

### approvalSteps

Các cấp duyệt thuộc **phiên bản hiện hành** của [cấu hình luồng phê duyệt](approval-flows.md) của một phòng ban **đang hoạt động**, chỉ định đích danh tài khoản (`approverUserId`). Đây là các cấp mà những lần gửi duyệt **sau này** sẽ dùng. Danh sách xếp theo tên phòng ban, rồi theo cấp (`position`).

- Cấp duyệt theo vai trò (`approverRole`) không chỉ định ai nên không tính.
- Phiên bản cũ không tính ở đây: chúng chỉ còn dùng cho các lượt duyệt đã gửi theo chúng, và các lượt còn chờ tài khoản này nằm ở `pendingApprovals`.
- Luồng của phòng ban đã ngừng áp dụng không tính: phòng ban đó không nhận gửi duyệt mới, và luồng của nó không lưu phiên bản mới được (400 `departmentId`).
- Phiên bản đã lưu là bất biến. Lưu phiên bản mới bằng `PUT /approval-flows/{id}` chỉ đổi người duyệt của các lần gửi duyệt sau đó; các lượt đang chờ vẫn theo phiên bản cũ ([bản chụp chuỗi duyệt](requisition-approvals.md#bản-chụp-chuỗi-duyệt)). Vì vậy hộp thoại khóa chỉ hiện các bước này dưới dạng cảnh báo.

### pendingApprovals

Các cấp duyệt **chưa quyết định** của những yêu cầu đang chờ duyệt (`PENDING_APPROVAL`) mà lượt duyệt hiện tại chỉ định đích danh tài khoản. Mỗi lượt duyệt giữ phiên bản cấu hình lúc gửi, nên lưu phiên bản mới không đổi người duyệt của các lượt này, kể cả khi `approvalSteps` đã không còn tên người này. Cấp chỉ định đích danh chỉ người đó quyết định được; HR_MANAGER và ADMIN không duyệt thay ([ai được duyệt một cấp](requisition-approvals.md#ai-được-duyệt-một-cấp)). Khóa tài khoản khi còn mục ở đây thì lượt duyệt đó dừng ở cấp này, không ai quyết định được.

- Gồm cấp đang chờ (`current: true`) và các cấp sau nó trong cùng lượt (`current: false`, một cấp trước phải quyết định trước). Chỉ tính các cấp 1 đến số cấp áp dụng của lượt: cấp chỉ áp dụng trên một ngưỡng lương mà lượt không tới thì không hiện.
- Cấp đã quyết định, lượt đã kết thúc (duyệt xong, từ chối, yêu cầu bổ sung, hủy) và lượt cũ của một yêu cầu đã gửi lại không tính.
- `round` là số lượt, `level` là cấp, `flowId` và `flowVersion` là phiên bản cấu hình của lượt. `waitingSince` là thời điểm của sự kiện mới nhất trong lượt: từ lúc đó lượt chờ cấp hiện tại.
- Chờ lâu nhất đứng trước, như [hộp chờ duyệt](requisition-approvals.md#hộp-chờ-duyệt); cùng yêu cầu thì cấp nhỏ đứng trước.
- Cấp duyệt theo vai trò không tính, như `approvalSteps`.
- Backend chưa có cách đổi người duyệt hay duyệt thay cho một lượt đang chờ; việc đó cần PO quyết định riêng. Hộp thoại khóa chỉ hiện các mục này dưới dạng cảnh báo.

### Lỗi của GET

Thứ tự kiểm: xác thực (401), quyền (403), rồi mới tới tài khoản đích (404). Nhờ vậy, người không có quyền không biết được một id có tồn tại hay không.

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|`VALIDATION_ERROR`|`id` không phải UUID|
|401|`UNAUTHORIZED`|Bộ lọc Bearer từ chối: thiếu, sai hoặc hết hạn token; phiên bị thu hồi; tài khoản người gọi bị khóa. Có `WWW-Authenticate: Bearer` và `Cache-Control: no-store`|
|401|`SESSION_INVALID`|Request đã qua bộ lọc nhưng service kiểm lại thấy token vừa hết hạn. Lỗi này đi qua `ApiExceptionHandler` nên **không** có `WWW-Authenticate`, giống các API khác ([ma trận quyền](../architecture/role-permission-matrix.md)); `Cache-Control` vẫn chứa `no-store`|
|403|`FORBIDDEN`|Thiếu vai trò `ADMIN`, `USER_ADMIN_WRITE_ALL` hoặc `REQUISITIONS_READ_ALL`|
|404|`ACCOUNT_NOT_FOUND`|Tài khoản cần bàn giao không tồn tại|

## POST /accounts/{id}/handover

Giao từng yêu cầu tuyển dụng mà tài khoản `id` đang phụ trách cho một recruiter khác, và từng phòng ban mà tài khoản đang làm trưởng phòng cho một trưởng phòng mới. Mọi mục của một request được bàn giao **cùng lúc hoặc không mục nào** (một transaction). API không khóa tài khoản: hộp thoại khóa gọi API này trước, rồi mới gọi `PUT /accounts/{id}/lock` như cũ. Tài khoản đã bị khóa vẫn bàn giao được, để bàn giao nốt phần còn lại sau khi khóa.

```json
{
  "note": "Nghỉ việc từ 20/10",
  "requisitions": [
    {"requisitionId": "6f0c1f9e-8f3e-4b67-9a39-2c1d3f4e5a6b", "toUserId": "00000000-0000-0000-0000-000000000012"},
    {"requisitionId": "0b9d6c55-3a43-4f0e-9a8e-6c1f1f5d2e7a", "toUserId": "00000000-0000-0000-0000-000000000013"}
  ],
  "departments": [
    {"departmentId": "00000000-0000-0000-0000-000000000101", "toUserId": "00000000-0000-0000-0000-000000000014"}
  ]
}
```

| Trường | Quy tắc |
|---|---|
|note|Không bắt buộc. Tối đa 1.000 ký tự, không chứa ký tự NUL; trống thì lưu `null`. Một ghi chú cho cả request, được lưu vào **mỗi** thay đổi trong [lịch sử phân công](requisition-recruiters.md#lịch-sử-phân-công-task-286), như `note` của `POST /requisitions/{id}/assign`|
|requisitions|Không bắt buộc, tối đa 100 mục. Mỗi yêu cầu tuyển dụng chỉ xuất hiện một lần. `requisitions` và `departments` không được cùng thiếu hoặc cùng rỗng|
|requisitions[i].requisitionId|Bắt buộc. UUID của yêu cầu tuyển dụng, thường lấy từ `requisitions` của `GET /accounts/{id}/handover-items`|
|requisitions[i].toUserId|Bắt buộc. UUID tài khoản nhận, phải khác `id`. Nhiều mục có thể cùng một người nhận|
|departments|Không bắt buộc, tối đa 100 mục. Mỗi phòng ban chỉ xuất hiện một lần. Có mục nào thì cần thêm `ORGANIZATION_WRITE_ALL`|
|departments[i].departmentId|Bắt buộc. UUID của phòng ban, thường lấy từ `managedDepartments` của `GET /accounts/{id}/handover-items`|
|departments[i].toUserId|Bắt buộc. UUID trưởng phòng mới, phải khác `id`. Nhiều phòng ban có thể cùng một người nhận, kể cả một người nhận yêu cầu tuyển dụng|

- Client không gửi vai trò: server bàn giao đúng vai trò mà tài khoản đang giữ ở từng yêu cầu.
- Trường khác trả 400 `INVALID_JSON`, ví dụ `role` trong một mục yêu cầu tuyển dụng hoặc `code` trong một mục phòng ban. `requisitionId`, `departmentId` hoặc `toUserId` không phải UUID cũng là `INVALID_JSON`.

### Kết quả của từng yêu cầu tuyển dụng

| Tài khoản `id` đang là | Người nhận | Kết quả | Lịch sử ghi thêm |
|---|---|---|---|
|Recruiter chính|Chưa ở trong nhóm|Người nhận thành recruiter chính, tài khoản rời yêu cầu|`PRIMARY_HANDED_OVER`, `previousAssignedTo` là tài khoản|
|Recruiter chính|Recruiter hỗ trợ|Người nhận được nâng lên recruiter chính, tài khoản rời yêu cầu|`PRIMARY_HANDED_OVER`|
|Recruiter hỗ trợ|Chưa ở trong nhóm|Tài khoản rời nhóm, người nhận thành recruiter hỗ trợ|`SUPPORTING_REMOVED`, rồi `SUPPORTING_ADDED`|
|Recruiter hỗ trợ|Đã ở trong nhóm (chính hoặc hỗ trợ)|Tài khoản rời nhóm; người nhận giữ vai trò đang có|`SUPPORTING_REMOVED`|
|Không có trong nhóm|Bất kỳ|409 `HANDOVER_ITEM_NOT_HELD`|Không ghi|

- Tài khoản rời nhóm trước khi người nhận vào, nên nhóm đã đủ 10 recruiter hỗ trợ vẫn nhận được người mới.
- Người nhận một vai trò mới phải hợp lệ như khi [phân công](requisition-recruiters.md#ai-được-phân-công): có vai trò RECRUITER, không bị quản trị khóa, đã kích hoạt. Người bị khóa tạm vì nhập sai mật khẩu vẫn nhận được. Người đã ở trong nhóm và giữ nguyên vai trò thì không bị kiểm lại.
- Yêu cầu phải ở trạng thái còn đổi được recruiter (`DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`), như `POST /requisitions/{id}/assign`.
- Mọi thay đổi đi qua đúng các quy tắc của phân công recruiter và được ghi vào lịch sử (V17), mỗi thay đổi một dòng: người thực hiện là Admin gọi API, ghi chú là `note` của request. Mỗi dòng mang thời điểm của chính thay đổi đó, nên các dòng của một request (kể cả `SUPPORTING_REMOVED` và `SUPPORTING_ADDED` của cùng một mục) có thể lệch nhau rất ít; thứ tự đúng là `revision`. Không có bảng lịch sử bàn giao riêng; `GET /requisitions/{id}/assignment-history` cho thấy từng lần bàn giao.
- Quyền xem yêu cầu và soạn tin tuyển dụng đi theo nhóm hiện tại ([task 285](requisition-recruiters.md#recruiter-xem-yêu-cầu-được-phân-công-task-285)), nên chuyển sang người nhận ngay khi request commit.

### Kết quả của từng phòng ban

- Tài khoản `id` phải còn giữ phòng ban đúng như `managedDepartments` của `GET`: là trưởng phòng trực tiếp, và phòng ban đang hoạt động hoặc đã ngừng áp dụng nhưng còn yêu cầu tuyển dụng chưa đóng của chính nó. Không thì 409 `HANDOVER_ITEM_NOT_HELD`, ví dụ khi HR vừa đổi trưởng phòng bằng `PUT /departments/{id}`, khi phòng ban đã ngừng áp dụng và không còn yêu cầu chưa đóng, hoặc khi phòng ban không tồn tại. Phòng ban cha đã ngừng áp dụng mà chỉ phòng ban con cháu còn được dùng cũng trả 409 (xem [managedDepartments](#manageddepartments)); đổi trưởng phòng của nó tại `PUT /departments/{id}`.
- Người nhận trở thành trưởng phòng (`managerUserId`). Chỉ trường này đổi: mã, tên, phòng ban cha và trạng thái giữ nguyên. Phòng ban con giữ trưởng phòng riêng. Người nhận không vì thế mà đổi phòng ban (`departmentId` của tài khoản) hay vai trò, như `PUT /departments/{id}`.
- Người nhận theo quy tắc trưởng phòng mới của [API phòng ban](departments.md): tài khoản tồn tại, đã kích hoạt và không bị quản trị khóa. Người bị khóa tạm vì nhập sai mật khẩu vẫn nhận được. Không bắt buộc vai trò nào. Sai thì 400 `INVALID_DEPARTMENT_MANAGER` với cùng thông báo như API phòng ban.
- Đổi trưởng phòng không có lịch sử, như `PUT /departments/{id}`. `note` chỉ được lưu vào lịch sử phân công recruiter của các mục `requisitions`.

**Bàn giao phòng ban cũng chuyển phạm vi dữ liệu của trưởng phòng.** Ai làm trưởng phòng (`departments.manager_user_id`) quyết định ai xem được dữ liệu của phòng ban theo phạm vi, tính cả mọi phòng ban con cháu. Ngay khi request commit, phạm vi này chuyển từ tài khoản `id` sang người nhận. Bàn giao không cấp vai trò hay quyền nào: người nhận chỉ dùng được phạm vi này với quyền và vai trò mình đã có:

- xem, sửa yêu cầu tuyển dụng của phòng ban và các phòng ban con cháu, khi có quyền `REQUISITIONS_*_SCOPED` (theo seed: RECRUITER, HIRING_MANAGER, APPROVER), kể cả bản nháp do người khác tạo, và chọn các phòng ban đó trên form ([phạm vi dữ liệu](requisitions.md#phạm-vi-dữ-liệu-task-245-249-285));
- danh sách `GET /requisitions`, `GET /requisitions/{id}/headcount-overrides` và `GET /headcount-plans/remaining` của các phòng ban đó, với cùng quyền;
- duyệt các cấp chỉ định vai trò `HIRING_MANAGER` hoặc `RECRUITER` của các yêu cầu thuộc các phòng ban đó, kể cả lượt duyệt đang chờ, khi người nhận có đúng vai trò mà cấp đó chỉ định (cùng quyền đọc và ghi yêu cầu tuyển dụng): lượt duyệt giữ phiên bản cấu hình lúc gửi, không giữ người trưởng phòng ([ai được duyệt một cấp](requisition-approvals.md#ai-được-duyệt-một-cấp)).

API nhận người nhận có vai trò bất kỳ, như `PUT /departments/{id}`. Người nhận không có các quyền và vai trò trên (ví dụ chỉ có vai trò INTERVIEWER) vẫn thành trưởng phòng nhưng không xem được yêu cầu tuyển dụng theo phạm vi và không duyệt được các cấp theo vai trò. Khi đó các cấp theo vai trò `HIRING_MANAGER` hoặc `RECRUITER` của các yêu cầu đó, kể cả lượt đang chờ, chỉ còn người có vai trò đó làm trưởng phòng của một phòng ban cấp trên quyết định được; HR_MANAGER và ADMIN không duyệt thay. Không có ai như vậy thì lượt duyệt dừng ở cấp đó.

Tài khoản `id` mất phạm vi này ngay, trừ khi vẫn là trưởng phòng của một phòng ban cấp trên. Tin tuyển dụng không đổi theo trưởng phòng: chúng đi theo nhóm recruiter của yêu cầu. Khi có module ứng viên dùng phạm vi trưởng phòng (`CANDIDATES_*_SCOPED` của HIRING_MANAGER), quyền xem ứng viên của các phòng ban đó cũng chuyển theo cùng cách. Vì vậy hộp thoại nên nhắc Admin điều này trước khi gửi.

### Response

200, `Cache-Control: no-store`:

```json
{
  "transferred": 3,
  "remaining": {
    "userId": "00000000-0000-0000-0000-000000000011",
    "fullName": "Nguyễn Văn A",
    "status": "ACTIVE",
    "requisitions": [],
    "managedDepartments": [],
    "approvalSteps": [],
    "pendingApprovals": []
  }
}
```

- `transferred`: số mục đã bàn giao. Vì bàn giao là tất cả hoặc không, số này luôn bằng tổng số mục của `requisitions` và `departments` trong request (ví dụ trên: 2 yêu cầu tuyển dụng và 1 phòng ban).
- `remaining`: những việc tài khoản còn giữ sau lần bàn giao, cùng dạng với `GET /accounts/{id}/handover-items`. Hộp thoại dùng nó để hiện phần còn lại mà không cần gọi lại `GET`.

### Thứ tự kiểm

1. Quyền ở `SecurityConfiguration` (401/403). `id` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors`. Các lỗi này trả về **trước khi khóa bất cứ bản ghi nào**.
3. Khóa tài khoản của người gọi, tài khoản `id` và mọi người nhận (của cả hai danh sách, theo thứ tự id), rồi phiên của người gọi. Kiểm lại hạn token, phiên, tài khoản người gọi còn được truy cập, vai trò `ADMIN` và ba quyền đọc lại từ DB, thêm `ORGANIZATION_WRITE_ALL` khi có `departments` (401 `SESSION_INVALID` / 403).
4. Tài khoản `id` tồn tại (404 `ACCOUNT_NOT_FOUND`).
5. Có `departments`: lấy khóa cây phòng ban (advisory lock chung của mọi lệnh ghi phòng ban), như `POST`, `PUT`, `DELETE /departments`.
6. Có `requisitions`: khóa mọi yêu cầu tuyển dụng của request trong một câu lệnh, theo thứ tự id, rồi kiểm lại người gọi như bước 3 (việc chờ khóa có thể kéo dài).
7. Từng yêu cầu tuyển dụng, theo thứ tự trong request: yêu cầu tồn tại (409 `HANDOVER_ITEM_NOT_HELD`), trạng thái cho phép đổi recruiter (409 `REQUISITION_NOT_ASSIGNABLE`), tài khoản có trong nhóm (409 `HANDOVER_ITEM_NOT_HELD`), người nhận hợp lệ (400).
8. Có `departments`: khóa các dòng phòng ban của request theo thứ tự id, kiểm lại người gọi như bước 3, rồi từng phòng ban theo thứ tự trong request: tài khoản còn giữ phòng ban (409 `HANDOVER_ITEM_NOT_HELD`), người nhận hợp lệ (400 `INVALID_DEPARTMENT_MANAGER`).

Mục lỗi đầu tiên (các yêu cầu tuyển dụng được xử lý trước các phòng ban) làm hủy cả request, kể cả các mục đã xử lý trước nó.

### Khóa và đồng thời

- Thứ tự khóa (tài khoản, phiên, yêu cầu tuyển dụng) giống `POST /requisitions/{id}/assign`. Một lần phân công và một lần bàn giao cùng yêu cầu chạy lần lượt, và lệnh sau thấy kết quả của lệnh trước. Ví dụ: HR vừa chuyển yêu cầu sang người khác thì lần bàn giao chạy sau trả 409 `HANDOVER_ITEM_NOT_HELD`.
- Hai lần bàn giao có chung yêu cầu đều khóa các yêu cầu theo thứ tự id, nên không deadlock, kể cả khi hai request liệt kê các yêu cầu theo thứ tự khác nhau.
- Tài khoản người nhận được khóa trong suốt lần bàn giao. Nếu một Admin khác khóa người nhận hoặc gỡ vai trò RECRUITER của họ trong lúc lần bàn giao đang chờ, lần bàn giao thấy thay đổi đó và trả 400. Nếu lần bàn giao chạy trước, người nhận vẫn được giao rồi mới bị khóa; `GET /requisitions/{id}/assignment` khi đó hiện `eligible: false`, như mọi recruiter bị khóa sau khi được phân công.
- Bàn giao có `departments` lấy khóa cây phòng ban trước mọi dòng yêu cầu tuyển dụng hay phòng ban, như `DepartmentService`. Một lần sửa, tạo hay xóa phòng ban và một lần bàn giao phòng ban chạy lần lượt, và lệnh sau thấy kết quả của lệnh trước. Ví dụ: HR vừa đổi trưởng phòng bằng `PUT /departments/{id}` thì lần bàn giao chạy sau trả 409 `HANDOVER_ITEM_NOT_HELD`; lần bàn giao chạy trước thì `PUT` chạy sau vẫn ghi trưởng phòng theo body của nó.
- Các dòng phòng ban được khóa sau các dòng yêu cầu tuyển dụng. Đây là thứ tự của lưu yêu cầu tuyển dụng và quyết định duyệt (khóa dòng yêu cầu, rồi đọc phòng ban `FOR SHARE`), nên chúng không deadlock với lần bàn giao. Khi một yêu cầu của phòng ban đang được lưu hoặc một tài khoản đang được gán vào phòng ban, lần bàn giao chờ việc đó xong rồi kiểm lại người gọi.
- Tài khoản `id` và tài khoản Admin đang bàn giao cũng được khóa trong suốt lần bàn giao. `PUT /accounts/{id}/lock` gửi lúc đó chờ lần bàn giao commit (hoặc hủy) rồi mới khóa, không bị từ chối. Bàn giao làm được cả với tài khoản đã khóa, nên khóa `id` trước hay sau lần bàn giao đều cho cùng kết quả.

### Lỗi của POST

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|`VALIDATION_ERROR`|`fieldErrors.requisitions`: `requisitions` và `departments` cùng thiếu hoặc cùng rỗng ("Vui lòng chọn ít nhất một công việc để bàn giao."), hoặc hơn 100 yêu cầu tuyển dụng. `fieldErrors["requisitions[i]"]`: mục `null`. `fieldErrors["requisitions[i].requisitionId"]`: thiếu, hoặc trùng một mục trước đó ("Mỗi yêu cầu tuyển dụng chỉ được bàn giao một lần."). `fieldErrors["requisitions[i].toUserId"]`: thiếu, hoặc bằng `id` ("Người nhận phải khác tài khoản đang bàn giao."). `fieldErrors.departments`: hơn 100 phòng ban. `fieldErrors["departments[i]"]`: mục `null`. `fieldErrors["departments[i].departmentId"]`: thiếu, hoặc trùng một mục trước đó ("Mỗi phòng ban chỉ được bàn giao một lần."). `fieldErrors["departments[i].toUserId"]`: thiếu, hoặc bằng `id`. `fieldErrors.note`: quá 1.000 ký tự hoặc chứa ký tự NUL. Ngoài ra `id` không phải UUID|
|400|`INVALID_JSON`|JSON sai, thiếu body, trường không được phép, id trong body không phải UUID|
|400|`INVALID_REQUISITION_RECRUITER`|Người nhận không tồn tại hoặc không có vai trò RECRUITER; `fieldErrors["requisitions[i].toUserId"]`|
|400|`REQUISITION_RECRUITER_INACTIVE`|Người nhận bị quản trị khóa hoặc chưa kích hoạt; `fieldErrors["requisitions[i].toUserId"]`|
|400|`INVALID_DEPARTMENT_MANAGER`|Trưởng phòng mới không tồn tại, chưa kích hoạt hoặc bị quản trị khóa; `fieldErrors["departments[i].toUserId"]`|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Như `GET`, kể cả khi token hết hạn, phiên bị thu hồi hoặc người gọi bị khóa trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu vai trò `ADMIN`, `USER_ADMIN_WRITE_ALL`, `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_WRITE_ALL`, hoặc thiếu `ORGANIZATION_WRITE_ALL` khi có `departments`, kể cả khi bị gỡ trong lúc chờ khóa|
|404|`ACCOUNT_NOT_FOUND`|Tài khoản `id` không tồn tại|
|409|`HANDOVER_ITEM_NOT_HELD`|"Công việc này không còn thuộc tài khoản cần bàn giao. Vui lòng tải lại danh sách." Yêu cầu không tồn tại, hoặc tài khoản không còn trong nhóm recruiter của nó; `fieldErrors["requisitions[i].requisitionId"]`. Phòng ban không tồn tại, tài khoản không còn là trưởng phòng, hoặc phòng ban đã ngừng áp dụng và không còn yêu cầu chưa đóng của chính nó; `fieldErrors["departments[i].departmentId"]`|
|409|`REQUISITION_NOT_ASSIGNABLE`|Yêu cầu bị từ chối, đã tuyển đủ hoặc đã hủy; `fieldErrors["requisitions[i].requisitionId"]`|

- `i` là vị trí của mục trong `requisitions` hoặc `departments` của request, bắt đầu từ 0. Mỗi lỗi chỉ nêu mục lỗi đầu tiên.

## Ghi chú tích hợp frontend

- Khi mở, hộp thoại khóa gọi `GET /accounts/{id}/handover-items` và hiện bốn nhóm: "Yêu cầu tuyển dụng đang phụ trách", "Phòng ban đang làm trưởng phòng", "Bước phê duyệt được chỉ định đích danh" và "Yêu cầu đang chờ người này duyệt".
- Phòng ban có `active: false` nên ghi rõ "đã ngừng áp dụng" để Admin biết vì sao nó vẫn cần bàn giao.
- Nhóm "Yêu cầu đang chờ người này duyệt" chỉ là cảnh báo. Không nên gợi ý lưu phiên bản mới của luồng phê duyệt cho nhóm này, vì phiên bản mới không đổi được lượt đang chờ.
- Mỗi yêu cầu tuyển dụng có một ô chọn người nhận. Danh sách lấy từ `GET /accounts?role=RECRUITER&size=100` (đọc hết các trang), giữ người có `status` là `ACTIVE` hoặc `TEMPORARILY_LOCKED`, bỏ chính tài khoản đang bàn giao.
- Mỗi phòng ban có một ô chọn trưởng phòng mới. Danh sách lấy từ `GET /accounts?size=100` (đọc hết các trang), cùng điều kiện `status` như trên, bỏ chính tài khoản đang bàn giao; nên đưa người có vai trò HIRING_MANAGER lên đầu. Ghi rõ cạnh nhóm này rằng trưởng phòng mới nhận phạm vi dữ liệu của phòng ban và các phòng ban con ([phạm vi dữ liệu](#kết-quả-của-từng-phòng-ban)). Khi người được chọn không có vai trò HIRING_MANAGER (`roles` của `GET /accounts`), hiện cảnh báo cạnh dòng đó: người này không duyệt được các cấp theo vai trò HIRING_MANAGER của phòng ban, và chỉ xem được yêu cầu tuyển dụng của phòng ban nếu vai trò của họ có quyền `REQUISITIONS_*_SCOPED`.
- Khi bấm khóa: nếu có dòng đã chọn người nhận, gửi `POST /accounts/{id}/handover` với đúng các dòng đó (yêu cầu tuyển dụng trong `requisitions`, phòng ban trong `departments`; ghi chú trống thì gửi `null`), rồi mới gọi `PUT /accounts/{id}/lock`. Bàn giao lỗi thì chưa khóa. Khóa lỗi sau khi bàn giao thành công thì các việc đã bàn giao vẫn giữ nguyên; hiện `remaining` cho Admin.
- 409 `HANDOVER_ITEM_NOT_HELD` và 409 `REQUISITION_NOT_ASSIGNABLE` đều nghĩa là danh sách đang hiển thị đã cũ: yêu cầu hoặc phòng ban đã sang người khác, hoặc yêu cầu đã đổi sang trạng thái không bàn giao được (danh sách chỉ gồm trạng thái còn bàn giao được). Xử lý hai mã như nhau: tải lại `GET /accounts/{id}/handover-items` và báo danh sách công việc đã thay đổi.
- Lỗi có `fieldErrors["requisitions[i]..."]` hoặc `fieldErrors["departments[i]..."]` thì hiện ở dòng thứ `i` của danh sách tương ứng trong request vừa gửi (không phải dòng thứ `i` của danh sách đang hiển thị, nếu có dòng chưa chọn người nhận).
- Mỗi request tối đa 100 yêu cầu tuyển dụng và 100 phòng ban, còn `GET` trả mọi việc tài khoản đang giữ. Khi có hơn 100 dòng đã chọn người nhận trong một nhóm (ví dụ "giao tất cả cho một người"), gửi lần lượt nhiều request, mỗi request tối đa 100 mục. Mỗi request là "tất cả hoặc không" của riêng nó: một request lỗi không hoàn tác các request trước đã thành công. Giữa hai request, cập nhật danh sách theo `remaining`; chỉ gọi `PUT /accounts/{id}/lock` khi mọi request đã thành công.
- Admin không có `ORGANIZATION_WRITE_ALL` trong `GET /auth/permissions` thì ẩn ô chọn của nhóm phòng ban và chỉ gửi `requisitions`: gửi `departments` sẽ nhận 403 cho cả request.
- Bước duyệt và lượt duyệt đang chờ chưa bàn giao được qua API này.
- `handoverWarning` của API khóa chưa đổi: vẫn là lời nhắc chung, trả về mỗi lần khóa.
- Không có migration: dữ liệu lấy từ bảng phân công recruiter (V16), phòng ban (V5), luồng phê duyệt (V18) và lịch sử duyệt (V19); bàn giao ghi vào V16, lịch sử phân công V17 và cột `manager_user_id` của phòng ban (V5).
