# Chuyển giai đoạn hồ sơ ứng tuyển

Phạm vi TKNHTTDNB1-380 (API chuyển giai đoạn), story S5-03 (TKNHTTDNB1-51). Tiêu chí của story:

- Chuyển sang Loại bắt buộc chọn lý do từ danh mục.
- Mỗi lần chuyển giai đoạn được ghi nhật ký kèm người thực hiện và thời điểm.
- Không cho phép bỏ qua giai đoạn Phỏng vấn khi đi thẳng tới Đề xuất offer, trừ khi Trưởng phòng Nhân sự xác nhận.

Task 380 thêm API và quy tắc chuyển tiến từng bước, không thêm mã quyền, không migration (dùng `job_applications.stage` của V26 và lịch sử `application_stage_events` của V34). Task 381 (TKNHTTDNB1-381) thêm chuyển sang Loại với lý do từ danh mục và ngoại lệ bỏ qua Phỏng vấn của Trưởng phòng Nhân sự, kèm migration V40. Task 382 (TKNHTTDNB1-382) thêm ghi chú lý do cho mọi lần chuyển và chốt nội dung lịch sử (trước/sau, lý do, người xử lý, thời gian, ngoại lệ HR), không thêm migration.

## API

`POST /api/v1/applications/{id}/stage-changes` (Bearer). `{id}` là id **lần ứng tuyển** (`applications[].id` của hồ sơ hợp nhất). POST như các API hành động khác (CORS không cho PATCH). Mọi response, kể cả lỗi, có `Cache-Control: no-store`.

```json
{"targetStage": "SCREENING", "expectedCurrentStage": "NEW"}
```

| Trường | Quy tắc |
|---|---|
| `targetStage` | Bắt buộc. Tên giai đoạn đúng như enum (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `ONBOARDED`, `REJECTED`, `WITHDRAWN`), viết hoa. ONBOARDED chỉ được chuyển qua checklist tiếp nhận, API này từ chối |
| `expectedCurrentStage` | Bắt buộc. Giai đoạn trang của người gọi đang hiển thị (cột mà thẻ được kéo ra). Khác giai đoạn thật lúc khóa thì bị từ chối 409, không ghi gì |
| `rejectionReasonId` | Task 381. UUID của một giá trị danh mục `REJECTION_REASON` đang dùng. **Bắt buộc** khi `targetStage` là `REJECTED`, **không được gửi** với giai đoạn khác |
| `confirmSkipInterview` | Task 381. Trưởng phòng Nhân sự xác nhận chuyển thẳng `SCREENING → OFFER`. Phải là giá trị JSON `true` (chuỗi `"true"` hay số `1` bị từ chối 400 `INVALID_JSON`); vắng, `null`, `false` là chưa xác nhận. Bị bỏ qua khi bước chuyển không bỏ qua giai đoạn nào |
| `note` | Task 382. Không bắt buộc, cho mọi lần chuyển: lý do của người chuyển (ví dụ giải trình khi bỏ qua Phỏng vấn, bổ sung cho lý do loại). Văn bản thuần, cho phép xuống dòng, tối đa 500 ký tự; xuống dòng Windows thành `\n`; chỉ có khoảng trắng hay ký tự vô hình thì coi như không có. Nội bộ: không bao giờ hiện cho ứng viên |

Trường khác (`actorId`, `changedAt`...) bị từ chối 400 `INVALID_JSON`: người thực hiện, thời điểm và giai đoạn trước do server quyết định.

```json
{"targetStage": "REJECTED", "expectedCurrentStage": "INTERVIEW", "rejectionReasonId": "6f1c…"}
{"targetStage": "OFFER", "expectedCurrentStage": "SCREENING", "confirmSkipInterview": true}
```

Response 200 chính là dòng lịch sử vừa ghi:

```json
{"applicationId":"a1b2…","previousStage":"NEW","previousStageName":"Hồ sơ mới","stage":"SCREENING",
 "stageName":"Sàng lọc","changedAt":"2026-10-17T03:00:00.123456Z","changedById":"…","changedByName":"Trần Văn Bình"}
```

Từ task 381 response có thêm `interviewSkipped` (luôn có; `true` chỉ với ngoại lệ HR `SCREENING → OFFER`) và, chỉ khi chuyển sang Loại, `rejectionReasonId` cùng `rejectionReasonName` (tên giá trị danh mục lúc loại); từ task 382 có `note` như đã lưu (bỏ khi không có). Response chỉ có id, giai đoạn, lý do và họ tên người thực hiện; không có dữ liệu ứng viên.

## Quy tắc chuyển (task 380)

Chỉ tiến **một bước** theo thứ tự của story: Hồ sơ mới → Sàng lọc → Phỏng vấn → Đề xuất offer → Nhận việc (`NEW → SCREENING → INTERVIEW → OFFER → HIRED`).

