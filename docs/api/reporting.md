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

## TKNHTTDNB1-556 — Biểu đồ hồ sơ theo tuần và vị trí đã đóng theo tháng

`GET /api/v1/reports/dashboard/charts?from=2026-09-02&to=2026-10-15`

- `from`, `to` (yyyy-MM-dd): nhập cả hai hoặc bỏ trống cả hai; tối đa 366 ngày; từ 2000-01-01 đến 2100-12-31.
- Bỏ trống: biểu đồ tuần lấy 12 tuần gần nhất (tính cả tuần hiện tại), biểu đồ tháng lấy 12 tháng gần nhất (tính cả
  tháng hiện tại).
- Có `from`/`to`: cả hai chuỗi phủ trọn các tuần (thứ Hai–Chủ nhật) và trọn các tháng chứa khoảng đó. Ví dụ trên cho
  tuần từ 2026-08-31 đến 2026-10-18 (7 điểm) và tháng 2026-09 đến 2026-10 (2 điểm). `from`/`to` của từng chuỗi trong
  response là khoảng thực tế đã dùng.

Response 200:

```json
{
  "scope": "ALL",
  "weeklyApplications": {
    "from": "2026-08-31", "to": "2026-10-18",
    "points": [
      {"weekStart": "2026-08-31", "weekEnd": "2026-09-06", "applications": 2},
      {"weekStart": "2026-09-07", "weekEnd": "2026-09-13", "applications": 0}
    ]
  },
  "monthlyClosedRequisitions": {
    "from": "2026-09-01", "to": "2026-10-31",
    "points": [
      {"month": "2026-09", "monthStart": "2026-09-01", "monthEnd": "2026-09-30", "filled": 1, "cancelled": 0, "closed": 1}
    ]
  }
}
```

Định nghĩa:

- `applications`: số hồ sơ có `received_at` trong tuần (giờ Việt Nam), trong phạm vi người xem, kể cả hồ sơ sau đó bị
  loại hoặc rút.
- `filled` / `cancelled`: số yêu cầu tuyển dụng có sự kiện `FILLED` / `CANCELLED` (lịch sử chỉ ghi thêm, mỗi yêu cầu
  tối đa một sự kiện đóng) xảy ra trong tháng. `closed = filled + cancelled`. Tạm dừng (`PAUSED`) không phải là đóng.
- Mỗi tuần/tháng luôn có một điểm, kể cả khi bằng 0, để biểu đồ không bị thiếu cột.
- Thời điểm bắt đầu tuần/tháng được tính theo múi giờ nghiệp vụ trong Java rồi so sánh trong SQL bằng `width_bucket`,
  nên không phụ thuộc múi giờ của PostgreSQL.

Lỗi: 400 `VALIDATION_ERROR` khi chỉ có một đầu, `to < from`, quá 366 ngày, ngày sai định dạng hoặc ngoài khoảng cho
phép; 401/403 như phần chung.

Ghi chú cho frontend: vẽ cột theo `points` theo đúng thứ tự trả về; nhãn tuần nên hiển thị `weekStart`–`weekEnd`; nhãn
tháng dùng `month`. Khi người dùng đổi khoảng ngày, gửi lại cả `from` và `to`.

## TKNHTTDNB1-557 — API dashboard tổng hợp theo phạm vi truy cập

`GET /api/v1/reports/dashboard?from=&to=&departmentId=&positionId=&page=&size=`

Một lần gọi trả đủ thẻ chỉ số, danh sách trễ hạn (task 555) và cả hai biểu đồ (task 556), cùng một snapshot. Response
của task 555 có thêm:

- `filters`: `{"departmentId": "…" | null, "positionId": "…" | null}`.
- `charts`: đúng cấu trúc của `GET /api/v1/reports/dashboard/charts` (gồm `scope`, `filters`,
  `weeklyApplications`, `monthlyClosedRequisitions`).

Tham số:

| Tham số | Ý nghĩa |
| --- | --- |
| `from`, `to` | khoảng ngày của biểu đồ (quy tắc như task 556). Thẻ chỉ số luôn mô tả hôm nay |
| `departmentId` | chỉ phòng ban đó (không gồm phòng ban con, giống bộ lọc danh sách yêu cầu tuyển dụng) |
| `positionId` | chỉ các yêu cầu của chức danh đó, ở mọi phòng ban trong phạm vi |
| `page`, `size` | phân trang danh sách trễ hạn |

