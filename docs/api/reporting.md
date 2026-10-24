# Báo cáo tuyển dụng — TKNHTTDNB1-555–582 (story S8-05 … S8-09)

Mọi API báo cáo nằm dưới `/api/v1/reports`, dùng Bearer access token và trả `Cache-Control: no-store`. Mọi con số được
tính bằng SQL từ dữ liệu thật (yêu cầu tuyển dụng, hồ sơ, lịch sử giai đoạn, lịch phỏng vấn, offer, chi phí nhập tay),
không có dữ liệu mẫu hay giá trị cố định.

## Quyền và phạm vi dữ liệu (chung cho mọi báo cáo)

Một báo cáo cần đủ ba quyền đọc: `REPORTS_READ_*`, `REQUISITIONS_READ_*` và `CANDIDATES_READ_*` (ALL hoặc SCOPED).
Thiếu một trong ba: 403 `FORBIDDEN` trước mọi truy vấn. Quyền được đọc lại từ database ở mỗi request, nên thu hồi
quyền có hiệu lực ngay ở request kế tiếp dù token cũ chưa hết hạn. Không token hoặc phiên đã hết: 401.

| Vai trò (seed V3/V33_1) | Phạm vi báo cáo |
| --- | --- |
| ADMIN, HR_MANAGER | `ALL`: toàn công ty |
| RECRUITER | `SCOPED`: các yêu cầu tuyển dụng mình đang là recruiter chính hoặc hỗ trợ |
| HIRING_MANAGER (Trưởng bộ phận) | `SCOPED`: yêu cầu thuộc phòng ban mình quản lý và mọi phòng ban con; cộng các yêu cầu được phân công (nếu có) |
| APPROVER | `SCOPED` (có `REPORTS_READ_ALL` nhưng chỉ có quyền đọc yêu cầu/ứng viên SCOPED) |
| INTERVIEWER, tài khoản không vai trò | 403 |

Quy tắc phạm vi (`ReportingAccess.SCOPE`): chỉ khi cả ba quyền REPORTS, REQUISITIONS, CANDIDATES đều là ALL thì báo cáo
không lọc dòng. Ngược lại, một dòng chỉ được tính khi yêu cầu tuyển dụng của nó đang được phân công cho người xem
(`requisition_recruiters`, chính hoặc hỗ trợ) hoặc thuộc phòng ban người xem quản lý (kể cả phòng ban con). Người tạo
yêu cầu, lịch sử phân công và lịch sử phê duyệt không mở thêm phạm vi. Gỡ phân công hoặc đổi trưởng phòng có hiệu lực ở
request kế tiếp. Quyền rộng của REPORTS không bao giờ nới rộng quyền hẹp của CANDIDATES.

Mỗi báo cáo chạy trong một snapshot `REPEATABLE READ` (quyền, phân công, cây phòng ban và số liệu cùng một thời
điểm), mỗi câu SQL tối đa 5 giây.

Ngày nghiệp vụ theo `app.business-zone` (mặc định `Asia/Ho_Chi_Minh`), không theo múi giờ của máy chủ.

## Lỗi chung

