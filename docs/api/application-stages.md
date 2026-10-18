# Chuyển giai đoạn hồ sơ ứng tuyển

Phạm vi TKNHTTDNB1-380 (API chuyển giai đoạn), story S5-03 (TKNHTTDNB1-51). Tiêu chí của story:

- Chuyển sang Loại bắt buộc chọn lý do từ danh mục.
- Mỗi lần chuyển giai đoạn được ghi nhật ký kèm người thực hiện và thời điểm.
- Không cho phép bỏ qua giai đoạn Phỏng vấn khi đi thẳng tới Đề xuất offer, trừ khi Trưởng phòng Nhân sự xác nhận.

Task 380 thêm API và quy tắc chuyển tiến từng bước. Không thêm mã quyền, không migration (dùng `job_applications.stage` của V26 và lịch sử `application_stage_events` của V34).

## API

`POST /api/v1/applications/{id}/stage-changes` (Bearer). `{id}` là id **lần ứng tuyển** (`applications[].id` của hồ sơ hợp nhất). POST như các API hành động khác (CORS không cho PATCH). Mọi response, kể cả lỗi, có `Cache-Control: no-store`.

```json
{"targetStage": "SCREENING", "expectedCurrentStage": "NEW"}
```

| Trường | Quy tắc |
|---|---|
| `targetStage` | Bắt buộc. Tên giai đoạn đúng như enum (`NEW`, `SCREENING`, `INTERVIEW`, `OFFER`, `HIRED`, `REJECTED`, `WITHDRAWN`), viết hoa |
| `expectedCurrentStage` | Bắt buộc. Giai đoạn trang của người gọi đang hiển thị (cột mà thẻ được kéo ra). Khác giai đoạn thật lúc khóa thì bị từ chối 409, không ghi gì |

Trường khác (`actorId`, `changedAt`...) bị từ chối 400 `INVALID_JSON`: người thực hiện, thời điểm và giai đoạn trước do server quyết định.

Response 200 chính là dòng lịch sử vừa ghi:

```json
{"applicationId":"a1b2…","previousStage":"NEW","previousStageName":"Hồ sơ mới","stage":"SCREENING",
 "stageName":"Sàng lọc","changedAt":"2026-10-17T03:00:00.123456Z","changedById":"…","changedByName":"Trần Văn Bình"}
```

Response chỉ có id, giai đoạn và họ tên người thực hiện; không có dữ liệu ứng viên.

## Quy tắc chuyển (task 380)

Chỉ tiến **một bước** theo thứ tự của story: Hồ sơ mới → Sàng lọc → Phỏng vấn → Đề xuất offer → Nhận việc (`NEW → SCREENING → INTERVIEW → OFFER → HIRED`).

- Không lùi giai đoạn, không về `NEW`, không đứng yên (`targetStage` bằng giai đoạn hiện tại): 409.
- `HIRED`, `REJECTED`, `WITHDRAWN` là kết thúc trong phiên bản này: không mở lại.
- Nhân viên không chuyển sang `WITHDRAWN`: chỉ ứng viên tự rút (task 345).
- Trạng thái của yêu cầu tuyển dụng không ảnh hưởng: ứng viên còn lại của yêu cầu đã tuyển đủ hoặc đã hủy (task 291) vẫn được chuyển tiếp. Chuyển sang `HIRED` **không** tự đóng hay tự đánh dấu yêu cầu đã tuyển đủ.

Quy tắc nằm ở một chỗ, `ApplicationStageTransitions` (gói `vn.ttcs.recruitment.pipeline`); thao tác kéo thả trên bảng Kanban phải gọi chính API này.

## Ai được chuyển

| Quyền (đọc lại từ database mỗi lần) | Vai trò trong seed | Được chuyển |
|---|---|---|
| `CANDIDATES_WRITE_ALL` và `CANDIDATES_READ_ALL` | HR_MANAGER, ADMIN | mọi lần ứng tuyển |
| `CANDIDATES_WRITE_SCOPED` và `CANDIDATES_READ_SCOPED` | RECRUITER | lần ứng tuyển của yêu cầu mà người gọi **đang** là recruiter chính hoặc hỗ trợ (`RecruiterAssignmentAccess.isAssigned`, kiểm khi giữ khóa yêu cầu) |
| chỉ quyền đọc | HIRING_MANAGER, INTERVIEWER, APPROVER | không (403) |

- Cần **cả** quyền ghi lẫn quyền đọc (`WRITE` không bao gồm `READ`, V3), và lần ứng tuyển phải nằm trong phạm vi đọc `CandidateAccess.READABLE` lẫn phạm vi ghi. Trưởng phòng ban chỉ đọc, không chuyển giai đoạn.
- Lịch sử phân công không được tính: sau khi bàn giao, recruiter cũ nhận 403 ngay ở request kế tiếp.