`GET /api/v1/reports/dashboard/charts` nhận thêm `departmentId`, `positionId` với cùng ý nghĩa.

Bộ lọc chỉ thu hẹp phạm vi, không bao giờ mở rộng. Thứ tự kiểm tra:

1. 401 nếu chưa đăng nhập; 403 nếu thiếu quyền đọc báo cáo (trước mọi tra cứu).
2. 404 `DEPARTMENT_NOT_FOUND` ("Không tìm thấy phòng ban.") hoặc `POSITION_NOT_FOUND` ("Không tìm thấy chức danh.")
   nếu id không tồn tại.
3. 403 `FORBIDDEN` nếu người xem SCOPED lọc theo phòng ban mình không quản lý và không có yêu cầu nào được phân công ở
   đó, hoặc theo chức danh không có yêu cầu nào trong phạm vi. HR (ALL) lọc được mọi phòng ban/chức danh tồn tại, kể
   cả khi rỗng.
4. 400 `VALIDATION_ERROR` cho UUID sai định dạng, khoảng ngày sai, trang sai.

Bảo đảm phạm vi (đã có test tích hợp):

- Mọi phần (thẻ, phỏng vấn, offer, trễ hạn, biểu đồ tuần, biểu đồ tháng) dùng cùng điều kiện phạm vi và bộ lọc.
- Gỡ phân công recruiter hoặc đổi trưởng phòng: request kế tiếp không còn thấy số liệu đó.
- Thu hồi quyền `REPORTS_READ_SCOPED`: request kế tiếp với cùng token bị 403.
- HR có `REPORTS_READ_ALL` nhưng bị hạ `CANDIDATES` xuống SCOPED: báo cáo chuyển sang `SCOPED`.
- Response không chứa tên, email, số điện thoại hay id hồ sơ ứng viên.

Ghi chú cho frontend: trang dashboard chỉ cần gọi một API này. Bộ chọn phòng ban/chức danh nên lấy từ
`GET /api/v1/departments` và `GET /api/v1/positions`; với người xem SCOPED, xử lý 403 bằng thông báo "Bạn không phụ
trách phòng ban/chức danh này" và giữ bộ lọc cũ.

## TKNHTTDNB1-558 — Tối ưu truy vấn dashboard cho mục tiêu dưới 2 giây

Không thêm API. Cách đạt mục tiêu:

- **Cửa sổ có giới hạn**: biểu đồ tối đa 366 ngày (54 tuần, 13 tháng), thẻ tuần chỉ đọc một tuần, danh sách trễ hạn tối
  đa 50 dòng mỗi trang, mỗi câu SQL bị dừng sau 5 giây (`statement_timeout`), mỗi báo cáo trong một transaction tối đa
  15 giây.
- **Tổng hợp trong SQL**: mỗi phần là một câu `count`/`width_bucket … GROUP BY`; không tải từng hồ sơ lên Java, không
  truy vấn lặp theo từng dòng (số người đã tuyển của dòng trễ hạn là subquery dùng
  `job_applications_requisition_stage_idx`).
- **Điều kiện phạm vi gọn**: người xem ALL nhận điều kiện `TRUE` (không có subquery phạm vi trong câu SQL); người xem
  SCOPED dùng hai subquery (phân công hiện tại, cây phòng ban) mà PostgreSQL chạy một lần mỗi câu.
- **Index mới (V67)**:

| Index | Phục vụ |
| --- | --- |
| `job_applications_received_at_idx (received_at, requisition_id)` | thẻ hồ sơ trong tuần, biểu đồ tuần, sau này là cohort phễu/nguồn |
| `recruitment_requisitions_open_needed_by_idx (needed_by, id) WHERE status IN ('APPROVED','PAUSED') AND needed_by IS NOT NULL` | danh sách trễ hạn và tổng số |
| `requisition_workflow_events_closed_at_idx (occurred_at, requisition_id) WHERE event_type IN ('FILLED','CANCELLED')` | biểu đồ vị trí đóng theo tháng |
| `offers_sent_deadline_idx (response_deadline, application_id) WHERE status = 'SENT'` | thẻ offer chờ phản hồi |

