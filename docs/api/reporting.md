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
     "conversionRate": 0.6667, "rejected": 30, "declined": 0, "withdrawn": 2, "inProgress": 8, "dropped": 32,
     "dropRate": 0.2667},
    {"stage": "ONBOARDED", "label": "Hoàn tất hội nhập", "nextStage": null, "reached": 3, "advanced": 0,
     "conversionRate": null, "rejected": 0, "declined": 0, "withdrawn": 0, "inProgress": 3, "dropped": 0,
     "dropRate": 0.0000}
  ]
}
```

| Trường | Định nghĩa |
| --- | --- |
| `reached` | số hồ sơ đã đạt ít nhất giai đoạn này. Luôn giảm dần (không tăng) theo thứ tự giai đoạn |
| `advanced` | số hồ sơ đi tiếp được = `reached` của giai đoạn sau |
| `conversionRate` | `advanced / reached` (4 chữ số thập phân, 0.6667 = 66,67%); `null` khi `reached = 0`; giai đoạn cuối luôn `null` |
| `rejected`, `declined`, `withdrawn` | hồ sơ có giai đoạn xa nhất là giai đoạn này và hiện đang `REJECTED` / `WITHDRAWN` (rơi rụng tại đây). Hồ sơ `REJECTED` mà sự kiện chuyển sang `REJECTED` **gần nhất** là câu trả lời của ứng viên cho offer được tính là `declined` (ứng viên từ chối offer), còn lại là `rejected` (công ty loại) |
| `inProgress` | hồ sơ có giai đoạn xa nhất là giai đoạn này và vẫn còn ở đó (chưa rơi, chưa đi tiếp) |
| `dropped`, `dropRate` | `rejected + declined + withdrawn`; `dropped / reached` (`null` khi `reached = 0`) |
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

## TKNHTTDNB1-569 — Giai đoạn rơi ứng viên nhiều nhất

Không thêm API: `GET /api/v1/reports/funnel` có thêm `totalDropped` và `largestDrop`.

```json
"totalDropped": 5,
"largestDrop": {
  "stage": "SCREENING", "label": "Sàng lọc", "nextStage": "INTERVIEW",
  "reached": 5, "advanced": 1, "conversionRate": 0.2000,
  "dropped": 4, "rejected": 3, "declined": 0, "withdrawn": 1, "dropRate": 0.8000, "shareOfAllDrops": 0.8000,
  "topRejectionReasons": [{"kind": "STAFF_REJECTION", "reason": "Thiếu kinh nghiệm", "count": 2},
                          {"kind": "STAFF_REJECTION", "reason": "Lương kỳ vọng cao", "count": 1}],
  "rule": "MOST_DROPPED_THEN_HIGHEST_DROP_RATE_THEN_EARLIEST_STAGE"
}
```

Cách xác định:

- Mức rơi của một giai đoạn = `dropped` của dòng giai đoạn đó trong `stages` (hồ sơ có giai đoạn xa nhất là giai đoạn
  này và đã bị loại, từ chối offer hoặc rút). Hồ sơ còn đang xử lý (`inProgress`) **không** bị tính là rơi.
- Chọn giai đoạn có `dropped` lớn nhất; bằng nhau thì lấy `dropRate` cao hơn (so sánh chính xác, không làm tròn); vẫn
  bằng nhau thì lấy giai đoạn sớm hơn.
- `shareOfAllDrops = dropped / totalDropped`; `totalDropped` là tổng `dropped` của mọi giai đoạn.
- `topRejectionReasons`: tối đa 5 lý do phổ biến nhất của các hồ sơ đang `REJECTED` tại giai đoạn đó. Mỗi hồ sơ chỉ
  được đếm **một lần**, theo sự kiện chuyển sang `REJECTED` gần nhất (một hồ sơ có thể bị loại, được mở lại bằng offer
  thương lượng rồi lại từ chối). `kind = STAFF_REJECTION`: tên lý do loại lưu trên sự kiện (tên tại thời điểm loại); hồ
  sơ không có sự kiện loại (dữ liệu nhập cũ) là "Không rõ lý do". `kind = CANDIDATE_DECLINE`: ứng viên từ chối offer,
  lý do lấy từ `offers.decline_reason` ("Ứng viên từ chối offer: Lương", "…: Địa điểm", "…: Nhận việc nơi khác",
  "…: Lý do cá nhân", hoặc "Ứng viên từ chối offer (không nêu lý do)"). Sắp theo số lượng giảm dần rồi theo tên. Tổng
  `count` không bao giờ vượt `rejected + declined` của giai đoạn.
- Không có hồ sơ nào rơi: `largestDrop = null`.
- Mọi số trong `largestDrop` trùng với dòng tương ứng trong `stages`, nên giao diện có thể tô đậm cột đó trên biểu đồ.

Ghi chú cho frontend: tô nổi cột `largestDrop.stage` trên biểu đồ phễu và hiển thị câu giải thích, ví dụ "Sàng lọc làm
rơi 4/5 hồ sơ (80%), chiếm 80% tổng số hồ sơ rơi; lý do chính: Thiếu kinh nghiệm (2)". Khi `largestDrop = null` hiển thị
"Chưa có hồ sơ nào bị loại hoặc rút trong kỳ".

## TKNHTTDNB1-573 — Lưu chi phí tuyển dụng nhập thủ công

Bảng mới `recruitment_costs` (migration `V67_1__create_recruitment_costs.sql`). Mỗi khoản là một dòng sổ chi phí của
một yêu cầu tuyển dụng: loại, số tiền VND, ngày phát sinh, mô tả, người nhập và thời điểm nhập. Một khoản **không bao
giờ bị sửa hay xóa**; nhập sai thì **hủy** (ghi người hủy, thời điểm, lý do) rồi nhập lại. Trigger trong database từ
chối mọi UPDATE khác việc hủy một lần và mọi DELETE; yêu cầu tuyển dụng có chi phí không xóa được (khóa ngoại
RESTRICT); không có API xóa.

Quyền: ghi cần `REPORTS_WRITE_*` cùng `REPORTS_READ_*` và `REQUISITIONS_READ_*` (seed: HR_MANAGER, ADMIN). Người ghi
SCOPED (nếu sau này được cấp) chỉ ghi cho yêu cầu trong phạm vi của mình. Đọc danh sách như mọi báo cáo (phần chung).
Ghi theo thứ tự khóa chung: tài khoản → phiên → yêu cầu tuyển dụng (`FOR SHARE`) → dòng chi phí; quyền và phiên được
kiểm tra lại sau khi khóa, nên thu hồi quyền có hiệu lực ngay ở request kế tiếp.

### Ghi một khoản — `POST /api/v1/reports/costs` (201)

```json
{
  "requisitionId": "UUID_YEU_CAU",
  "costType": "ADVERTISING",
  "amountVnd": 5000000,
  "incurredOn": "2026-10-10",
  "description": "Đăng tin TopCV 30 ngày"
}
```

| Trường | Quy tắc |
| --- | --- |
| `costType` | `ADVERTISING` (chi phí đăng tin) hoặc `REFERRAL_BONUS` (thưởng giới thiệu) |
| `amountVnd` | số nguyên JSON (1.5, "1000" → 400 `INVALID_JSON`), từ 1 đến 100.000.000.000 |
| `incurredOn` | yyyy-MM-dd, từ 2000-01-01 đến hôm nay (ngày nghiệp vụ); không nhận ngày tương lai |
| `description` | tùy chọn, ≤ 500 ký tự, văn bản thuần (không HTML/ký tự điều khiển); chỉ có khoảng trắng → lưu `null` |

Không gửi người nhập hay thời gian: server lấy từ token và đồng hồ. Trường lạ → 400 `INVALID_JSON`.

Response: `id, requisitionId, positionId, positionName, departmentId, departmentName, costType, amountVnd, incurredOn,
description, recordedBy, recordedByName, recordedAt, voided, voidedAt, voidedBy, voidedByName, voidReason`.

Lỗi: 400 `VALIDATION_ERROR` (kèm `fieldErrors`), 400 `INVALID_JSON`, 401, 403 (không có quyền ghi hoặc yêu cầu ngoài
phạm vi), 404 `REQUISITION_NOT_FOUND`, 409 `COST_REQUISITION_NOT_APPROVED` (chỉ ghi cho yêu cầu đã từng được **duyệt
cuối**: lịch sử phê duyệt có sự kiện `APPROVED` làm yêu cầu thành `APPROVED`; trạng thái hiện tại có thể là `APPROVED`,
`PAUSED`, `FILLED` hay `CANCELLED`, nhưng yêu cầu bị hủy khi còn nháp/chờ duyệt thì không), 503 `COST_BUSY` (chờ khóa quá
2 giây hoặc quá thời gian xử lý; thử lại sau ít giây).

### Hủy một khoản — `POST /api/v1/reports/costs/{id}/void` (200)

```json
{"reason": "Nhập trùng hóa đơn"}
```

`reason` bắt buộc, ≤ 500 ký tự văn bản thuần. Lỗi: 404 `COST_NOT_FOUND`, 409 `COST_ALREADY_VOIDED` (một khoản chỉ hủy
được một lần), 503 `COST_BUSY`, 400, 401, 403 như trên. Người ghi SCOPED chỉ hủy được khoản của yêu cầu trong phạm vi. Khoản đã hủy vẫn nằm trong sổ nhưng không được tính vào tổng hay chi phí/người.

### Danh sách — `GET /api/v1/reports/costs`

Tham số: `requisitionId`, `departmentId`, `positionId` (kiểm tra 404/403 như task 557), `from`/`to` (theo
`incurredOn`, nhập cả hai hoặc bỏ cả hai, tối đa 366 ngày; bỏ trống = mọi ngày), `costType`, `includeVoided` (mặc định
`false`), `page`, `size` (1–100, mặc định 20). Sắp theo `incurredOn` mới nhất, rồi thời điểm nhập, rồi id.

Response: `scope, filters, from, to, costType, includeVoided, activeEntries, activeTotalVnd, items[], page, size,
totalElements, totalPages`. `activeEntries`/`activeTotalVnd` là số khoản và tổng tiền **chưa hủy** của mọi khoản khớp bộ
lọc (không chỉ trang hiện tại); `totalElements` đếm các khoản được liệt kê (gồm khoản đã hủy khi `includeVoided=true`).

Câu hỏi BA/PO: có cần chặn nhập trùng (cùng yêu cầu, loại, số tiền, ngày) hay cho phép và để HR tự hủy? Hiện backend cho
phép và lưu vết đầy đủ; frontend nên khóa nút Lưu trong lúc chờ phản hồi.

Ghi chú cho frontend: màn hình "Chi phí tuyển dụng" chỉ hiện nút Thêm/Hủy khi `/api/v1/auth/permissions` có
`REPORTS_WRITE_ALL` hoặc `REPORTS_WRITE_SCOPED`; khoản `voided = true` hiển thị gạch ngang kèm lý do; số tiền hiển thị
định dạng `5.000.000 ₫`.

## TKNHTTDNB1-574 — Thời gian tuyển và chi phí trên mỗi người

`GET /api/v1/reports/time-cost?from=2026-09-01&to=2026-09-30` (mặc định 90 ngày gần nhất; tối đa 366 ngày). Migration
`V67_2__index_hire_evidence.sql` thêm index cho hai tra cứu bên dưới.

Định nghĩa (bằng chứng thật, không dùng ngày dự kiến):

| Khái niệm | Cách tính |
| --- | --- |
| Một lần tuyển (hire) | lần **đầu tiên** hồ sơ chuyển vào `HIRED` ("Nhận việc") theo lịch sử `application_stage_events`, rơi vào kỳ theo ngày nghiệp vụ. Sự kiện này được ghi khi ứng viên chấp nhận offer (cổng offer) hoặc HR ghi nhận nhận việc. `offers.start_date` và `onboarding_checklists.start_date` là ngày **dự kiến** nên không bao giờ được dùng |
| `hiresWithAcceptedOffer` | trong số hire, hồ sơ có offer `ACCEPTED` (hoặc sự kiện HIRED đến từ câu trả lời offer) |
| `hiresOnboarded` | trong số hire, hồ sơ đã hoàn tất hội nhập (`ONBOARDED`) |
| Mốc duyệt yêu cầu | sự kiện `APPROVED` **đầu tiên làm yêu cầu thành `APPROVED`** (duyệt cấp trung gian giữ `PENDING_APPROVAL` nên không tính; `RESUMED` sau tạm dừng không phải là duyệt) |
| Time to hire của một hire | ngày nghiệp vụ của hire − ngày nghiệp vụ của mốc duyệt (giờ Việt Nam; 00:30 ngày 21 là ngày 21) |
| `averageDays` | trung bình cộng theo **từng hire** (có trọng số theo số hire, không lấy trung bình của các trung bình), 1 chữ số thập phân; `null` khi không có mẫu |
| `missingApproval` | hire mà yêu cầu không có sự kiện duyệt cuối (ví dụ dữ liệu nhập cũ): không đưa vào trung bình |
| `invalid` | hire có ngày trước mốc duyệt (dữ liệu mâu thuẫn): không đưa vào trung bình |
| Chi phí | các khoản **chưa hủy** của task 573 có `incurredOn` trong kỳ: `advertisingVnd`, `referralBonusVnd`, `totalVnd`, `entries` |
| `costsRecorded` | `true` khi kỳ có ít nhất một khoản chi phí chưa hủy trong nhóm |
| `costPerHireVnd` | `costs.totalVnd / hires`, làm tròn half-up tới đồng; `null` khi `hires = 0` (yêu cầu có chi phí nhưng chưa tuyển được ai vẫn hiện chi phí) và `null` khi `costsRecorded = false` (chưa nhập chi phí thì chi phí/người là **chưa biết**, không phải 0). Mỗi khoản ≥ 1 đồng nên chi phí đã nhập không bao giờ cộng ra 0 |

Chi phí và hire được đọc riêng rồi ghép theo yêu cầu tuyển dụng, nên một khoản chi phí không bao giờ bị nhân theo số
hire. Phạm vi như mọi báo cáo.

Response 200:

```json
{
  "generatedAt": "…", "from": "2026-09-01", "to": "2026-09-30", "scope": "ALL",
  "overall": {
    "hires": 4, "hiresWithAcceptedOffer": 3, "hiresOnboarded": 1,
    "timeToHire": {"sampleSize": 3, "averageDays": 30.0, "minDays": 10, "maxDays": 60, "missingApproval": 1, "invalid": 0},
    "costs": {"advertisingVnd": 5000000, "referralBonusVnd": 0, "totalVnd": 5000000, "entries": 1},
    "costsRecorded": true,
    "costPerHireVnd": 1250000
  },
  "requisitions": [
    {"requisitionId": "…", "departmentId": "…", "departmentName": "IT", "positionId": "…", "positionName": "Developer",
     "status": "APPROVED", "approvedOn": "2026-09-01", "metrics": {"…": "cùng cấu trúc"}}
  ]
}
```

`requisitions` gồm mọi yêu cầu trong phạm vi có hire hoặc chi phí trong kỳ, sắp theo tên phòng ban, tên chức danh, id.
Giới hạn: tối đa 50.000 hire và 5.000 yêu cầu trong một báo cáo, vượt quá trả 400 `REPORT_TOO_LARGE` (không cắt bớt âm
thầm).

Câu hỏi BA/PO: "ngày ứng viên nhận việc" được hiểu là ngày chấp nhận làm việc (stage HIRED, nhãn "Nhận việc"), không
phải ngày đi làm thực tế — hệ thống chưa lưu ngày đi làm thực tế. Nếu cần tính tới ngày đi làm, cần bổ sung dữ liệu đó.

Ghi chú cho frontend: hiển thị `averageDays = null` thành "Chưa có dữ liệu" và chú thích số `missingApproval`;
`costPerHireVnd = null` thành "—", với chú thích "chưa có người nhận việc" khi `hires = 0` hoặc "chưa nhập chi phí" khi
`costsRecorded = false`.

## TKNHTTDNB1-575 — API báo cáo so sánh thời gian và chi phí

`GET /api/v1/reports/time-cost?from=&to=&requisitionId=&departmentId=&positionId=`

Response của task 574 có thêm:

- `filters`: `{"requisitionId", "departmentId", "positionId"}` (giá trị `null` khi không lọc).
- `byDepartment`: một dòng cho mỗi phòng ban có hire hoặc chi phí trong kỳ (theo phòng ban của yêu cầu, không cộng dồn
  lên phòng ban cha).
- `byPosition`: một dòng cho mỗi chức danh có hire hoặc chi phí trong kỳ.

```json
"byDepartment": [
  {"id": "…", "name": "Kỹ thuật", "requisitions": 1,
   "metrics": {"hires": 2, "timeToHire": {"sampleSize": 2, "averageDays": 15.0, "…": "…"},
               "costs": {"totalVnd": 4000000, "…": "…"}, "costPerHireVnd": 2000000, "…": "…"}},
  {"id": "…", "name": "Tài chính", "requisitions": 2, "metrics": {"…": "…"}}
],
"byPosition": [{"id": "…", "name": "Kế toán", "requisitions": 1, "metrics": {"…": "…"}}]
```

- `requisitions` của một nhóm: số yêu cầu của nhóm có hire hoặc chi phí trong kỳ.
- `metrics` của nhóm tính lại từ **từng hire và từng khoản chi phí** của nhóm (không lấy trung bình của các yêu cầu):
  ví dụ chức danh có các hire 10, 20, 29 ngày có `averageDays = 19.7`.
- Sắp theo tên rồi id. Bộ lọc áp dụng cho mọi phần (`overall`, hai bảng so sánh, `requisitions`) và được kiểm tra
  404/403 như task 557.

Lỗi: 400 (`VALIDATION_ERROR`, `REPORT_TOO_LARGE`), 401, 403, 404 `REQUISITION_NOT_FOUND`/`DEPARTMENT_NOT_FOUND`/
`POSITION_NOT_FOUND`.

Ghi chú cho frontend: hai bảng so sánh có thể vẽ biểu đồ cột ngang `averageDays` và `costPerHireVnd` theo `name`; luôn
hiển thị kèm `metrics.hires` (cỡ mẫu) để người xem không so sánh nhóm chỉ có 1 hire với nhóm nhiều hire như nhau.

## TKNHTTDNB1-576 — Xuất Excel báo cáo thời gian và chi phí tuyển dụng

`GET /api/v1/reports/time-cost/export?from=&to=&requisitionId=&departmentId=&positionId=`

Cùng tham số, cùng quyền, cùng phạm vi và **cùng đối tượng báo cáo** với `GET /api/v1/reports/time-cost` (file được dựng
từ đúng response JSON đó, nên số liệu trên file và trên màn hình luôn khớp). Response 200:

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="bao-cao-thoi-gian-chi-phi_<from>_<to>.xlsx"`
- `Cache-Control: no-store`

