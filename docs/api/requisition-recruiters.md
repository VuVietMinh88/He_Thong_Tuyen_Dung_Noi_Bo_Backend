# API phân công recruiter cho yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-283 (lưu recruiter chính và hỗ trợ), TKNHTTDNB1-284 (API phân công và bàn giao), TKNHTTDNB1-286 (lịch sử thay đổi người tuyển dụng) và TKNHTTDNB1-285 (giới hạn truy cập theo vị trí được phân công), story TKNHTTDNB1-35 (S3-06). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`.

Tiêu chí của story:

- "Phân công một recruiter chính và nhiều recruiter hỗ trợ": đã có (283, 284).
- "Có ghi lịch sử chuyển giao khi đổi người phụ trách": đã có (286, mục "Lịch sử phân công").
- "Recruiter chỉ nhìn thấy ứng viên của vị trí được giao": phần yêu cầu tuyển dụng đã có (285: recruiter đang được phân công xem được yêu cầu, mất quyền ngay khi bàn giao, mục "Recruiter xem yêu cầu được phân công"); phần hồ sơ ứng viên: quy tắc đọc đã có (363, dùng cùng `RecruiterAssignmentAccess`), API hồ sơ có từ task 361 (chỉ trả các lần ứng tuyển trong phạm vi), API CV chờ task 362. Tiêu chí này vì vậy chưa đạt đủ.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /requisitions/{id}/assignment`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`, cùng phạm vi như `GET /requisitions/{id}` (từ task 262 gồm cả người tham gia duyệt, từ task 285 cả recruiter đang được phân công)|ADMIN, HR_MANAGER (mọi yêu cầu); HIRING_MANAGER, RECRUITER, APPROVER (yêu cầu của phòng ban mình phụ trách, yêu cầu mình đang được phân công, hoặc khi tham gia duyệt)|
|`POST /requisitions/{id}/assign`|`REQUISITIONS_WRITE_ALL`, **và** xem được yêu cầu đó theo phạm vi phòng ban (như `GET /requisitions/{id}`, không gồm người tham gia duyệt và recruiter được phân công)|HR_MANAGER, ADMIN|
|`POST /requisitions/{id}/unassign`|Như `POST /assign`|HR_MANAGER, ADMIN|
|`GET /requisitions/{id}/assignment-history`|Như `GET /assignment`|Như `GET /assignment` (yêu cầu của phòng ban mình phụ trách, yêu cầu mình đang được phân công, hoặc khi tham gia duyệt)|

- Chỉ người có `REQUISITIONS_WRITE_ALL` được phân công. Trưởng bộ phận (`REQUISITIONS_WRITE_SCOPED`) bị 403, vì bảng vai trò ghi việc "phân công recruiter" thuộc về Trưởng phòng Nhân sự. Không thêm mã quyền mới.
- Quyền ghi không bao gồm quyền đọc. `/assign` và `/unassign` trả về cả nhóm, kể cả khi không đổi gì, nên người gọi còn phải xem được yêu cầu đó theo phạm vi phòng ban của `GET /requisitions/{id}` (người tham gia duyệt và recruiter được phân công không được tính, task 262, 285), giống sao chép yêu cầu (task 278). Người chỉ có quyền ghi bị 403, kể cả với `{id}` không tồn tại. Theo seed hiện tại HR_MANAGER và ADMIN có cả `REQUISITIONS_READ_ALL`, nên điều này chưa chặn ai.
- Từ task 285, được phân công (chính hoặc hỗ trợ) cho recruiter **xem** yêu cầu đó, phân công, lịch sử phân công và tiến độ duyệt; không cho sửa, gửi duyệt, sao chép, xem ngoại lệ định biên, quyết định duyệt, đổi trạng thái, phân công hay xem ứng viên đang xử lý (task 291). Bàn giao hoặc bỏ recruiter hỗ trợ lấy lại quyền xem ngay khi lưu. Lọc `recruiterId` (recruiter chính) không mở rộng phạm vi. Xem câu hỏi 14 và 19 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md).
- Từ task 303, recruiter được phân công soạn và xem [tin tuyển dụng](job-postings.md) của yêu cầu đó; từ task 285 họ cũng đọc được chính yêu cầu.

## Xem phân công

`GET /requisitions/{id}/assignment` trả 200:

```json
{
  "requisitionId": "6f0c1f9e-8f3e-4b67-9a39-2c1d3f4e5a6b",
  "primaryRecruiter": {
    "recruiterId": "00000000-0000-0000-0000-000000000011",
    "fullName": "Trần Anh Tuấn",
    "eligible": true,
    "assignedById": "00000000-0000-0000-0000-000000000001",
    "assignedBy": "Nguyễn Thị Hoa",
    "assignedAt": "2026-10-10T03:00:00.123456Z"
  },
  "supportingRecruiters": [
    {
      "recruiterId": "00000000-0000-0000-0000-000000000012",
      "fullName": "Lê Văn Cường",
      "eligible": false,
      "assignedById": "00000000-0000-0000-0000-000000000001",
      "assignedBy": "Nguyễn Thị Hoa",
      "assignedAt": "2026-10-10T03:05:00Z"
    }
  ]
}
```

- Yêu cầu chưa phân công trả `"primaryRecruiter": null` và `"supportingRecruiters": []`. Cả hai khóa luôn có.
- Recruiter hỗ trợ xếp theo thứ tự được thêm.
- Response chỉ có `id` và họ tên, không có email. Tên được đọc lúc trả, nên đổi tên tài khoản thì hiện tên mới.
- `eligible` cho biết người đó hiện có được giao vai trò mới không: còn vai trò RECRUITER, không bị quản trị khóa, đã kích hoạt. Khóa này **chỉ có** khi người gọi có `REQUISITIONS_WRITE_ALL`; người khác không nhận khóa này. `eligible: false` là dấu hiệu nên chuyển giao.
- Thứ tự kiểm giống `GET /requisitions/{id}`: quyền (401/403), yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`), rồi phòng ban phụ trách, phân công (task 285) hoặc tham gia duyệt (task 262); không khớp thì 403.
- Recruiter xem qua phân công (task 285) nhận response như Trưởng bộ phận: không có `eligible`.

## Phân công và chuyển giao

`POST /requisitions/{id}/assign` trả 200 với phân công mới (cùng dạng như `GET`, luôn có `eligible`).

```json
{ "recruiterId": "00000000-0000-0000-0000-000000000011", "role": "PRIMARY", "note": "Chuyển giao do HR cũ nghỉ phép" }
```

| Trường | Quy tắc |
|---|---|
|recruiterId|Bắt buộc. UUID tài khoản. Chuỗi không phải UUID (ví dụ `"HR01"`) là `INVALID_JSON`|
|role|Không bắt buộc. `PRIMARY` (recruiter chính) hoặc `SUPPORTING` (recruiter hỗ trợ), đúng chữ hoa. Không gửi hoặc `null` nghĩa là `PRIMARY`, nên body `{recruiterId, note}` mà frontend đang gửi là giao hoặc chuyển giao recruiter chính|
|note|Không bắt buộc. Tối đa 1.000 ký tự, không chứa ký tự NUL. Lý do của lần thay đổi, lưu vào lịch sử (task 286); không gửi, `null` hoặc không có ký tự nhìn thấy được (rỗng, chỉ có khoảng trắng và xuống dòng, kể cả khoảng trắng không ngắt như U+00A0, hay chỉ có ký tự rộng 0 như U+200B) thì lưu `null`|

Trường khác (ví dụ `assignedBy`) trả `INVALID_JSON`.

| `role` | Tình huống | Kết quả |
|---|---|---|
|PRIMARY|Chưa có recruiter chính|Giao recruiter chính|
|PRIMARY|Đã có recruiter chính khác|**Chuyển giao**: người mới thay người cũ, người cũ rời khỏi yêu cầu (HR có thể thêm lại làm hỗ trợ). Nếu người mới đang là hỗ trợ thì được nâng lên chính|
|PRIMARY|Chính người đó đang là recruiter chính|Không đổi gì, 200|
|SUPPORTING|Chưa có recruiter chính|409 `REQUISITION_PRIMARY_RECRUITER_REQUIRED`|
|SUPPORTING|Người đó là recruiter chính|409 `REQUISITION_RECRUITER_ALREADY_PRIMARY`|
|SUPPORTING|Đã có 10 recruiter hỗ trợ|409 `REQUISITION_SUPPORTING_RECRUITER_LIMIT`|
|SUPPORTING|Người đó đã là hỗ trợ|Không đổi gì, 200|
|SUPPORTING|Còn lại|Thêm recruiter hỗ trợ|

## Bỏ recruiter hỗ trợ

`POST /requisitions/{id}/unassign` với body `{ "recruiterId": "...", "note": "..." }` (không có `role`) trả 200 với phân công mới. Người không được phân công thì không đổi gì. Recruiter chính **không bỏ được**, chỉ chuyển giao: 409 `REQUISITION_PRIMARY_RECRUITER_REQUIRED`.

## Lịch sử phân công (task 286)

Mỗi lần nhóm recruiter **thực sự** thay đổi, backend ghi thêm một dòng vào bảng `requisition_recruiter_changes` (V17), trong cùng transaction và với cùng thời điểm như thay đổi đó. Lần gọi không đổi gì và lần gọi bị từ chối không ghi gì. Lịch sử chỉ được thêm: không có API sửa hay xóa.

`GET /requisitions/{id}/assignment-history` trả **mảng JSON** (không bọc trong `items`), mới nhất trước; mảng rỗng `[]` khi chưa có thay đổi. Quyền và thứ tự lỗi giống `GET /assignment`.

```json
[
  {
    "id": "0b6f...",
    "revision": 2,
    "changeType": "PRIMARY_HANDED_OVER",
    "recruiterId": "00000000-0000-0000-0000-000000000011",
    "assignedTo": "Trần Anh Tuấn",
    "previousRecruiterId": "00000000-0000-0000-0000-000000000013",
    "previousAssignedTo": "Nguyễn Thị Phương",
    "assignedById": "00000000-0000-0000-0000-000000000001",
    "assignedBy": "Nguyễn Thị Hoa",
    "assignedAt": "2026-10-10T03:15:30.123456Z",
    "note": "Chuyển giao do HR cũ nghỉ phép"
  }
]
```

| `changeType` | Ý nghĩa | `assignedTo` | `previousAssignedTo` |
|---|---|---|---|
|`PRIMARY_ASSIGNED`|Giao recruiter chính lần đầu|Recruiter chính mới|`null`|
|`PRIMARY_HANDED_OVER`|Chuyển giao (kể cả nâng recruiter hỗ trợ lên chính)|Recruiter chính mới|Recruiter chính cũ|
|`SUPPORTING_ADDED`|Thêm recruiter hỗ trợ|Người được thêm|`null`|
|`SUPPORTING_REMOVED`|Bỏ recruiter hỗ trợ|Người bị bỏ|`null`|

- `assignedTo`, `assignedBy`, `assignedAt`, `note` đúng là các trường frontend đang hiển thị. Riêng với `SUPPORTING_REMOVED`, `assignedTo` là người **bị bỏ**, nên giao diện nên ghi nhãn theo `changeType`.
- `revision` là số thứ tự 1, 2, 3… của mỗi yêu cầu, theo đúng thứ tự các thay đổi xảy ra. Danh sách xếp theo `revision`, không theo thời gian, vì hai thay đổi có thể cùng thời điểm. Áp lần lượt các dòng từ `revision` 1 sẽ ra đúng nhóm hiện tại.
- Tên người được đọc lúc trả, nên tài khoản đổi tên thì hiện tên mới.
- Phân công được lưu trước khi có V17 (nếu task 284 được phát hành trước) được V17 ghi bổ sung mỗi người một dòng, recruiter chính trước, không có người tiền nhiệm và ghi chú.

## Recruiter xem yêu cầu được phân công (task 285)

Người có `REQUISITIONS_READ_SCOPED` đang là recruiter chính hoặc hỗ trợ của một yêu cầu xem được yêu cầu đó: nó có trong `GET /requisitions` của họ, và `GET /requisitions/{id}`, `/assignment`, `/assignment-history`, `/approvals` (tiến độ duyệt) trả 200, ở mọi trạng thái. Mọi thao tác ghi vẫn theo phòng ban; `/headcount-overrides` cũng vậy. Bảng đầy đủ từng API ở mục "Recruiter được phân công" của [API yêu cầu tuyển dụng](requisitions.md).

- Quyền xem đi theo nhóm **hiện tại** (bảng `requisition_recruiters`), không theo lịch sử phân công: sau khi bàn giao, recruiter chính mới xem được và người cũ mất quyền ngay từ request bắt đầu sau khi lần bàn giao commit. Bỏ recruiter hỗ trợ cũng vậy. Thăng recruiter hỗ trợ lên recruiter chính thì người đó vẫn xem được, còn recruiter chính cũ thì không.
- Lệnh đọc không khóa gì và không chờ một lần đổi nhóm đang chạy; lệnh đọc bắt đầu trước khi lần đổi nhóm commit vẫn trả theo nhóm cũ.
- Quyền xem theo mã quyền: người được phân công mất vai trò RECRUITER nhưng còn `REQUISITIONS_READ_SCOPED` từ vai trò khác vẫn xem được; không còn quyền đọc nào thì 403, dù dòng phân công vẫn còn.
- Quy tắc nằm ở `RecruiterAssignmentAccess` (gói `requisition`): yêu cầu được giao cho một người là yêu cầu có dòng của người đó trong `requisition_recruiters`, vai trò chính hay hỗ trợ đều được. Danh sách yêu cầu và tin tuyển dụng dùng truy vấn con `ASSIGNED_REQUISITION_IDS` trong câu SQL; một yêu cầu dùng `isAssigned`, được viết bằng chính truy vấn con đó, nên danh sách và chi tiết không thể trả khác nhau. Nó đọc trong transaction của người gọi (bắt buộc có transaction) và không khóa gì.

**Dành cho module ứng viên.** Module ứng viên có từ task 363 (quy tắc đọc; API hồ sơ có từ task 361, CV là task 362). Recruiter (`CANDIDATES_*_SCOPED`) chỉ xem ứng viên và hồ sơ ứng tuyển của yêu cầu mình đang được phân công, qua đoạn SQL `CandidateAccess.READABLE`, dựng trên `ASSIGNED_REQUISITION_IDS` của lớp này và `DepartmentRepository.MANAGED_DEPARTMENT_IDS` (trưởng phòng ban của yêu cầu cũng xem được): danh sách lọc bằng `... AND CandidateAccess.READABLE` trong câu SQL, một bản ghi kiểm bằng `CandidateAccess.findApplication` (cùng đoạn SQL, không dùng `isAssigned`); thao tác ghi về một ứng viên kiểm `isAssigned` khi đang giữ khóa dòng yêu cầu (mọi lần đổi nhóm đều khóa dòng đó trước). Xem [ai được xem hồ sơ ứng viên](candidate-profiles.md#ai-được-xem-task-363). Còn chờ BA/PO: "được phân công" theo yêu cầu tuyển dụng hay theo chức danh (backend: theo yêu cầu, V16), và ứng viên nộp vào nhiều yêu cầu thì recruiter thấy cả hồ sơ hay chỉ đơn ứng tuyển vào yêu cầu của mình (câu hỏi 19; backend tạm chỉ trả đơn của mình, task 361, câu hỏi 25 ý 7).

## Ai được phân công

Người **được giao vai trò mới** phải:

- là tài khoản tồn tại có vai trò RECRUITER, nếu không thì 400 `INVALID_REQUISITION_RECRUITER`, `fieldErrors.recruiterId`;
- không bị quản trị khóa và đã kích hoạt, nếu không thì 400 `REQUISITION_RECRUITER_INACTIVE`, `fieldErrors.recruiterId`.

Bị khóa tạm vì nhập sai mật khẩu vẫn được giao, vì khóa đó tự hết. Người vừa là HR_MANAGER vừa là RECRUITER cũng được giao. Không giới hạn theo phòng ban.

Người **giữ nguyên vai trò đang có** thì không bị kiểm lại. Một recruiter bị khóa hoặc mất vai trò RECRUITER sau khi được giao vẫn ở trong nhóm cho tới khi HR chuyển giao; `eligible: false` báo điều đó.

## Trạng thái của yêu cầu

Phân công được khi yêu cầu ở trạng thái `DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED` (`RequisitionRecruiterService.ASSIGNABLE_STATUSES`, task 257) hoặc `PAUSED` (tạm dừng, task 293: vẫn bàn giao được); yêu cầu `REJECTED`, `FILLED` (đã tuyển đủ) hoặc `CANCELLED` (đã hủy) trả 409 `REQUISITION_NOT_ASSIGNABLE`. Khi luồng duyệt thêm trạng thái mới, nhóm phải xếp trạng thái đó vào nhóm được phân công hay không: `RequisitionRecruiterStatusTest` không biên dịch cho tới khi làm việc này. Xem phân công được ở mọi trạng thái.

Phân công gắn với **yêu cầu tuyển dụng**, không gắn với chức danh trong danh mục. Nó không đổi `updatedAt` của yêu cầu. Yêu cầu chuyển sang phòng ban khác thì nhóm recruiter đi theo. Bản sao (task 278, 279) bắt đầu chưa được phân công.

## Thứ tự kiểm khi ghi

1. Quyền ở `SecurityConfiguration` (401/403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors`.
3. Khóa tài khoản người gọi và tài khoản người được giao (theo thứ tự id), rồi phiên; kiểm lại hạn token, phiên và quyền (401 `SESSION_INVALID` / 403).
4. Khóa dòng yêu cầu tuyển dụng, rồi kiểm lại người gọi lần nữa. Người gọi phải có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`, nếu không thì 403 ngay, trước khi biết yêu cầu có tồn tại hay không.
5. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
6. Người chỉ có `REQUISITIONS_READ_SCOPED` phải phụ trách phòng ban của yêu cầu (403), theo quy tắc phòng ban của `GET /requisitions/{id}`; người tham gia duyệt và recruiter được phân công không được tính (task 262, 285).
7. Trạng thái cho phép phân công (409 `REQUISITION_NOT_ASSIGNABLE`).
8. Người đó đã có đúng vai trò: 200, không ghi gì.
9. Người được giao hợp lệ (400).
10. Quy tắc của nhóm (409).

## Khóa và đồng thời

- Mọi lần đổi nhóm đều khóa dòng yêu cầu tuyển dụng trước khi đọc nhóm hiện tại. Vì vậy hai HR thao tác cùng lúc chạy lần lượt, và lệnh sau áp lên kết quả của lệnh trước. Ví dụ: hai HR cùng thêm hai recruiter hỗ trợ khác nhau thì cả hai đều được giữ.
- Tài khoản người được giao bị khóa cùng tài khoản người gọi, theo cùng thứ tự với quản trị tài khoản. Nếu Admin gỡ vai trò RECRUITER hoặc khóa người đó trong lúc lệnh đang chờ, lệnh sẽ thấy thay đổi và trả 400.
- Database có lớp chặn cuối: mỗi yêu cầu tối đa một recruiter chính (`requisition_recruiters_one_primary_idx`), mỗi người tối đa một dòng.
- Từ task 285, nhóm quyết định ai xem được yêu cầu: một lần đổi nhóm đã commit có hiệu lực từ request đọc tiếp theo. Lệnh đọc không chờ khóa dòng yêu cầu, nên không thêm chờ đợi hay deadlock nào.

## Lỗi

| HTTP | `code` | Khi nào |
|---|---|---|
|400|`VALIDATION_ERROR`|Thiếu `recruiterId`; `role` sai; `note` quá 1.000 ký tự hoặc chứa ký tự NUL; `{id}` không phải UUID|
|400|`INVALID_JSON`|JSON sai, `recruiterId` không phải UUID, trường không được phép, thiếu body|
|400|`INVALID_REQUISITION_RECRUITER`|Tài khoản không tồn tại hoặc không có vai trò RECRUITER|
|400|`REQUISITION_RECRUITER_INACTIVE`|Tài khoản bị quản trị khóa hoặc chưa kích hoạt|
|401|`UNAUTHORIZED` / `SESSION_INVALID`|Token sai, hết hạn hoặc phiên bị thu hồi, kể cả khi điều đó xảy ra trong lúc chờ khóa|
|403|`FORBIDDEN`|Thiếu quyền (kể cả bị gỡ quyền trong lúc chờ); người ghi không có quyền xem yêu cầu; người `SCOPED` xem hoặc ghi yêu cầu ngoài phạm vi|
|404|`REQUISITION_NOT_FOUND`|Không có yêu cầu với `{id}`|
|409|`REQUISITION_PRIMARY_RECRUITER_REQUIRED`|Thêm hỗ trợ khi chưa có recruiter chính; bỏ recruiter chính|
|409|`REQUISITION_RECRUITER_ALREADY_PRIMARY`|Thêm recruiter chính làm hỗ trợ|
|409|`REQUISITION_SUPPORTING_RECRUITER_LIMIT`|Đã đủ 10 recruiter hỗ trợ|
|409|`REQUISITION_NOT_ASSIGNABLE`|Yêu cầu ở trạng thái không cho phân công (bị từ chối, đã tuyển đủ hoặc đã hủy)|

## Ghi chú cho frontend

Frontend nhánh `UI` (`src/services/recruiterAssignmentService.ts`, `AssignRecruiterModal.tsx`) cần sửa:

- Đường dẫn `/job-requisitions/{id}/assign` thành `/requisitions/{id}/assign`, `/job-requisitions/{id}/assignment-history` thành `/requisitions/{id}/assignment-history`. Body `{recruiterId, note}` đã khớp; lịch sử vẫn có bốn trường `assignedTo`, `assignedBy`, `assignedAt`, `note` mà giao diện đang đọc.
- `recruiterId` phải là UUID thật. Danh sách chọn lấy từ `GET /accounts?role=RECRUITER&size=100` (cần `USER_ADMIN_READ_ALL`, có ở HR_MANAGER và ADMIN), thay cho danh sách giả, rồi giữ người có `status` là `ACTIVE` hoặc `TEMPORARILY_LOCKED`. Không lọc `status=ACTIVE` trên URL: bộ lọc đó bỏ mất người đang bị khóa tạm vì nhập sai mật khẩu, trong khi backend vẫn cho giao người này. `status` chỉ nhận một giá trị mỗi lần gọi.
- Chỉ hiện nút "Phân công" khi người dùng có `REQUISITIONS_WRITE_ALL` (người đó cũng phải xem được yêu cầu; nút nằm trong trang chi tiết yêu cầu nên điều này đã đúng). Không dựa vào quyền sửa yêu cầu nói chung, vì Trưởng bộ phận sẽ bấm được rồi bị 403 và bị đưa sang `/unauthorized`.
- Lịch sử: thêm `changeType` (và `previousAssignedTo`) vào `AssignmentHistoryItem`, rồi ghi nhãn mỗi dòng theo `changeType`: giao recruiter chính, chuyển giao từ `previousAssignedTo` sang `assignedTo`, thêm hỗ trợ, bỏ hỗ trợ. `AssignRecruiterModal.tsx` hiện ghi "Giao cho:" cho mọi dòng, nên một lần bỏ recruiter hỗ trợ sẽ hiện thành "Giao cho" chính người vừa bị bỏ.
- Để hiện và quản lý recruiter hỗ trợ: gọi `GET /requisitions/{id}/assignment`, gửi `role: "SUPPORTING"` khi thêm hỗ trợ, gọi `POST /requisitions/{id}/unassign` khi bỏ.
- Task 285: RECRUITER nay thấy yêu cầu mình được phân công trong danh sách và mở được chi tiết, phân công, lịch sử phân công, tiến độ duyệt. Trang chi tiết với người chỉ xem qua phân công là **chỉ đọc**: chỉ hiện "Sửa", "Gửi duyệt", "Sao chép" khi có `REQUISITIONS_WRITE_ALL` hoặc người dùng phụ trách phòng ban của yêu cầu; `REQUISITIONS_WRITE_SCOPED` một mình không đủ (bấm sẽ nhận 403 rồi bị chuyển sang `/unauthorized`). Nút "Phân công" và đổi trạng thái vẫn theo `REQUISITIONS_WRITE_ALL`; quyết định duyệt theo `actions.canDecide`. Không gọi `/headcount-overrides` hay `/headcount-plans/remaining` cho yêu cầu như vậy.
- Task 285: sau khi bàn giao, lần tải tiếp theo của recruiter cũ nhận 403 và interceptor chuyển sang `/unauthorized`. Gợi ý: trang chi tiết bắt 403 và hiện "Bạn không còn được phân công yêu cầu này" kèm liên kết về danh sách (frontend quyết định). `JobPostingForm.tsx` (`GET /requisitions/{id}`) nay chạy được với recruiter được phân công.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 14 trong ma trận vai trò và quyền. Gồm:

- ADMIN cũng được phân công;
- tối đa 10 recruiter hỗ trợ;
- người cũ rời khỏi yêu cầu khi chuyển giao;
- phân công được ở `DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED`, không phân công ở `REJECTED`, `FILLED`, `CANCELLED`;
- không giới hạn theo phòng ban;
- recruiter được giao (chính và hỗ trợ) xem được yêu cầu, phân công, lịch sử phân công và tiến độ duyệt ở mọi trạng thái, mất quyền ngay khi bàn giao (câu hỏi 19);
- lịch sử chỉ ghi lần thay đổi thật, không ghi lần gọi không đổi gì hay bị từ chối (task 286);
- phân công lưu trước V17 được ghi bổ sung thành dòng lịch sử không có người tiền nhiệm và ghi chú (task 286).