Đo thực tế (test `DashboardPerformanceIntegrationTest`, PostgreSQL nhúng trên máy phát triển Windows đang chạy song
song các bộ test khác; gọi HTTP thật `GET /api/v1/reports/dashboard?from=2025-10-16&to=2026-10-15`, 3 lần khởi động
rồi đo 10 lần cho mỗi người xem). Dữ liệu: 400 yêu cầu, 160.002 hồ sơ (40.000 trong 400 ngày gần nhất có lịch sử giai
đoạn, 120.000 hồ sơ cũ tới 5 năm), 74.285 sự kiện giai đoạn, 17.143 buổi phỏng vấn, 5.714 offer.

| Người xem | Trước V67 (trung vị / chậm nhất) | Sau V67 + điều kiện TRUE (trung vị / chậm nhất) |
| --- | --- | --- |
| HR_MANAGER (ALL) | 388 ms / 598 ms | 368 ms / 528 ms |
| RECRUITER (SCOPED) | 453 ms / 530 ms | 301 ms / 500 ms |
| HIRING_MANAGER (SCOPED) | 451 ms / 540 ms | 317 ms / 380 ms |
| Tham chiếu `GET /api/v1/auth/me` (lời gọi đơn giản nhất có xác thực) | trung vị 176 ms | trung vị 160 ms |

Hai lớp test:

- `DashboardQueryPlanIntegrationTest` (chạy trong bộ test mặc định): kiểm tra bốn index tồn tại, rồi trên dữ liệu nhỏ
  (5.000 yêu cầu, 5.000 hồ sơ trong hai năm, 3.000 sự kiện đóng, sau `ANALYZE`) chạy `EXPLAIN (FORMAT JSON)` trên
  **đúng** các câu SQL của repository với tham số thật và khẳng định PostgreSQL dùng index V67 cho thẻ hồ sơ trong
  tuần, biểu đồ tuần, danh sách trễ hạn và số vị trí đóng trong tháng.
- `DashboardPerformanceIntegrationTest` (gắn `@Tag("performance")`, bộ test mặc định bỏ qua vì tốn thời gian và phụ
  thuộc tải máy): đo HTTP trên khối lượng lớn. Chạy bằng
  `./mvnw -o -q test -Pperformance -Dtest=DashboardPerformanceIntegrationTest`.

Thời gian thực thi trong PostgreSQL (`EXPLAIN ANALYZE`, cùng dữ liệu): đếm hồ sơ trong tuần 44,7 ms (Parallel Seq Scan)
→ 0,75 ms (Index Only Scan); biểu đồ 53 tuần 31,8 ms → 17,2 ms (Bitmap Index Scan); danh sách trễ hạn chuyển sang
Index Scan trên index mới (≈0,8 ms). Kết luận trung thực: ngay cả trước khi tối ưu, API đã dưới 2 giây ở khối lượng
này; phần lớn thời gian còn lại là chi phí chung của mọi request (xác thực, đọc quyền, HTTP) như lời gọi tham chiếu cho
thấy. Index giữ cho thời gian các câu SQL tỷ lệ với cửa sổ được xem thay vì toàn bộ lịch sử. Test hiệu năng khẳng định
mỗi lần gọi dưới 2.000 ms. Trên máy đang chạy song song nhiều bộ test, một lần chạy sau đó đo được trung vị 414–751 ms và
chậm nhất 1.277 ms (lời gọi tham chiếu 257 ms): con số phụ thuộc tải máy, nên test này không nằm trong bộ test mặc định.
Đây là thời gian backend (database + service + JSON), không gồm thời gian trình duyệt vẽ trang.

Ghi chú cho frontend: gọi đúng một API `GET /api/v1/reports/dashboard` cho cả trang; không gọi lặp cho từng thẻ. Khi đổi
khoảng ngày của biểu đồ chỉ cần gọi lại với `from`/`to` mới.

## TKNHTTDNB1-562 — Tiến độ pipeline, lịch sắp tới và phiếu còn chờ của một vị trí

`GET /api/v1/reports/requisitions/{id}/progress`