Workbook (Apache POI, `.xlsx` thật):

| Sheet | Nội dung |
| --- | --- |
| `Bộ lọc` | từ ngày, đến ngày, phạm vi dữ liệu, các bộ lọc (id hoặc "Tất cả"), thời điểm lập, định nghĩa ngắn các chỉ số |
| `Tổng hợp` | một dòng chỉ số tổng |
| `Theo phòng ban` / `Theo chức danh` | mã, tên, số yêu cầu, rồi các cột chỉ số |
| `Theo yêu cầu` | mã yêu cầu, phòng ban, chức danh, trạng thái, ngày duyệt, rồi các cột chỉ số |

Các cột chỉ số: số người nhận việc, có offer được chấp nhận, đã hoàn tất hội nhập, số mẫu, thời gian tuyển TB (ngày, 1
chữ số thập phân), nhanh nhất, chậm nhất, thiếu mốc duyệt, ngày không hợp lệ, chi phí đăng tin, thưởng giới thiệu, tổng
chi phí (VND, định dạng `#,##0`), số khoản chi phí, chi phí mỗi người.

An toàn:

- Ô số là ô số (NUMERIC), ô chữ là ô chữ (STRING); **không bao giờ có ô công thức**.
- Chữ bắt đầu bằng `=`, `+`, `-`, `@`, tab hoặc xuống dòng (ví dụ tên phòng ban `=HYPERLINK(...)`) được đánh dấu
  quote-prefixed nên Excel hiển thị và giữ đúng nguyên văn, không chạy như công thức (chống CSV/formula injection).
