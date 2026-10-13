# API phân công recruiter cho yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-283 (lưu recruiter chính và hỗ trợ) và TKNHTTDNB1-284 (API phân công và bàn giao), story TKNHTTDNB1-35 (S3-06). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; mọi response thành công và lỗi nghiệp vụ dùng `Cache-Control: no-store`.

Tiêu chí của story:

- "Phân công một recruiter chính và nhiều recruiter hỗ trợ": đã có (283, 284).
- "Có ghi lịch sử chuyển giao khi đổi người phụ trách": thuộc task 286.
- "Recruiter chỉ nhìn thấy ứng viên của vị trí được giao": thuộc task 285, chưa làm vì chưa có module ứng viên.

## Quyền

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`GET /requisitions/{id}/assignment`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`, cùng phạm vi như `GET /requisitions/{id}`|ADMIN, HR_MANAGER (mọi yêu cầu); HIRING_MANAGER, RECRUITER, APPROVER (yêu cầu của phòng ban mình phụ trách)|
|`POST /requisitions/{id}/assign`|`REQUISITIONS_WRITE_ALL`, **và** xem được yêu cầu đó như `GET /assignment`|HR_MANAGER, ADMIN|
|`POST /requisitions/{id}/unassign`|Như `POST /assign`|HR_MANAGER, ADMIN|

- Chỉ người có `REQUISITIONS_WRITE_ALL` được phân công. Trưởng bộ phận (`REQUISITIONS_WRITE_SCOPED`) bị 403, vì bảng vai trò ghi việc "phân công recruiter" thuộc về Trưởng phòng Nhân sự. Không thêm mã quyền mới.
- Quyền ghi không bao gồm quyền đọc. `/assign` và `/unassign` trả về cả nhóm, kể cả khi không đổi gì, nên người gọi còn phải xem được yêu cầu đó như `GET /requisitions/{id}`, giống sao chép yêu cầu (task 278). Người chỉ có quyền ghi bị 403, kể cả với `{id}` không tồn tại. Theo seed hiện tại HR_MANAGER và ADMIN có cả `REQUISITIONS_READ_ALL`, nên điều này chưa chặn ai.
- Được phân công **chưa** làm recruiter thấy yêu cầu đó: `REQUISITIONS_*_SCOPED` vẫn hiểu là "phòng ban mình phụ trách". Việc mở rộng phạm vi theo phân công chờ BA/PO (câu hỏi 14 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)) và task 285.

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
- Thứ tự kiểm giống `GET /requisitions/{id}`: quyền (401/403), yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`), phạm vi (403).

## Phân công và chuyển giao

`POST /requisitions/{id}/assign` trả 200 với phân công mới (cùng dạng như `GET`, luôn có `eligible`).

```json
{ "recruiterId": "00000000-0000-0000-0000-000000000011", "role": "PRIMARY", "note": "Chuyển giao do HR cũ nghỉ phép" }
```

| Trường | Quy tắc |
|---|---|
|recruiterId|Bắt buộc. UUID tài khoản. Chuỗi không phải UUID (ví dụ `"HR01"`) là `INVALID_JSON`|
|role|Không bắt buộc. `PRIMARY` (recruiter chính) hoặc `SUPPORTING` (recruiter hỗ trợ), đúng chữ hoa. Không gửi hoặc `null` nghĩa là `PRIMARY`, nên body `{recruiterId, note}` mà frontend đang gửi là giao hoặc chuyển giao recruiter chính|
|note|Không bắt buộc. Tối đa 1.000 ký tự, không chứa ký tự NUL. Hiện được kiểm và nhận, nhưng chưa được lưu: lịch sử lưu ghi chú là task 286|

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

## Ai được phân công

Người **được giao vai trò mới** phải:

- là tài khoản tồn tại có vai trò RECRUITER, nếu không thì 400 `INVALID_REQUISITION_RECRUITER`, `fieldErrors.recruiterId`;
- không bị quản trị khóa và đã kích hoạt, nếu không thì 400 `REQUISITION_RECRUITER_INACTIVE`, `fieldErrors.recruiterId`.

Bị khóa tạm vì nhập sai mật khẩu vẫn được giao, vì khóa đó tự hết. Người vừa là HR_MANAGER vừa là RECRUITER cũng được giao. Không giới hạn theo phòng ban.

Người **giữ nguyên vai trò đang có** thì không bị kiểm lại. Một recruiter bị khóa hoặc mất vai trò RECRUITER sau khi được giao vẫn ở trong nhóm cho tới khi HR chuyển giao; `eligible: false` báo điều đó.

## Trạng thái của yêu cầu

Phân công được khi yêu cầu ở trạng thái `DRAFT`, trạng thái duy nhất hiện có (`RequisitionRecruiterService.ASSIGNABLE_STATUSES`); trạng thái khác trả 409 `REQUISITION_NOT_ASSIGNABLE`. Khi luồng duyệt thêm trạng thái mới, nhóm phải xếp trạng thái đó vào nhóm được phân công hay không: `RequisitionRecruiterStatusTest` không biên dịch cho tới khi làm việc này. Xem phân công được ở mọi trạng thái.