Quyền như phần chung; yêu cầu tuyển dụng phải tồn tại (404 `REQUISITION_NOT_FOUND`) và nằm trong phạm vi người xem
(403): HR thấy mọi yêu cầu; Trưởng bộ phận thấy yêu cầu của phòng ban mình quản lý (kể cả phòng ban con); recruiter
thấy yêu cầu mình đang được phân công.

Response 200:

```json
{
  "generatedAt": "2026-10-15T03:00:00Z",
  "businessDate": "2026-10-15",
  "scope": "SCOPED",
  "requisition": {
    "requisitionId": "…", "positionId": "…", "positionName": "Developer", "departmentId": "…",
    "departmentName": "IT", "status": "APPROVED", "headcount": 2, "hiredCount": 1, "remainingHeadcount": 1,
    "neededBy": "2026-10-25", "daysRemaining": 10
  },
  "stageCounts": [
    {"stage": "NEW", "label": "Hồ sơ mới", "count": 3},
    {"stage": "SCREENING", "label": "Sàng lọc", "count": 2},
    {"stage": "INTERVIEW", "label": "Phỏng vấn", "count": 2},
    {"stage": "OFFER", "label": "Đề xuất offer", "count": 1},
    {"stage": "HIRED", "label": "Nhận việc", "count": 1},
    {"stage": "ONBOARDED", "label": "Hoàn tất hội nhập", "count": 0},
    {"stage": "REJECTED", "label": "Loại", "count": 4},
    {"stage": "WITHDRAWN", "label": "Ứng viên rút", "count": 1}
  ],
  "totalApplications": 14,
  "activeApplications": 8,
  "upcomingInterviews": {
    "available": true, "unavailableReason": null, "total": 2, "limit": 20,
    "items": [{"interviewId": "…", "applicationId": "…", "candidateName": "Nguyễn Văn A", "applicationStage": "INTERVIEW",
               "roundName": "Vòng kỹ thuật", "startsAt": "…", "endsAt": "…", "mode": "ONSITE", "viewerOnPanel": true}]
  },
  "pendingEvaluations": {"available": false, "unavailableReason": "MISSING_EVALUATIONS_WRITE", "total": 0, "limit": 20, "items": []},
  "awaitingDecision": {
    "available": true, "unavailableReason": null, "total": 1, "limit": 20,
    "items": [{"applicationId": "…", "candidateName": "Trần Thị B", "interviewId": "…", "roundName": "Vòng cuối",
               "interviewEndedAt": "…", "submittedEvaluations": 2}]
  }
}
```

Định nghĩa:

| Trường | Cách tính |
| --- | --- |
| `stageCounts` | số hồ sơ của yêu cầu đang ở từng giai đoạn **hiện tại** (đủ 8 giai đoạn, kể cả 0, theo thứ tự pipeline). Khác với phễu (task 567) vốn tính theo lịch sử |
| `activeApplications` | NEW + SCREENING + INTERVIEW + OFFER |
| `hiredCount` | HIRED + ONBOARDED; `remainingHeadcount = max(headcount − hiredCount, 0)` |
| `daysRemaining` | `neededBy − hôm nay` (ngày nghiệp vụ): âm khi đã trễ, `null` khi yêu cầu không có ngày cần người |
| `upcomingInterviews` | buổi `SCHEDULED` có `startsAt ≥ bây giờ` của hồ sơ còn trong pipeline (NEW..OFFER), sớm nhất trước; `viewerOnPanel` cho biết người xem có trong hội đồng (đang hoạt động) |
| `pendingEvaluations` | phiếu **của chính người xem**: buổi phỏng vấn đã kết thúc (`endsAt ≤ bây giờ`), không bị hủy, người xem là thành viên hội đồng đang hoạt động, chưa có phiếu (`NOT_STARTED`) hoặc phiếu còn `DRAFT`. Không bao giờ lộ phiếu của người khác |
| `awaitingDecision` | hồ sơ chờ người xem ra quyết định tuyển (`POST /applications/{id}/hiring-decisions`): giai đoạn `INTERVIEW`, yêu cầu đang `APPROVED`, buổi phỏng vấn mới nhất (giờ bắt đầu muộn nhất trong các buổi `SCHEDULED`) có ít nhất một phiếu `SUBMITTED` của thành viên hội đồng đang hoạt động — đúng điều kiện API quyết định chấp nhận |