- Giá trị không có (ví dụ chưa có người nhận việc nên không có chi phí/người) là **ô trống**, không ghi số 0 giả.
- Mỗi sheet tối đa 5.000 dòng dữ liệu; vượt quá trả 400 `REPORT_TOO_LARGE` thay vì cắt bớt âm thầm.
- File không chứa dữ liệu cá nhân của ứng viên (chỉ có nhóm và yêu cầu tuyển dụng).

Lỗi: như task 575 nhưng trả JSON lỗi (không phải file): 400, 401, 403, 404.

Ghi chú cho frontend: tải file bằng `fetch` kèm header `Authorization` rồi tạo `Blob` (không mở link trực tiếp vì cần
token); lấy tên file từ `Content-Disposition`; nếu response là JSON (status khác 200) thì hiển thị `message`.

## TKNHTTDNB1-580 — Tổng hợp CV và kết quả pipeline theo nguồn

`GET /api/v1/reports/sources?from=2026-07-01&to=2026-09-30` (mặc định 90 ngày gần nhất; tối đa 366 ngày).

Liên kết mỗi hồ sơ với **nguồn hiệu lực** và kết quả pipeline của nó:

1. Hồ sơ do nhân viên giới thiệu (có dòng `application_referrals`, API `POST /api/v1/referrals`) thuộc nguồn
   "Giới thiệu nội bộ" (`kind = INTERNAL_REFERRAL`, `sourceKey = code = INTERNAL_REFERRAL`) — giống nhãn trên hồ sơ
   ứng viên hợp nhất; hồ sơ giới thiệu được lưu không có nguồn danh mục.