- Không lùi giai đoạn, không về `NEW`, không đứng yên (`targetStage` bằng giai đoạn hiện tại): 409.
- `HIRED`, `ONBOARDED`, `REJECTED`, `WITHDRAWN` không có bước chuyển qua API chung này. HIRED → ONBOARDED chỉ xảy ra khi hoàn tất [checklist tiếp nhận](onboarding.md).
- Nhân viên không chuyển sang `WITHDRAWN`: chỉ ứng viên tự rút (task 345).
- Trạng thái của yêu cầu tuyển dụng không ảnh hưởng: ứng viên còn lại của yêu cầu đã tuyển đủ hoặc đã hủy (task 291) vẫn được chuyển tiếp. Chuyển sang `HIRED` **không** tự đóng hay tự đánh dấu yêu cầu đã tuyển đủ.

Quy tắc nằm ở một chỗ, `ApplicationStageTransitions.decide` (gói `vn.ttcs.recruitment.pipeline`, public để bảng Kanban hiển thị cột đích bằng đúng quy tắc này); thao tác kéo thả trên bảng Kanban phải gọi chính API này.

## Loại và ngoại lệ Trưởng phòng Nhân sự (task 381)

**Loại.** Được chuyển sang `REJECTED` từ mọi giai đoạn đang xử lý (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`), không từ giai đoạn kết thúc. Bắt buộc chọn một giá trị danh mục `REJECTION_REASON` **đang dùng** (`active = true`); Trưởng phòng Nhân sự khai báo các giá trị qua [API danh mục](recruitment-catalogs.md) (V10 không tạo sẵn). Giá trị được khóa `FOR SHARE` tới khi lưu xong, nên HR không đổi tên, ngừng dùng hay xóa được nó giữa lúc kiểm và lúc lưu; nếu HR đang sửa giá trị đó, lần chuyển chờ rồi thấy trạng thái HR vừa lưu. Lịch sử lưu id giá trị (khóa ngoại RESTRICT: giá trị đã được dùng để loại chỉ ngừng dùng được, xóa trả 409 `RECRUITMENT_CATALOG_ITEM_IN_USE`) và **tên lúc loại**: đổi tên giá trị sau đó không đổi lịch sử.

**Bỏ qua Phỏng vấn.** `SCREENING → OFFER` chỉ được khi người gọi **đang có vai trò** `HR_MANAGER` (đọc từ tài khoản đã khóa; ADMIN hay quyền `CANDIDATES_WRITE_ALL` không đủ), vẫn có quyền và phạm vi bình thường với lần ứng tuyển đó, và gửi `confirmSkipInterview: true`. Không bỏ qua giai đoạn nào khác, kể cả HR (`NEW → OFFER` bỏ qua cả Sàng lọc: 409 `APPLICATION_STAGE_TRANSITION_NOT_ALLOWED`). Dòng lịch sử của mọi lần `SCREENING → OFFER` có `interview_skipped = true` (CHECK của V40 bắt buộc), mọi dòng khác là `false`.

| Người gọi, `SCREENING → OFFER` | Kết quả |
|---|---|
| Không có vai trò HR_MANAGER (recruiter, ADMIN) | 409 `APPLICATION_INTERVIEW_REQUIRED`, dù có gửi xác nhận |
| HR_MANAGER không gửi `confirmSkipInterview: true` | 409 `APPLICATION_INTERVIEW_SKIP_UNCONFIRMED`: frontend hỏi xác nhận rồi gửi lại |
| HR_MANAGER gửi `confirmSkipInterview: true` | 200, `interviewSkipped: true` |

## Ai được chuyển

| Quyền (đọc lại từ database mỗi lần) | Vai trò trong seed | Được chuyển |
|---|---|---|
| `CANDIDATES_WRITE_ALL` và `CANDIDATES_READ_ALL` | HR_MANAGER, ADMIN | mọi lần ứng tuyển |
| `CANDIDATES_WRITE_SCOPED` và `CANDIDATES_READ_SCOPED` | RECRUITER | lần ứng tuyển của yêu cầu mà người gọi **đang** là recruiter chính hoặc hỗ trợ (`RecruiterAssignmentAccess.isAssigned`, kiểm khi giữ khóa yêu cầu) |
| chỉ quyền đọc | HIRING_MANAGER, INTERVIEWER, APPROVER | không (403) |

- Cần **cả** quyền ghi lẫn quyền đọc (`WRITE` không bao gồm `READ`, V3), và lần ứng tuyển phải nằm trong phạm vi đọc `CandidateAccess.READABLE` lẫn phạm vi ghi. Trưởng phòng ban chỉ đọc, không chuyển giai đoạn.
- Lịch sử phân công không được tính: sau khi bàn giao, recruiter cũ nhận 403 ngay ở request kế tiếp.

## Thứ tự kiểm, khóa và lỗi

1. Body (400 `VALIDATION_ERROR`/`INVALID_JSON`), rồi (task 381) lý do loại thiếu hoặc thừa (400 `REJECTION_REASON_REQUIRED`/`REJECTION_REASON_NOT_ALLOWED`), trước mọi truy cập database.
2. Một transaction riêng READ COMMITTED, tối đa 5 giây, `lock_timeout` 2 giây, `statement_timeout` 3 giây. Khóa theo thứ tự chung của dự án: tài khoản người gọi, phiên (kiểm token, phiên, hai quyền: 401/403 trước mọi tra cứu) → yêu cầu tuyển dụng `FOR NO KEY UPDATE` (khóa của mọi lần đóng yêu cầu, đổi nhóm recruiter và rút hồ sơ) → lần ứng tuyển `FOR NO KEY UPDATE`.
3. Sau **mỗi** lần chờ khóa, kiểm lại token, phiên, quyền **và phạm vi của chính lần ứng tuyển** bằng quyền vừa đọc lại. Quyền bị thu hẹp từ `ALL` sang `SCOPED` trong lúc chờ thì lần ứng tuyển ngoài phạm vi mới bị từ chối 403.
4. Giai đoạn đọc dưới khóa khác `expectedCurrentStage`: 409 `APPLICATION_STAGE_CHANGED`; sau đó quy tắc chuyển: 409 `APPLICATION_STAGE_TRANSITION_NOT_ALLOWED`, `APPLICATION_INTERVIEW_REQUIRED` hoặc `APPLICATION_INTERVIEW_SKIP_UNCONFIRMED`.
5. Task 381, khi loại: khóa giá trị lý do `FOR SHARE` (sau lần ứng tuyển; việc ghi danh mục không bao giờ khóa hồ sơ ứng tuyển nên không có vòng chờ), kiểm lại người gọi và phạm vi, rồi 400 `INVALID_REJECTION_REASON` (không có, hoặc thuộc danh mục khác) hay 400 `REJECTION_REASON_INACTIVE`.
6. Đọc đồng hồ sau khóa cuối; đổi giai đoạn (chỉ từ giai đoạn đã khóa) và ghi một dòng lịch sử `STAFF` trong cùng transaction.

| HTTP | Mã | Trường hợp |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu trường, tên giai đoạn sai (kể cả viết thường, `INTERVIEWING` cũ của frontend); `{id}` không phải UUID |
| 400 | `INVALID_JSON` | JSON sai hoặc có trường ngoài hợp đồng |
| 401 | `UNAUTHORIZED`/`SESSION_INVALID` | Thiếu, sai, hết hạn token; phiên bị thu hồi; tài khoản bị khóa |
| 403 | `FORBIDDEN` | Không có quyền ghi hoặc đọc ứng viên (với mọi id); lần ứng tuyển có thật nhưng ngoài phạm vi |
| 400 | `REJECTION_REASON_REQUIRED`, `REJECTION_REASON_NOT_ALLOWED` | Task 381: loại mà không có `rejectionReasonId`, hoặc gửi lý do với giai đoạn khác; `fieldErrors.rejectionReasonId` |
| 400 | `INVALID_REJECTION_REASON`, `REJECTION_REASON_INACTIVE` | Task 381: lý do không phải giá trị `REJECTION_REASON` có thật, hoặc đã ngừng dùng; `fieldErrors.rejectionReasonId` |
| 404 | `APPLICATION_NOT_FOUND` | Lần ứng tuyển không tồn tại (kiểm trước phạm vi) |
| 409 | `APPLICATION_STAGE_CHANGED` | Giai đoạn đã đổi so với trang đang xem (người khác vừa chuyển, ứng viên vừa rút): tải lại |
| 409 | `APPLICATION_STAGE_TRANSITION_NOT_ALLOWED` | Bước chuyển không được phép từ giai đoạn hiện tại |
| 409 | `APPLICATION_INTERVIEW_REQUIRED`, `APPLICATION_INTERVIEW_SKIP_UNCONFIRMED` | Task 381: `SCREENING → OFFER` của người không phải HR_MANAGER, hoặc của HR_MANAGER chưa xác nhận |
| 503 | `APPLICATION_STAGE_BUSY` | Hết thời gian chờ khóa hoặc câu lệnh; không lưu gì, gửi lại được |

Bị từ chối thì không ghi gì. Hai lần chuyển cùng lúc (kéo thả hai lần) chạy lần lượt trên khóa yêu cầu: lần sau thấy giai đoạn mới và nhận 409 `APPLICATION_STAGE_CHANGED`, chỉ có một dòng lịch sử. Rút hồ sơ và chuyển giai đoạn của cùng lần ứng tuyển cũng chạy lần lượt: bên sau thấy giai đoạn bên trước đã lưu.

## Lịch sử (task 382, V34 và V40)

Mỗi lần chuyển thêm đúng một dòng `application_stage_events`, ghi cùng transaction với giai đoạn (dòng lịch sử lỗi thì giai đoạn giữ nguyên, và ngược lại):

| Tiêu chí 382 | Cột |
|---|---|
| Trước/sau | `stage_before`, `stage_after` |
| Lý do | `rejection_reason_id` và `rejection_reason_name` (lý do loại theo danh mục, tên lúc loại), `reason` (ghi chú `note` của nhân viên; ở dòng `CANDIDATE` là lý do ứng viên tự ghi khi rút) |
| Người xử lý | `actor_type = 'STAFF'`, `actor_id` (người gọi, lấy từ token, không từ body) |
| Thời gian | `occurred_at`, đồng hồ đọc sau khóa cuối, tới micro giây |
| Ngoại lệ HR | `interview_skipped = true` (chỉ và luôn ở `SCREENING → OFFER`) cùng `actor_id` là Trưởng phòng Nhân sự đã xác nhận |

Dòng đã lưu không sửa được (trigger V34, SQLSTATE 23514), kể cả ghi chú hay cờ ngoại lệ; không có route sửa hay xóa (`ApiAuthorizationMatrixIntegrationTest.stageHistoryHasNoEditOrDeleteRoute`). Bị từ chối (400/403/404/409/503) thì không ghi dòng nào. API [dòng thời gian nội bộ](application-timeline.md) đọc lịch sử này qua `GET /api/v1/applications/{id}/timeline`, kiểm tra quyền bằng `CandidateAccess` và ghi nhật ký truy cập `PROFILE`. Hồ sơ hợp nhất vẫn chỉ đọc dòng `WITHDRAWN` (của ứng viên) cho `withdrawnAt`/`withdrawalReason`, nên ghi chú của nhân viên không bao giờ hiện thành lý do rút.

V40 (task 381) thêm vào dòng lịch sử: `rejection_reason_id` (khóa ngoại RESTRICT tới `recruitment_catalog_items`, chỉ mục `application_stage_events_rejection_reason_idx`) và `rejection_reason_name` (tên lúc loại), cả hai có đúng khi nhân viên chuyển sang `REJECTED` (CHECK `application_stage_events_rejection_reason`); `interview_skipped` bằng `true` đúng với mọi dòng `SCREENING → OFFER` (CHECK `application_stage_events_interview_skip`). Dòng cũ (ứng viên tự rút) không đổi: không có lý do loại, `interview_skipped = false`. Loại danh mục của lý do được service kiểm dưới khóa `FOR SHARE` (khóa ngoại thường không ràng buộc được loại).

## Phía ứng viên

API công khai (tra cứu, rút hồ sơ, danh sách giới thiệu) vẫn chỉ trả trạng thái công khai `PublicApplicationStatus` (`RECEIVED`, `IN_REVIEW`, `INTERVIEWING`, `COMPLETED`); không trả giai đoạn nội bộ, người thực hiện, lý do loại, ghi chú hay lịch sử (`PublicApplicationReader` chỉ đọc `job_applications`, `PublicApplicationPrivacyIntegrationTest` khóa mọi bảng khác khi tra cứu). Chuyển giai đoạn không gửi email; email báo rút hồ sơ chỉ dựng từ dòng `WITHDRAWN` của ứng viên.

## Ghi chú cho frontend

- Kéo thả trên bảng Kanban gọi đúng API này với `expectedCurrentStage` là cột nguồn. 409 `APPLICATION_STAGE_CHANGED`: tải lại cột, không tự thử bước khác.
- Chuyển sang Loại: mở hộp chọn lý do từ `GET /recruitment-catalogs/REJECTION_REASON/items?active=true`, gửi `rejectionReasonId`.
- `SCREENING → OFFER`: chỉ hiện cho người dùng có vai trò HR_MANAGER; khi nhận 409 `APPLICATION_INTERVIEW_SKIP_UNCONFIRMED`, hỏi xác nhận rồi gửi lại với `confirmSkipInterview: true` (có thể kèm `note` giải trình).
- Hiện `note` và tên lý do dưới dạng văn bản thuần, giữ xuống dòng, không render HTML.

## Ví dụ (PowerShell)

```powershell
$body = @{ email = 'recruiter@example.test'; password = '<mật khẩu>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$change = @{ targetStage = 'SCREENING'; expectedCurrentStage = 'NEW' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/applications/<id>/stage-changes' -Headers $headers -ContentType 'application/json' -Body $change
```