Mỗi danh sách tối đa 20 dòng (`limit`), `total` là tổng số dòng khớp. Mỗi danh sách có cổng quyền riêng; thiếu quyền thì
`available: false` và lý do, không trả danh sách rỗng giả:

| Danh sách | Điều kiện | Lý do khi thiếu |
| --- | --- | --- |
| `upcomingInterviews` | `INTERVIEWS_READ_*` | `MISSING_INTERVIEWS_READ` |
| `pendingEvaluations` | `EVALUATIONS_WRITE_*` (chỉ người được viết phiếu mới có phiếu phải nộp; seed: HR_MANAGER, ADMIN, INTERVIEWER) | `MISSING_EVALUATIONS_WRITE` |
| `awaitingDecision` | vai trò HIRING_MANAGER, HR_MANAGER hoặc ADMIN và `EVALUATIONS_READ_*`; HIRING_MANAGER chỉ thấy hồ sơ của phòng ban mình quản lý | `NOT_A_HIRING_DECIDER` |

Ba danh sách hiển thị tên ứng viên, nên mỗi hồ sơ được hiển thị ghi một dòng `GRANTED`/`PROFILE` vào
`candidate_access_events` trong cùng transaction (như Kanban). Số đếm không ghi log. Response không có email, số điện
thoại, CV, điểm hay nhận xét đánh giá.

Câu hỏi BA/PO: "ứng viên đang chờ mình cho ý kiến" được hiểu là cả phiếu đánh giá của chính mình (người phỏng vấn) và
quyết định tuyển (Trưởng bộ phận). Seed hiện tại không cho HIRING_MANAGER quyền viết phiếu, nên với Trưởng bộ phận danh
sách hữu ích là `awaitingDecision`; nếu một tài khoản vừa là Trưởng bộ phận vừa là Người phỏng vấn thì thấy cả hai.

Ghi chú cho frontend: dùng `stageCounts[].label` làm nhãn cột; hiển thị `daysRemaining < 0` thành "Trễ N ngày";
`viewerOnPanel = true` có thể tô nổi buổi người xem tham gia; bấm một dòng chờ quyết định thì mở
`GET /api/v1/applications/{applicationId}/evaluation-summary`.

## TKNHTTDNB1-563 — API tiến độ các vị trí của Trưởng bộ phận

`GET /api/v1/reports/requisition-progress?departmentId=&positionId=&page=0&size=20`

Danh sách các yêu cầu tuyển dụng **đang mở** (`APPROVED`, `PAUSED`) trong phạm vi người xem, gấp nhất trước: `neededBy`
tăng dần, yêu cầu không có ngày cần người ở cuối, rồi theo id. Mỗi dòng là bản tóm tắt của task 562, chỉ có số đếm, không
có tên ứng viên (nên không ghi log truy cập hồ sơ). Bấm vào một dòng thì mở chi tiết
`GET /api/v1/reports/requisitions/{id}/progress`.

Phạm vi quản lý (kiểm tra ở mỗi request, không dựa vào vai trò trong token):

- Trưởng bộ phận (HIRING_MANAGER): yêu cầu của phòng ban có `manager_user_id` là mình và mọi phòng ban con; đổi trưởng
  phòng thì request kế tiếp không còn thấy.
- Recruiter: yêu cầu mình đang là recruiter chính/hỗ trợ; gỡ phân công thì mất ngay.
- HR_MANAGER/ADMIN: mọi yêu cầu.

Tham số: `departmentId`, `positionId` (kiểm tra 404/403 như task 557); `page` 0–10000; `size` 1–50 (mặc định 20).

Response 200:

```json
{
  "generatedAt": "2026-10-15T03:00:00Z",
  "businessDate": "2026-10-15",
  "scope": "SCOPED",
  "filters": {"departmentId": null, "positionId": null},
  "lists": {
    "upcomingInterviews": {"available": true, "unavailableReason": null},
    "pendingEvaluations": {"available": false, "unavailableReason": "MISSING_EVALUATIONS_WRITE"},
    "awaitingDecision": {"available": true, "unavailableReason": null}
  },
  "items": [
    {
      "requisition": {"requisitionId": "…", "positionName": "Developer", "departmentName": "IT", "status": "PAUSED",
                      "headcount": 2, "hiredCount": 1, "remainingHeadcount": 1, "neededBy": "2026-10-10",
                      "daysRemaining": -5, "positionId": "…", "departmentId": "…"},
      "stageCounts": [{"stage": "NEW", "label": "Hồ sơ mới", "count": 0}, "… đủ 8 giai đoạn …"],
      "totalApplications": 3,
      "activeApplications": 1,
      "upcomingInterviews": 1,
      "pendingEvaluations": null,
      "awaitingDecision": 0
    }
  ],
  "page": 0, "size": 20, "totalElements": 3, "totalPages": 1
}
```

Số đếm từng dòng dùng đúng định nghĩa và cổng quyền của task 562. Số đếm người xem không được xem là `null` (không phải
0), và `lists` cho biết lý do một lần cho cả trang. Các số đếm của một trang được tính bằng 4 câu SQL gom nhóm cho tối đa
50 yêu cầu, không truy vấn lặp từng dòng.

Lỗi: 400 `VALIDATION_ERROR` (trang/kích thước/UUID sai), 401, 403 (thiếu quyền hoặc bộ lọc ngoài phạm vi), 404
`DEPARTMENT_NOT_FOUND`/`POSITION_NOT_FOUND`.

Ghi chú cho frontend: đây là màn hình "Vị trí của tôi" của Trưởng bộ phận. Hiển thị `daysRemaining` âm bằng màu cảnh báo;
cột "Chờ tôi quyết định" dùng `awaitingDecision`, cột "Phiếu của tôi" dùng `pendingEvaluations`; giá trị `null` hiển thị
"—" kèm tooltip theo `lists.*.unavailableReason`.

## TKNHTTDNB1-567 — Số ứng viên và tỷ lệ chuyển đổi theo giai đoạn (phễu)

`GET /api/v1/reports/funnel?from=2026-07-01&to=2026-09-30`

- `from`, `to`: khoảng ngày nhận hồ sơ (nhập cả hai hoặc bỏ cả hai; tối đa 366 ngày). Mặc định: 90 ngày gần nhất tính đến
  hôm nay.

Cách đếm (nhất quán cho mọi báo cáo dựa trên pipeline, dùng chung với báo cáo nguồn — `ApplicationReach`):

1. **Cohort**: các hồ sơ (lần ứng tuyển) có `received_at` trong `[00:00 ngày from, 00:00 ngày sau to)` giờ Việt Nam,
   trong phạm vi người xem. Đơn vị đếm là **hồ sơ ứng tuyển**, không phải người: một người nộp vào hai vị trí là hai hồ
   sơ, mỗi hồ sơ được đếm đúng một lần.
2. **Giai đoạn xa nhất đã đạt** của mỗi hồ sơ: thứ hạng cao nhất trong giai đoạn hiện tại và cả hai phía của mọi sự kiện
   trong lịch sử `application_stage_events` (NEW 1 < SCREENING 2 < INTERVIEW 3 < OFFER 4 < HIRED 5 < ONBOARDED 6;
   REJECTED/WITHDRAWN là kết quả, không phải vị trí), tối thiểu là NEW. Bị loại, rút hồ sơ, bị chuyển lùi hay mở lại sau
   đó không làm giảm giai đoạn đã đạt; nhiều sự kiện lặp lại vẫn chỉ đếm một lần. Hồ sơ được HR cho bỏ qua phỏng vấn
   (SCREENING → OFFER) được tính là đã qua INTERVIEW và được đếm riêng ở `interviewSkipped`.
3. Kết quả tính **đến thời điểm gọi API** (`generatedAt`): phễu của một kỳ cũ vẫn tiếp tục được cập nhật khi ứng viên
   trong kỳ đó đi tiếp.

Response 200:

```json
{
  "generatedAt": "2026-10-15T03:00:00Z", "from": "2026-07-01", "to": "2026-09-30", "scope": "ALL",
  "cohortSize": 120, "hired": 6, "overallConversionRate": 0.0500, "interviewSkipped": 1,
  "stages": [
    {"stage": "NEW", "label": "Hồ sơ mới", "nextStage": "SCREENING", "reached": 120, "advanced": 80,
     "conversionRate": 0.6667, "rejected": 30, "withdrawn": 2, "inProgress": 8, "dropped": 32, "dropRate": 0.2667},
    {"stage": "ONBOARDED", "label": "Hoàn tất hội nhập", "nextStage": null, "reached": 3, "advanced": 0,
     "conversionRate": null, "rejected": 0, "withdrawn": 0, "inProgress": 3, "dropped": 0, "dropRate": 0.0000}
  ]
}
```

| Trường | Định nghĩa |
| --- | --- |
| `reached` | số hồ sơ đã đạt ít nhất giai đoạn này. Luôn giảm dần (không tăng) theo thứ tự giai đoạn |
| `advanced` | số hồ sơ đi tiếp được = `reached` của giai đoạn sau |
| `conversionRate` | `advanced / reached` (4 chữ số thập phân, 0.6667 = 66,67%); `null` khi `reached = 0`; giai đoạn cuối luôn `null` |
| `rejected`, `withdrawn` | hồ sơ có giai đoạn xa nhất là giai đoạn này và hiện đang `REJECTED` / `WITHDRAWN` (rơi rụng tại đây) |
| `inProgress` | hồ sơ có giai đoạn xa nhất là giai đoạn này và vẫn còn ở đó (chưa rơi, chưa đi tiếp) |
| `dropped`, `dropRate` | `rejected + withdrawn`; `dropped / reached` (`null` khi `reached = 0`) |
| `hired`, `overallConversionRate` | `reached` của HIRED (gồm cả ONBOARDED); `hired / cohortSize` |

Luôn đúng: `reached = advanced + dropped + inProgress` cho mọi giai đoạn. Cohort rỗng: mọi số đếm là 0, mọi tỷ lệ là
`null` (mẫu số bằng 0 không bao giờ bị chia).

Ghi chú cho frontend: vẽ phễu bằng `stages[].reached`; nhãn chuyển đổi giữa hai cột là `conversionRate` của cột trước.
Tỷ lệ `null` hiển thị "—". Nhắc người dùng rằng đơn vị là hồ sơ ứng tuyển.

## TKNHTTDNB1-568 — API phễu theo bộ lọc và phạm vi quyền

`GET /api/v1/reports/funnel?from=&to=&requisitionId=&departmentId=&positionId=`

| Tham số | Ý nghĩa |
| --- | --- |
| `from`, `to` | khoảng ngày nhận hồ sơ (task 567) |
| `requisitionId` | một yêu cầu tuyển dụng (một "vị trí đang tuyển") |
| `departmentId` | một phòng ban (không gồm phòng ban con) |
| `positionId` | một chức danh, ở mọi phòng ban trong phạm vi |

Các bộ lọc kết hợp bằng AND và chỉ thu hẹp phạm vi người xem. Response có thêm
`"filters": {"requisitionId": …, "departmentId": …, "positionId": …}` (giá trị `null` khi không lọc).

Thứ tự kiểm tra: 401 → 403 (thiếu quyền đọc báo cáo, trước mọi tra cứu) → 404 `REQUISITION_NOT_FOUND` /
`DEPARTMENT_NOT_FOUND` / `POSITION_NOT_FOUND` → 403 khi người xem SCOPED lọc theo yêu cầu ngoài phạm vi, phòng ban mình
không quản lý và không có yêu cầu được phân công, hoặc chức danh không có yêu cầu nào trong phạm vi → 400 cho UUID/ngày
sai.

Phạm vi được đọc lại ở mỗi request (test tích hợp): đổi trưởng phòng hoặc gỡ phân công làm cohort của người đó về 0 ở
request kế tiếp; APPROVER (`REPORTS_READ_ALL` nhưng đọc ứng viên SCOPED) không thấy gì ngoài phạm vi; hạ quyền
`CANDIDATES` của HR xuống SCOPED thì báo cáo chuyển sang SCOPED; thu hồi `REPORTS_READ_ALL` thì 403.

Ghi chú cho frontend: bộ lọc "Vị trí" của màn hình phễu nên gửi `requisitionId` khi người dùng chọn một yêu cầu cụ thể,
hoặc `positionId` khi chọn một chức danh (gộp mọi yêu cầu của chức danh đó).