2. Nếu không, nguồn danh mục lúc nhận hồ sơ (`job_applications.source_id`, danh mục `CANDIDATE_SOURCE`;
   `kind = CATALOG`, `sourceKey` = id nguồn).
3. Còn lại: "Không rõ nguồn" (`kind = UNKNOWN`, `sourceKey = UNKNOWN`). Cohort và "giai đoạn xa nhất đã đạt" dùng đúng quy tắc của phễu (task 567, `ApplicationReach`), nên tổng
của báo cáo nguồn luôn khớp với phễu cùng kỳ và cùng bộ lọc.

Response 200:

```json
{
  "generatedAt": "…", "from": "2026-07-01", "to": "2026-09-30", "scope": "ALL", "cohortSize": 7,
  "sources": [
    {"sourceId": "…", "sourceKey": "…", "kind": "CATALOG", "code": "…", "name": "Website", "active": true,
     "applications": 3, "passedScreening": 2, "hired": 1, "rejected": 1, "withdrawn": 0, "inProgress": 1},
    {"sourceId": null, "sourceKey": "INTERNAL_REFERRAL", "kind": "INTERNAL_REFERRAL", "code": "INTERNAL_REFERRAL",
     "name": "Giới thiệu nội bộ", "active": null, "applications": 2, "passedScreening": 1, "hired": 1, "rejected": 0,
     "withdrawn": 0, "inProgress": 1},
    {"sourceId": null, "sourceKey": "UNKNOWN", "kind": "UNKNOWN", "code": null, "name": "Không rõ nguồn", "active": null,
     "applications": 2, "passedScreening": 0, "hired": 0, "rejected": 0, "withdrawn": 0, "inProgress": 2}
  ]
}
```