## Thứ tự kiểm, khóa và lỗi

1. Body (400 `VALIDATION_ERROR`/`INVALID_JSON`), trước mọi truy cập database.
2. Một transaction riêng READ COMMITTED, tối đa 5 giây, `lock_timeout` 2 giây, `statement_timeout` 3 giây. Khóa theo thứ tự chung của dự án: tài khoản người gọi, phiên (kiểm token, phiên, hai quyền: 401/403 trước mọi tra cứu) → yêu cầu tuyển dụng `FOR NO KEY UPDATE` (khóa của mọi lần đóng yêu cầu, đổi nhóm recruiter và rút hồ sơ) → lần ứng tuyển `FOR NO KEY UPDATE`.
3. Sau **mỗi** lần chờ khóa, kiểm lại token, phiên, quyền **và phạm vi của chính lần ứng tuyển** bằng quyền vừa đọc lại. Quyền bị thu hẹp từ `ALL` sang `SCOPED` trong lúc chờ thì lần ứng tuyển ngoài phạm vi mới bị từ chối 403.
4. Giai đoạn đọc dưới khóa khác `expectedCurrentStage`: 409 `APPLICATION_STAGE_CHANGED`; sau đó quy tắc chuyển: 409 `APPLICATION_STAGE_TRANSITION_NOT_ALLOWED`.
5. Đọc đồng hồ sau khóa cuối; đổi giai đoạn (chỉ từ giai đoạn đã khóa) và ghi một dòng lịch sử `STAFF` trong cùng transaction.

| HTTP | Mã | Trường hợp |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu trường, tên giai đoạn sai (kể cả viết thường, `INTERVIEWING` cũ của frontend); `{id}` không phải UUID |
| 400 | `INVALID_JSON` | JSON sai hoặc có trường ngoài hợp đồng |
| 401 | `UNAUTHORIZED`/`SESSION_INVALID` | Thiếu, sai, hết hạn token; phiên bị thu hồi; tài khoản bị khóa |
| 403 | `FORBIDDEN` | Không có quyền ghi hoặc đọc ứng viên (với mọi id); lần ứng tuyển có thật nhưng ngoài phạm vi |
| 404 | `APPLICATION_NOT_FOUND` | Lần ứng tuyển không tồn tại (kiểm trước phạm vi) |
| 409 | `APPLICATION_STAGE_CHANGED` | Giai đoạn đã đổi so với trang đang xem (người khác vừa chuyển, ứng viên vừa rút): tải lại |
| 409 | `APPLICATION_STAGE_TRANSITION_NOT_ALLOWED` | Bước chuyển không được phép từ giai đoạn hiện tại |
| 503 | `APPLICATION_STAGE_BUSY` | Hết thời gian chờ khóa hoặc câu lệnh; không lưu gì, gửi lại được |

Bị từ chối thì không ghi gì. Hai lần chuyển cùng lúc (kéo thả hai lần) chạy lần lượt trên khóa yêu cầu: lần sau thấy giai đoạn mới và nhận 409 `APPLICATION_STAGE_CHANGED`, chỉ có một dòng lịch sử. Rút hồ sơ và chuyển giai đoạn của cùng lần ứng tuyển cũng chạy lần lượt: bên sau thấy giai đoạn bên trước đã lưu.

## Lịch sử (V34)

Mỗi lần chuyển thêm đúng một dòng `application_stage_events`: `stage_before`, `stage_after`, `actor_type = 'STAFF'`, `actor_id` (người gọi), `occurred_at`. Giai đoạn và dòng lịch sử commit cùng nhau: dòng lịch sử lỗi thì giai đoạn giữ nguyên. Dòng đã lưu không sửa được (trigger V34); không có route sửa hay xóa.

## Phía ứng viên

API công khai (tra cứu, rút hồ sơ, danh sách giới thiệu) vẫn chỉ trả trạng thái công khai `PublicApplicationStatus` (`RECEIVED`, `IN_REVIEW`, `INTERVIEWING`, `COMPLETED`); không trả giai đoạn nội bộ, người thực hiện hay lịch sử. Chuyển giai đoạn không gửi email.

## Ví dụ (PowerShell)

```powershell
$body = @{ email = 'recruiter@example.test'; password = '<mật khẩu>' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$change = @{ targetStage = 'SCREENING'; expectedCurrentStage = 'NEW' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/applications/<id>/stage-changes' -Headers $headers -ContentType 'application/json' -Body $change
```
