# API công việc cần bàn giao khi khóa tài khoản

Góp ý số 10 "Khóa tài khoản: danh sách bàn giao công việc", phần 1: xem tài khoản còn phụ trách những việc gì. Trước khi Admin [khóa một tài khoản](account-locking.md), hộp thoại khóa liệt kê các việc này để giao từng việc cho đúng người nhận. URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi dùng `Cache-Control: no-store`, trừ 401 `SESSION_INVALID` khi service kiểm lại phiên (xem bảng lỗi).

API này chỉ đọc. Nó không bàn giao, không khóa bản ghi và không chặn API khóa tài khoản: khóa vẫn là thao tác bảo mật khẩn cấp, làm được kể cả khi còn việc chưa bàn giao.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /accounts/{id}/handover-items`|Vai trò `ADMIN` **và** `USER_ADMIN_WRITE_ALL` **và** `REQUISITIONS_READ_ALL`|ADMIN|

- Quyền như API khóa tài khoản, thêm `REQUISITIONS_READ_ALL` vì danh sách gồm yêu cầu tuyển dụng của mọi phòng ban. Không thêm mã quyền mới.
- `SecurityConfiguration` kiểm cả ba điều kiện. `AccountHandoverService` kiểm lại hạn token, phiên, tài khoản người gọi còn được truy cập, vai trò và quyền (đọc lại từ DB) ở mỗi request. Vì vậy, khi quyền bị thu hồi, request kế tiếp với cùng token nhận 403.
- HR_MANAGER (chỉ có `USER_ADMIN_READ_ALL`) nhận 403. Cấp thêm `USER_ADMIN_WRITE_ALL` cho HR_MANAGER cũng không đủ, vì còn thiếu vai trò `ADMIN`.

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
- phòng ban **đã ngừng áp dụng** (`active: false`) nhưng vẫn còn yêu cầu tuyển dụng chưa đóng của chính nó (`DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`, cùng tập trạng thái chặn xóa phòng ban). Ngừng áp dụng phòng ban không đóng các yêu cầu này: trưởng phòng vẫn xem chúng theo phạm vi (`REQUISITIONS_*_SCOPED`) và vẫn là người quyết định các cấp duyệt theo vai trò `HIRING_MANAGER`, `RECRUITER` của chúng.

Phòng ban đã ngừng áp dụng mà không còn yêu cầu chưa đóng thì không hiện, vì không còn việc gì để bàn giao.

- Không gồm phòng ban con: mỗi phòng ban con có trưởng phòng riêng. Quyền xem yêu cầu theo phạm vi (`REQUISITIONS_*_SCOPED`) của trưởng phòng vẫn tính cả cây con, nên đổi trưởng phòng của phòng ban cha cũng chuyển phạm vi của các phòng ban con.
- Hiện đổi trưởng phòng tại `PUT /departments/{id}` ([API phòng ban](departments.md)); API này đổi được trưởng phòng cả khi phòng ban đã ngừng áp dụng.

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

## Lỗi

Thứ tự kiểm: xác thực (401), quyền (403), rồi mới tới tài khoản đích (404). Nhờ vậy, người không có quyền không biết được một id có tồn tại hay không.

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|`VALIDATION_ERROR`|`id` không phải UUID|
|401|`UNAUTHORIZED`|Bộ lọc Bearer từ chối: thiếu, sai hoặc hết hạn token; phiên bị thu hồi; tài khoản người gọi bị khóa. Có `WWW-Authenticate: Bearer` và `Cache-Control: no-store`|
|401|`SESSION_INVALID`|Request đã qua bộ lọc nhưng service kiểm lại thấy token vừa hết hạn. Lỗi này đi qua `ApiExceptionHandler` nên **không** có `Cache-Control: no-store` và `WWW-Authenticate`, giống các API khác ([ma trận quyền](../architecture/role-permission-matrix.md))|
|403|`FORBIDDEN`|Thiếu vai trò `ADMIN`, `USER_ADMIN_WRITE_ALL` hoặc `REQUISITIONS_READ_ALL`|
|404|`ACCOUNT_NOT_FOUND`|Tài khoản cần bàn giao không tồn tại|

## Ghi chú tích hợp frontend

- Khi mở, hộp thoại khóa gọi API này và hiện bốn nhóm: "Yêu cầu tuyển dụng đang phụ trách", "Phòng ban đang làm trưởng phòng", "Bước phê duyệt được chỉ định đích danh" và "Yêu cầu đang chờ người này duyệt".
- Phòng ban có `active: false` nên ghi rõ "đã ngừng áp dụng" để Admin biết vì sao nó vẫn cần bàn giao.
- Nhóm "Yêu cầu đang chờ người này duyệt" chỉ là cảnh báo. Không nên gợi ý lưu phiên bản mới của luồng phê duyệt cho nhóm này, vì phiên bản mới không đổi được lượt đang chờ.
- `handoverWarning` của API khóa chưa đổi: vẫn là lời nhắc chung, trả về mỗi lần khóa.
- Không có migration: dữ liệu lấy từ bảng phân công recruiter (V16), phòng ban (V5), luồng phê duyệt (V18) và lịch sử duyệt (V19).