| Trường | Định nghĩa |
| --- | --- |
| `applications` | số hồ sơ (lần ứng tuyển, = số CV) của nguồn trong cohort |
| `passedScreening` | hồ sơ đã đạt ít nhất `INTERVIEW` (qua sàng lọc), kể cả được HR cho bỏ qua phỏng vấn và đi thẳng tới offer |
| `hired` | hồ sơ đã đạt `HIRED` (gồm cả `ONBOARDED`), dù sau đó trạng thái hiện tại là gì |
| `rejected`, `withdrawn` | hồ sơ hiện đang `REJECTED` / `WITHDRAWN` |
| `inProgress` | hồ sơ hiện còn ở `NEW`..`OFFER` |
| `rejected` | gồm cả hồ sơ ứng viên từ chối offer (phễu tách riêng thành `declined`) |
| `active` | nguồn danh mục còn đang dùng; nguồn bị ngừng dùng sau này vẫn giữ lịch sử của mình; `null` cho "Giới thiệu nội bộ" và "Không rõ nguồn" |

Hồ sơ không có nguồn được gom vào một dòng "Không rõ nguồn". Danh sách sắp theo số hồ sơ giảm dần rồi
theo tên (task 581 đổi sang sắp theo chất lượng). Phạm vi như mọi báo cáo; người xem không có hồ sơ nào trong phạm vi nhận
`sources: []`.