| HTTP | code | Khi nào |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` / `SESSION_INVALID` | thiếu token, token hết hạn, phiên đã đăng xuất |
| 403 | `FORBIDDEN` | thiếu quyền đọc báo cáo |
| 400 | `VALIDATION_ERROR` | tham số sai (trang, kích thước trang, ngày…); `fieldErrors` chỉ ra tham số |
| 503 | `REPORT_BUSY` | báo cáo chạy quá 5 giây một câu SQL, quá thời gian transaction, hoặc chờ khóa quá lâu; thử lại sau |

## TKNHTTDNB1-555 — Thẻ chỉ số và danh sách vị trí trễ hạn

`GET /api/v1/reports/dashboard?page=0&size=10`

- `page` 0–10000 (mặc định 0), `size` 1–50 (mặc định 10): phân trang danh sách trễ hạn.

Response 200:

```json
{
  "generatedAt": "2026-10-15T03:00:00Z",
  "businessDate": "2026-10-15",
  "businessZone": "Asia/Ho_Chi_Minh",
  "scope": "ALL",
  "cards": {
    "openRequisitions": {"available": true, "value": 12, "unavailableReason": null},
    "pausedRequisitions": {"available": true, "value": 1, "unavailableReason": null},
    "newApplicationsThisWeek": {"available": true, "value": 37, "unavailableReason": null},
    "upcomingInterviews": {"available": true, "value": 8, "unavailableReason": null},
    "pendingOffers": {"available": false, "value": null, "unavailableReason": "MISSING_OFFERS_READ"}
  },
  "currentWeek": {"from": "2026-10-12", "to": "2026-10-18"},
  "interviewHorizonDays": 7,
  "overdueRequisitions": {
    "items": [
      {"requisitionId": "…", "positionId": "…", "positionName": "Developer", "departmentId": "…",
       "departmentName": "IT", "status": "APPROVED", "neededBy": "2026-09-15", "daysOverdue": 30,
       "headcount": 2, "hiredCount": 1, "remainingHeadcount": 1, "fullyStaffed": false}
    ],
    "page": 0, "size": 10, "totalElements": 1, "totalPages": 1
  }
}
```

Định nghĩa chỉ số:

| Chỉ số | Cách tính |
| --- | --- |
| `openRequisitions` | số yêu cầu `status = APPROVED` (đang tuyển) trong phạm vi |
| `pausedRequisitions` | số yêu cầu `status = PAUSED` (tạm dừng, chưa đóng) |
| `newApplicationsThisWeek` | số hồ sơ có `received_at` trong tuần nghiệp vụ hiện tại: từ 00:00 thứ Hai đến trước 00:00 thứ Hai tuần sau (giờ Việt Nam). Hồ sơ bị loại hay rút sau đó vẫn được tính (đây là số hồ sơ nhận được) |
| `upcomingInterviews` | số buổi phỏng vấn `SCHEDULED` (không tính đã hủy) có `starts_at` trong `[bây giờ, bây giờ + 7 ngày)`, của hồ sơ chưa ở `REJECTED`/`WITHDRAWN` |
| `pendingOffers` | số offer `SENT` có `response_deadline` còn ở tương lai (offer đã quá hạn nhưng job hết hạn chưa chạy cũng không tính) |
| `overdueRequisitions` | yêu cầu `APPROVED` hoặc `PAUSED` có `needed_by` trước ngày nghiệp vụ hôm nay. Yêu cầu không có ngày cần người không bao giờ trễ. Sắp: yêu cầu còn thiếu người trước, trong đó `needed_by` tăng dần (trễ nhất trước); yêu cầu đã tuyển đủ (`fullyStaffed = true`) xếp sau cùng; cuối cùng theo id |
| `daysOverdue` | số ngày từ `needed_by` đến hôm nay (ngày nghiệp vụ), luôn ≥ 1 |
| `hiredCount` | số hồ sơ của yêu cầu đang ở `HIRED` hoặc `ONBOARDED`; `remainingHeadcount = max(headcount − hiredCount, 0)`; `fullyStaffed = hiredCount ≥ headcount` |

Thẻ cần quyền của module riêng: `upcomingInterviews` cần `INTERVIEWS_READ_*`, `pendingOffers` cần `OFFERS_READ_*`.
Thiếu quyền thì thẻ trả `available: false`, `value: null` và `unavailableReason` (`MISSING_INTERVIEWS_READ`,
`MISSING_OFFERS_READ`), không bao giờ trả số 0 giả. Phỏng vấn và offer chỉ bỏ lọc dòng khi người xem có ALL ở cả bốn
module liên quan.

Câu hỏi BA/PO: yêu cầu đã tuyển đủ người (`fullyStaffed = true`) nhưng chưa được đóng có nên ẩn khỏi danh sách trễ hạn
không? Hiện backend vẫn liệt kê nhưng xếp chúng sau mọi yêu cầu còn thiếu người và trả `fullyStaffed` để giao diện tự
quyết định cách hiển thị.

Ghi chú cho frontend:

- Hiển thị `scope = SCOPED` thành "Số liệu của các vị trí bạn phụ trách".
- Thẻ `available = false`: hiển thị "Không có quyền xem", không hiển thị 0.
- Dùng `remainingHeadcount` để hiển thị "còn thiếu N người"; dòng `fullyStaffed = true` nên hiển thị "đã đủ người,
  chưa đóng yêu cầu" (màu nhạt); `page`/`size` chỉ áp dụng cho danh sách trễ hạn.