Phân công gắn với **yêu cầu tuyển dụng**, không gắn với chức danh trong danh mục. Nó không đổi `updatedAt` của yêu cầu. Yêu cầu chuyển sang phòng ban khác thì nhóm recruiter đi theo. Bản sao (task 278, 279) bắt đầu chưa được phân công.

## Thứ tự kiểm khi ghi

1. Quyền ở `SecurityConfiguration` (401/403). `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Body: `INVALID_JSON`, rồi `VALIDATION_ERROR` với `fieldErrors`.
3. Khóa tài khoản người gọi và tài khoản người được giao (theo thứ tự id), rồi phiên; kiểm lại hạn token, phiên và quyền (401 `SESSION_INVALID` / 403).
4. Khóa dòng yêu cầu tuyển dụng, rồi kiểm lại người gọi lần nữa. Người gọi phải có `REQUISITIONS_READ_ALL` hoặc `REQUISITIONS_READ_SCOPED`, nếu không thì 403 ngay, trước khi biết yêu cầu có tồn tại hay không.
5. Yêu cầu tồn tại (404 `REQUISITION_NOT_FOUND`).
6. Người chỉ có `REQUISITIONS_READ_SCOPED` phải phụ trách phòng ban của yêu cầu (403), như `GET /requisitions/{id}`.
7. Trạng thái cho phép phân công (409 `REQUISITION_NOT_ASSIGNABLE`).
8. Người đó đã có đúng vai trò: 200, không ghi gì.
9. Người được giao hợp lệ (400).
10. Quy tắc của nhóm (409).

## Khóa và đồng thời

- Mọi lần đổi nhóm đều khóa dòng yêu cầu tuyển dụng trước khi đọc nhóm hiện tại. Vì vậy hai HR thao tác cùng lúc chạy lần lượt, và lệnh sau áp lên kết quả của lệnh trước. Ví dụ: hai HR cùng thêm hai recruiter hỗ trợ khác nhau thì cả hai đều được giữ.
- Tài khoản người được giao bị khóa cùng tài khoản người gọi, theo cùng thứ tự với quản trị tài khoản. Nếu Admin gỡ vai trò RECRUITER hoặc khóa người đó trong lúc lệnh đang chờ, lệnh sẽ thấy thay đổi và trả 400.
- Database có lớp chặn cuối: mỗi yêu cầu tối đa một recruiter chính (`requisition_recruiters_one_primary_idx`), mỗi người tối đa một dòng.

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
|409|`REQUISITION_NOT_ASSIGNABLE`|Yêu cầu ở trạng thái không cho phân công (chưa xảy ra được)|

## Ghi chú cho frontend

Frontend nhánh `UI` (`src/services/recruiterAssignmentService.ts`, `AssignRecruiterModal.tsx`) cần sửa:

- Đường dẫn `/job-requisitions/{id}/assign` thành `/requisitions/{id}/assign`. Body `{recruiterId, note}` đã khớp.
- `recruiterId` phải là UUID thật. Danh sách chọn lấy từ `GET /accounts?role=RECRUITER&size=100` (cần `USER_ADMIN_READ_ALL`, có ở HR_MANAGER và ADMIN), thay cho danh sách giả, rồi giữ người có `status` là `ACTIVE` hoặc `TEMPORARILY_LOCKED`. Không lọc `status=ACTIVE` trên URL: bộ lọc đó bỏ mất người đang bị khóa tạm vì nhập sai mật khẩu, trong khi backend vẫn cho giao người này. `status` chỉ nhận một giá trị mỗi lần gọi.
- Chỉ hiện nút "Phân công" khi người dùng có `REQUISITIONS_WRITE_ALL` (người đó cũng phải xem được yêu cầu; nút nằm trong trang chi tiết yêu cầu nên điều này đã đúng). Không dựa vào quyền sửa yêu cầu nói chung, vì Trưởng bộ phận sẽ bấm được rồi bị 403 và bị đưa sang `/unauthorized`.
- Để hiện và quản lý recruiter hỗ trợ: gọi `GET /requisitions/{id}/assignment`, gửi `role: "SUPPORTING"` khi thêm hỗ trợ, gọi `POST /requisitions/{id}/unassign` khi bỏ.

## Giả định chờ BA/PO

Các quy tắc trên là giả định của backend, liệt kê ở câu hỏi 14 trong ma trận vai trò và quyền. Gồm:

- ADMIN cũng được phân công;
- tối đa 10 recruiter hỗ trợ;
- người cũ rời khỏi yêu cầu khi chuyển giao;
- chỉ phân công được ở `DRAFT`;
- không giới hạn theo phòng ban;
- recruiter được giao chưa xem được yêu cầu.