Ghi chú cho frontend: dùng `sourceKey` làm khóa dòng (ổn định cho cả ba loại); dòng `kind = UNKNOWN` nên hiển thị
nghiêng/xám để phân biệt với nguồn thật.

## TKNHTTDNB1-581 — API chất lượng nguồn tuyển dụng

`GET /api/v1/reports/sources?from=&to=&requisitionId=&departmentId=&positionId=&minApplications=5`

Response của task 580 có thêm:

- `filters` (`requisitionId`, `departmentId`, `positionId`; kiểm tra 404/403 như task 557).
- `minApplications`: cỡ mẫu tối thiểu để một nguồn được xếp hạng (1–1000, mặc định 5; sai → 400 `VALIDATION_ERROR`).
- `rankingRule`: `HIRE_RATE_THEN_SCREENING_PASS_RATE_THEN_APPLICATIONS_MIN_SAMPLE`.
- `bestSource`: nguồn xếp hạng 1 (cùng cấu trúc một dòng `sources`), `null` khi không nguồn nào đủ điều kiện.
- `mostApplications`: nguồn thật (danh mục hoặc giới thiệu nội bộ) có nhiều hồ sơ nhất, để đối chiếu "nhiều nhất" với
  "tốt nhất".
- Mỗi dòng `sources` có thêm `screeningPassRate`, `hireRate`, `meetsMinimumSample`, `qualityRank`.

| Trường | Định nghĩa |
| --- | --- |
| `screeningPassRate` | `passedScreening / applications` (4 chữ số thập phân; `null` khi không có hồ sơ) |
| `hireRate` | `hired / applications` |
| `meetsMinimumSample` | `applications ≥ minApplications` |
| `qualityRank` | 1, 2, … cho nguồn thật (nguồn danh mục và "Giới thiệu nội bộ", không bao giờ "Không rõ nguồn") đủ cỡ mẫu, xếp theo `hireRate` giảm dần, rồi `screeningPassRate` giảm dần (so sánh chính xác bằng phân số, không làm tròn), rồi nhiều hồ sơ hơn, rồi tên; `null` cho nguồn chưa đủ mẫu và cho "Không rõ nguồn" |

Thứ tự `sources`: các nguồn có hạng theo hạng, sau đó các nguồn còn lại theo số hồ sơ giảm dần rồi theo tên. Như vậy nguồn
nhiều CV nhưng ít người được tuyển không đứng đầu chỉ vì số lượng, còn nguồn chỉ có 1–2 hồ sơ (tỷ lệ 100% do may mắn)
được hiện nhưng không được xếp hạng.

Câu hỏi BA/PO: ngưỡng cỡ mẫu mặc định 5 hồ sơ và thứ tự ưu tiên (tỷ lệ nhận việc trước tỷ lệ qua sàng lọc) là lựa chọn
mặc định hợp lý; nếu nghiệp vụ muốn tiêu chí khác (ví dụ tỷ lệ nhận việc trên số qua sàng lọc), backend chỉ cần đổi
`SourceRanking`.

Ghi chú cho frontend: hiển thị huy hiệu "Chất lượng tốt nhất" cho `bestSource` và "Nhiều hồ sơ nhất" cho
`mostApplications`; dòng có `meetsMinimumSample = false` ghi chú "Chưa đủ mẫu (cần ≥ minApplications hồ sơ)"; tỷ lệ hiển
thị dạng phần trăm (`0.4000` → 40,00%).

## TKNHTTDNB1-582 — Xuất Excel báo cáo chất lượng nguồn

`GET /api/v1/reports/sources/export?from=&to=&requisitionId=&departmentId=&positionId=&minApplications=`

Cùng tham số, quyền, phạm vi và **cùng đối tượng báo cáo** với `GET /api/v1/reports/sources` (task 581). Response 200:
`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
`Content-Disposition: attachment; filename="bao-cao-chat-luong-nguon_<from>_<to>.xlsx"`, `Cache-Control: no-store`.

| Sheet | Nội dung |
| --- | --- |
| `Bộ lọc` | khoảng ngày, phạm vi, bộ lọc, cỡ mẫu tối thiểu, tổng số hồ sơ, nguồn chất lượng nhất ("Chưa có nguồn đủ mẫu" khi không có), nguồn nhiều hồ sơ nhất, cách xếp hạng, thời điểm lập |
| `Theo nguồn` | hạng chất lượng, nguồn, mã, đang dùng, số hồ sơ, qua sàng lọc, tỷ lệ qua sàng lọc, nhận việc, tỷ lệ nhận việc, bị loại, rút hồ sơ, đang xử lý, đủ cỡ mẫu — đúng thứ tự dòng của JSON |

Xử lý dữ liệu thiếu và mẫu số bằng 0:

- Dòng "Không rõ nguồn" luôn có tên rõ ràng; ô "Mã nguồn" và "Đang dùng" để trống vì không có nguồn trong danh mục.
  Dòng "Giới thiệu nội bộ" có mã `INTERNAL_REFERRAL` và ô "Đang dùng" để trống, được xếp hạng như nguồn danh mục.
- Nguồn không được xếp hạng (chưa đủ mẫu, hoặc "Không rõ nguồn"): ô hạng để trống.
- Tỷ lệ có mẫu số bằng 0 (`null` trong JSON) là ô trống, không phải 0%; tỷ lệ có giá trị là ô số định dạng `0.00%`.
- Ô số là số, ô chữ là chữ, không có ô công thức; tên nguồn như `=1+1` được giữ nguyên văn (quote-prefixed); tối đa
  5.000 dòng mỗi sheet (vượt quá → 400 `REPORT_TOO_LARGE`). File không chứa dữ liệu cá nhân ứng viên.

Lỗi (JSON): 400 (`VALIDATION_ERROR` cho ngày/`minApplications`/UUID sai), 401, 403, 404.

Ghi chú cho frontend: dùng chung cách tải file với task 576; gửi đúng các tham số đang hiển thị trên màn hình để file
khớp với bảng.
