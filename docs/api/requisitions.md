# API yêu cầu tuyển dụng

Phạm vi TKNHTTDNB1-244 (tạo và lưu nháp), TKNHTTDNB1-245 (xem và cập nhật bản nháp), TKNHTTDNB1-246 (kiểm tra trường bắt buộc và lý do tuyển), TKNHTTDNB1-247 (bắt buộc giải trình khi dải lương đề xuất ngoài chuẩn), TKNHTTDNB1-248 (ngày cần người không ở quá khứ) và TKNHTTDNB1-249 (kiểm tra quyền và phòng ban của người tạo yêu cầu), story TKNHTTDNB1-29 (S2-10); TKNHTTDNB1-274 (kiểm tra định biên khi lưu) và TKNHTTDNB1-275 (HR cho phép vượt định biên có lý do), story TKNHTTDNB1-33 (S3-04); TKNHTTDNB1-278 (sao chép yêu cầu tuyển dụng) và TKNHTTDNB1-279 (bản sao bắt đầu ở trạng thái nháp), story TKNHTTDNB1-34 (S3-05); TKNHTTDNB1-297 (API danh sách theo bộ lọc) và TKNHTTDNB1-298 (số ngày mở, số ngày còn tới hạn và cờ trễ hạn; TRAN DUC LUONG), story TKNHTTDNB1-37 (S3-08); TKNHTTDNB1-285 (giới hạn truy cập theo vị trí được phân công), story TKNHTTDNB1-35 (S3-06). URL dùng tiền tố `/api/v1`. Gửi `Authorization: Bearer <accessToken>`; response thành công và các lỗi nghiệp vụ `REQUISITION_*`, `INVALID_REQUISITION_*`, `SALARY_JUSTIFICATION_REQUIRED`, `NEEDED_BY_IN_PAST` dùng `Cache-Control: no-store`.

Hiện có `POST /requisitions`, `GET /requisitions`, `GET /requisitions/{id}` và `PUT /requisitions/{id}`. Task 246 đã thêm kiểm tra trường bắt buộc, lý do tuyển, giới hạn số lượng và chức danh/phòng ban đang áp dụng (mục "Kiểm tra trường bắt buộc và lý do tuyển"). Task 247 bắt buộc nhập giải trình khi dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh (mục "Giải trình khi dải lương đề xuất ngoài chuẩn"). Task 248 từ chối ngày cần người trước ngày hôm nay theo múi giờ nghiệp vụ (mục "Ngày cần người không ở quá khứ"). Task 249 bắt phòng ban ghi vào yêu cầu (khi tạo, và khi sửa) phải thuộc phạm vi người gọi (mục "Phạm vi dữ liệu"). Sau task 249, phần backend cho các tiêu chí của story S2-10 đã có; trong story còn task 250 (kết nối giao diện, phía frontend) và 251 (test tạo và lưu nháp). Sprint 3 thêm: kiểm định biên khi lưu (task 274, mục "Kiểm tra định biên"), HR xác nhận vượt định biên và `GET /requisitions/{id}/headcount-overrides` (task 275), sao chép yêu cầu `POST /requisitions/{id}/copy` (task 278, 279, mục "Sao chép yêu cầu"). Phân công recruiter chính và hỗ trợ cho một yêu cầu (task 283, 284) và lịch sử thay đổi phân công (task 286) nằm ở [API phân công recruiter](requisition-recruiters.md). Gửi duyệt (task 257) và luồng duyệt nằm ở [API duyệt yêu cầu tuyển dụng](requisition-approvals.md). Tin tuyển dụng tạo từ yêu cầu đã duyệt (task 303) nằm ở [API tin tuyển dụng](job-postings.md). Task 297 thêm bộ lọc theo trạng thái, phòng ban, recruiter chính và khoảng ngày cần người cho `GET /requisitions` (mục "Danh sách"). Task 298 thêm `daysOpen`, `daysUntilNeededBy`, `overdue` vào item danh sách. Tạm dừng, mở lại, đóng vì đã tuyển đủ và hủy yêu cầu (task 293, 290) nằm ở [Tạm dừng, đóng và hủy yêu cầu tuyển dụng](requisition-status.md). Task 285 mở quyền **xem** yêu cầu cho recruiter đang được phân công (mục "Recruiter được phân công").

Tiêu chí của story S3-08:

- "Lọc theo trạng thái, phòng ban, recruiter phụ trách, khoảng thời gian": đã có (297, mục "Danh sách"). Bộ lọc chỉ thu hẹp những gì người gọi vốn được xem.
- "Mỗi dòng hiển thị số ngày mở và số ngày còn lại tới ngày cần người": đã có (298, `daysOpen` và `daysUntilNeededBy`).
- "Yêu cầu quá ngày cần người được đánh dấu nổi bật": đã có (298 `overdue`; 297 giới hạn cờ ở yêu cầu còn đang tuyển). Làm nổi bật trên màn hình là việc của frontend (task 296).

| Thao tác | Quyền cần có | Vai trò được phép theo seed hiện tại |
|---|---|---|
|`POST /requisitions`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`)|
|`GET /requisitions`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`: phòng ban mình phụ trách hoặc yêu cầu mình đang được phân công, chỉ để xem)|
|`GET /requisitions/{id}`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED`|Như dòng trên|
|`PUT /requisitions/{id}`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`|Như dòng `POST`|
|`GET /requisitions/{id}/headcount-overrides`|`REQUISITIONS_READ_ALL` **hoặc** `REQUISITIONS_READ_SCOPED` (task 275)|ADMIN, HR_MANAGER (`ALL`); HIRING_MANAGER, RECRUITER, APPROVER (`SCOPED`: chỉ phòng ban mình phụ trách; không gồm người tham gia duyệt và recruiter được phân công)|
|`POST /requisitions/{id}/copy`|`REQUISITIONS_WRITE_ALL` **hoặc** `REQUISITIONS_WRITE_SCOPED`, **và** quyền đọc yêu cầu gốc (task 278)|Như dòng `POST`|

INTERVIEWER và tài khoản không có vai trò nhận 403 `FORBIDDEN` ở cả sáu API. Backend đọc quyền hiện tại trong database ở mỗi yêu cầu. Khi ghi (`POST`, `PUT`), backend khóa tài khoản người gọi rồi phiên, sau đó kiểm lại trạng thái tài khoản, phiên, hạn JWT và quyền trước khi ghi, giống API chức danh. Quyền đọc và quyền ghi được kiểm riêng: `REQUISITIONS_READ_*` không cho phép tạo/sửa, `REQUISITIONS_WRITE_*` không cho phép gọi các API `GET`, và sao chép cần cả hai.

Mọi thao tác ghi còn kiểm lại hạn JWT, phiên và quyền (đọc lại từ database) sau mỗi khóa dòng mà thao tác phải chờ: dòng yêu cầu khi sửa, dòng chức danh và phòng ban, dòng định biên. Nếu trong lúc chờ token hết hạn hoặc quyền bị gỡ thì thao tác bị từ chối (401 `SESSION_INVALID` hoặc 403 `FORBIDDEN`) và không ghi gì.

## Phạm vi dữ liệu (task 245, 249, 285)

- Người có `ALL` (ADMIN, HR_MANAGER) xem, tạo và sửa yêu cầu của **mọi** phòng ban, và chuyển nháp giữa mọi phòng ban.
- Người chỉ có `SCOPED` chỉ xem, tạo và sửa yêu cầu thuộc **phòng ban mình phụ trách** (`departments.manager_user_id` là người gọi), tính cả mọi phòng ban con, cháu bên dưới trong cây. Ví dụ cây `IT > IT_DEV > IT_QA`: trưởng IT thấy và tạo được yêu cầu cho cả ba phòng; trưởng IT_DEV thấy và tạo được cho IT_DEV và IT_QA nhưng không cho IT.
- Người tạo yêu cầu không quyết định quyền xem: khi HR đổi người phụ trách phòng ban, bản nháp của phòng ban đó chuyển sang người phụ trách mới, người phụ trách cũ không xem/sửa được nữa (nhưng `createdBy` vẫn giữ người tạo).
- Trạng thái `active` của phòng ban không ảnh hưởng phạm vi: phòng ban ngừng áp dụng vẫn do người phụ trách của nó xem. Riêng việc **lưu** nháp vào phòng ban ngừng áp dụng bị chặn từ task 246 (xem mục kiểm tra bên dưới).
- RECRUITER và APPROVER thường không phụ trách phòng ban nào. Từ task 285, người có `REQUISITIONS_READ_SCOPED` đang là recruiter chính hoặc hỗ trợ của một yêu cầu ([API phân công recruiter](requisition-recruiters.md)) **xem** được yêu cầu đó (danh sách, chi tiết, phân công, lịch sử phân công, tiến độ duyệt), ở mọi trạng thái, cho tới khi bị bàn giao hoặc bỏ; họ vẫn nhận 403 khi sửa, gửi duyệt, sao chép, xem ngoại lệ định biên hay tạo yêu cầu (mục "Recruiter được phân công" bên dưới). Ai không phụ trách phòng ban nào và không được phân công yêu cầu nào nhận danh sách rỗng và 403 khi mở. Cách hiểu này cần BA/PO xác nhận (câu hỏi 2 và 19 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)). Từ task 262, người tham gia duyệt một yêu cầu xem được nó qua `GET /requisitions/{id}` (xem [API duyệt yêu cầu tuyển dụng](requisition-approvals.md), mục "Ai được xem yêu cầu"); danh sách vẫn chỉ theo phòng ban.
- Yêu cầu tồn tại nhưng ngoài phạm vi trả 403 `FORBIDDEN` theo quy ước chung của [thiết kế phân quyền](../architecture/authorization.md), không trả 404. UUID không tồn tại trả 404 `REQUISITION_NOT_FOUND` cho mọi người gọi; vì vậy người `SCOPED` phân biệt được "không tồn tại" và "không được phép", nhưng UUID là ngẫu nhiên nên không đoán được mã của phòng ban khác.

Task 245 giới hạn **yêu cầu đã có** mà người gọi được xem/sửa. Task 249 (kết quả Jira: "Bảo đảm Trưởng bộ phận tạo và cập nhật yêu cầu thuộc phạm vi phòng ban được phép") giới hạn thêm **phòng ban ghi vào body** (`departmentId`):

- `POST`: người `SCOPED` chỉ tạo được yêu cầu cho phòng ban thuộc phạm vi trên. Phòng ban khác, kể cả phòng ban **cha** của phòng mình phụ trách, trả 403 `FORBIDDEN` và không lưu gì.
- `PUT`: phải qua **hai** lần kiểm. Phòng ban hiện tại của nháp phải thuộc phạm vi (task 245), và phòng ban mới trong body cũng phải thuộc phạm vi. Vì vậy trưởng bộ phận chuyển được nháp giữa các phòng mình phụ trách (ví dụ IT sang IT_QA), nhưng không chuyển được ra ngoài (IT sang SALES), cũng không "kéo" nháp của phòng khác về phòng mình. Lần kiểm nào không qua cũng trả 403 `FORBIDDEN` và dòng giữ nguyên.
- Người `ALL` không bị giới hạn phòng ban; tài khoản có nhiều vai trò được `ALL` nếu một vai trò có `REQUISITIONS_WRITE_ALL` (ví dụ HIRING_MANAGER kiêm HR_MANAGER).
- Phạm vi chỉ phụ thuộc cây phòng ban, không phụ thuộc vai trò cụ thể: RECRUITER hay APPROVER được đặt làm người phụ trách một phòng ban cũng tạo được yêu cầu cho phòng ban đó; ai không phụ trách phòng ban nào thì nhận 403 với mọi `departmentId` (chờ BA/PO, câu hỏi 2 như trên).
- `active` của phòng ban chọn trong body vẫn phải là `true` (task 246), nhưng không ảnh hưởng phạm vi: phòng ban con đang áp dụng nằm dưới một phòng ban đã ngừng vẫn thuộc phạm vi của người phụ trách phòng ban đã ngừng đó.

Thứ tự: kiểm phạm vi của phòng ban trong body **sau** khi phòng ban đó được xác nhận tồn tại và đang áp dụng, và **trước** khi so dải lương chuẩn (bước 7 của tạo, trong bước 7 của sửa ở dưới). Hệ quả:

- `departmentId` không tồn tại vẫn là 400 `INVALID_REQUISITION_DEPARTMENT` với mọi người gọi, không phải 403, giống "UUID không tồn tại là 404 trước 403" ở trên. Phòng ban đã ngừng là 400 `REQUISITION_DEPARTMENT_INACTIVE`. Cây phòng ban vốn đọc được bởi mọi vai trò nội bộ (`ORGANIZATION_READ_ALL`), nên thứ tự này không làm lộ thêm thông tin.
- Lỗi chỉ dựa trên body (từng trường, `REQUISITION_SALARY_RANGE_INVALID`, `NEEDED_BY_IN_PAST`) và lỗi chức danh được trả trước 403 này.
- Lương ngoài dải chuẩn mà chưa có giải trình nhưng phòng ban ngoài phạm vi trả 403, không trả `SALARY_JUSTIFICATION_REQUIRED`: dải chuẩn không được so cho yêu cầu không được phép lưu.

**Đồng thời:** phạm vi được đọc **sau** khi dòng phòng ban trong body bị khóa `SELECT ... FOR SHARE` (bước 6 của tạo). Nếu HR đang đổi người phụ trách hoặc phòng ban cha của chính phòng ban đó, request chờ HR xong rồi dùng cây mới: ví dụ HR chuyển IT_DEV từ dưới IT sang dưới SALES và commit trong lúc trưởng IT đang tạo nháp cho IT_DEV thì trưởng IT nhận 403, HR hủy thì lưu bình thường. Sau khi kiểm, khóa giữ đến khi lưu xong nên HR không đổi được người phụ trách hay phòng ban cha của phòng ban đó trước khi nháp được lưu. Thay đổi ở phòng ban **cha** (ví dụ đổi người phụ trách IT khi nháp chọn IT_DEV) không bị chặn: nếu nó commit sau lúc kiểm thì được tính như xảy ra ngay sau khi nháp được lưu, và nháp đi theo người phụ trách mới như mọi nháp khác.

Quyết định của backend (chờ BA/PO xác nhận):

- Phòng ban ngoài phạm vi trả 403 `FORBIDDEN` theo quy ước chung, không trả lỗi form 400 riêng cho `departmentId`. Giao diện nên chỉ cho chọn các phòng ban người dùng phụ trách (tính được từ `GET /departments/tree`: node có `managerUserId` là người đang đăng nhập cùng toàn bộ `children` bên dưới), nên 403 này chỉ xảy ra khi dữ liệu trên form đã cũ hoặc request bị sửa tay.
- Không thêm quyền hay cột "phòng ban được phép" riêng: phạm vi tính từ `departments.manager_user_id` và `parent_id` ở mỗi lần lưu.

### Recruiter được phân công (task 285)

Story S3-06, tiêu chí "Recruiter chỉ nhìn thấy ứng viên của vị trí được giao". Backend chưa có module ứng viên, nên task 285 áp dụng quy tắc phân công ở chỗ đã có dữ liệu recruiter: yêu cầu tuyển dụng và các API chỉ đọc của nó. Với yêu cầu tuyển dụng, đây là việc **mở rộng** cho recruiter (trước đây họ không xem được yêu cầu mình được giao). Phần **giới hạn** hồ sơ ứng viên của tiêu chí chưa làm được cho tới khi có module ứng viên: quy tắc phân công đã có và áp dụng cho yêu cầu tuyển dụng; phần hồ sơ ứng viên chờ module ứng viên, sẽ dùng cùng quy tắc.

Quy tắc: người gọi xem được một yêu cầu khi có `ALL`, **hoặc** phụ trách phòng ban của nó (kể cả phòng ban cha), **hoặc** đang là recruiter chính hay hỗ trợ của nó (một dòng trong `requisition_recruiters`, V16), **hoặc** tham gia duyệt nó (task 262). Quy tắc nằm ở một chỗ, `RecruiterAssignmentAccess`; tin tuyển dụng (`JOB_POSTINGS_*_SCOPED`, task 303) dùng cùng quy tắc, và module ứng viên sau này cũng phải dùng nó. Chỉ thao tác **đọc** dùng quy tắc này; mọi thao tác ghi giữ quy tắc phòng ban.

Người gọi trong bảng: **ALL** là HR_MANAGER, ADMIN (`REQUISITIONS_READ_ALL`); **Trưởng phòng ban** là người `SCOPED` phụ trách phòng ban của yêu cầu; **Được phân công** là người `SCOPED` không phụ trách phòng ban nào nhưng đang là recruiter chính hoặc hỗ trợ của yêu cầu; **Recruiter cũ / không được phân công** là người đã bị bàn giao, bị bỏ (đã lưu) hoặc chưa bao giờ có tên trong nhóm; **Tham gia duyệt** là người tham gia duyệt (task 262) không phụ trách phòng ban đó.

| API | ALL | Trưởng phòng ban | Được phân công: trước → **sau** | Recruiter cũ / không được phân công | Tham gia duyệt | Quyết định |
|---|---|---|---|---|---|---|
|`GET /requisitions`|mọi yêu cầu|phòng ban phụ trách|trang rỗng → **phòng ban phụ trách cộng yêu cầu được phân công**, mọi bộ lọc vẫn là AND|không có trong danh sách|không có trong danh sách (không đổi)|**Mở rộng**|
|`GET /requisitions/{id}`|200|200|403 → **200**|403|200|**Mở rộng**|
|`GET /requisitions/{id}/assignment`|200, có `eligible`|200, không có `eligible`|403 → **200, không có `eligible`**|403|200|Mở rộng (dùng kiểm tra của chi tiết)|
|`GET /requisitions/{id}/assignment-history`|200|200|403 → **200** (gồm ghi chú và tên recruiter trước; câu hỏi 19.2)|403|200|Mở rộng (như trên)|
|`GET /requisitions/{id}/approvals` (tiến độ duyệt)|200|200|403 → **200**; `actions.canDecide` vẫn theo người duyệt (`false` với recruiter thường)|403|200|Mở rộng (như trên)|
|`GET /requisitions/approval-inbox`|theo người duyệt|theo người duyệt|không đổi (rỗng với recruiter thường)|không đổi|không đổi|Không mở rộng (không phải quy tắc phạm vi)|
|`GET /requisitions/{id}/headcount-overrides`|200|200 (dòng của phòng ban phụ trách)|403 → **403**|403|403|**Không mở rộng** (ngoại lệ định biên kèm số liệu định biên của phòng ban; giống người tham gia duyệt)|
|`GET /headcount-plans/remaining`|200|phòng ban phụ trách|403 với phòng ban đó → **403**|–|–|Không mở rộng (số liệu định biên của phòng ban)|
|`GET /headcount-plans`, `/headcount-plans/{id}`; `GET /approval-flows…`|`HEADCOUNT_PLANS_READ_ALL`; luồng duyệt: vai trò ADMIN hoặc HR_MANAGER kèm `REQUISITIONS_READ_ALL`|403 (matcher)|403 (matcher)|403|403|Không gắn với một yêu cầu; không đổi (liệt kê để không API đọc nào bị bỏ sót)|
|`PUT /requisitions/{id}`|200|200 nếu còn sửa được|403 → **403**|403|403|Ghi: không đổi|
|`POST /requisitions/{id}/copy`|201|201|403 → **403** (kể cả khi chức danh của bản gốc đã ngừng: 403 trước 400)|403|403|Không mở rộng (sao chép cho thấy bản gốc và tạo nháp của phòng ban bản gốc, việc người không phụ trách phòng ban đó vốn không làm được)|
|`POST /requisitions/{id}/submit`|200|200|403 → **403**|403|403|Ghi: không đổi|
|`POST /requisitions/{id}/approvals` (quyết định duyệt)|theo người duyệt|theo người duyệt|403 → **403** (không phải 409, kể cả với yêu cầu đã duyệt)|403|theo người duyệt (409 khi tiến độ đã cũ)|Ghi: không đổi; phân công không được tính|
|`POST /requisitions/{id}/assign`, `/unassign`|200|403 (matcher)|403 (matcher)|403|403|Không đổi; service vẫn kiểm quyền xem theo phòng ban|
|`POST /requisitions/{id}/status-changes`|200|403 (matcher)|403 (matcher)|403|403|Không đổi; service vẫn kiểm quyền xem theo phòng ban|
|`POST /requisitions`|mọi phòng ban|phòng ban phụ trách|403 (phòng ban trong body)|–|–|Không đổi|
|Tin tuyển dụng: `GET /job-postings/source-requisitions`, `GET /job-postings` (lọc trong SQL), `GET /job-postings/{id}`, `/preview`, `/history`, `POST`, `PUT`, `/submit` (từng bản ghi; ghi dưới khóa dòng yêu cầu)|mọi tin|– (không có `JOB_POSTINGS_WRITE_*`)|theo phân công (từ task 303)|403 / rỗng|–|Hành vi không đổi; câu SQL và kiểm tra từng bản ghi nay lấy từ `RecruiterAssignmentAccess`. `/approvals`, `/publish` vẫn chỉ `JOB_POSTINGS_WRITE_ALL`|

Thứ tự kiểm của các API đọc không đổi: matcher (401/403), `{id}` không phải UUID (400), token và tài khoản (401), quyền đọc `REQUISITIONS_READ_*` (403, **trước** khi tìm yêu cầu), yêu cầu tồn tại (404), rồi `ALL`, phòng ban phụ trách, phân công, tham gia duyệt; không khớp cái nào thì 403. Kiểm rẻ trước: lịch sử duyệt chỉ được đọc khi ba điều kiện đầu không khớp. Đọc không khóa gì.

- **Mất quyền khi nào ("kể cả sau bàn giao").** Quyền xem đi theo nhóm recruiter **hiện tại**: khi HR bàn giao recruiter chính hoặc bỏ recruiter hỗ trợ và lần lưu đó đã commit, recruiter mới xem được và người cũ mất quyền ngay từ request tiếp theo. Lịch sử phân công (V17) không bao giờ cho quyền xem. Một lệnh đọc đã bắt đầu trước lúc commit vẫn trả theo nhóm cũ (cả lệnh dùng một snapshot REPEATABLE READ, bắt đầu từ câu truy vấn tài khoản); đây là hành vi đúng, không phải lỗi. Lệnh đọc không bao giờ chờ một thay đổi nhóm đang chạy.
- **Theo mã quyền, không theo tên vai trò.** Ai đang được phân công rồi đổi vai trò RECRUITER sang vai trò khác vẫn có `REQUISITIONS_READ_SCOPED` thì vẫn xem được; không còn quyền đọc `REQUISITIONS_READ_*` nào thì nhận 403 trước khi tìm yêu cầu, dù dòng phân công vẫn còn (V16 giữ dòng đó tới khi HR bàn giao).
- **Mọi trạng thái.** Được phân công thì xem được yêu cầu ở mọi trạng thái, cả nháp, chờ duyệt, cần bổ sung và sau khi kết thúc (bị từ chối, đã tuyển đủ, đã hủy). Nhóm cuối cùng của một yêu cầu đã kết thúc vẫn xem được nó (không API nào bỏ phân công ở các trạng thái đó). Câu hỏi 19.3.
- **Ghi không mở rộng.** Sửa, gửi duyệt, sao chép, ngoại lệ định biên, quyết định duyệt, đổi trạng thái và phân công vẫn theo phòng ban (hoặc theo người duyệt với quyết định duyệt). Riêng quyết định duyệt cố ý không tính phân công: nếu tính, recruiter không được duyệt sẽ nhận 409 (lộ trạng thái và tiến độ) thay vì 403.
- **Hồ sơ ứng viên: chờ module ứng viên.** Khi có module ứng viên, recruiter (`CANDIDATES_*_SCOPED`) chỉ xem ứng viên và hồ sơ ứng tuyển của yêu cầu mình đang được phân công, theo cùng `RecruiterAssignmentAccess`: danh sách lọc bằng `ASSIGNED_REQUISITION_IDS` trong câu SQL, một bản ghi kiểm bằng `isAssigned`, thao tác ghi kiểm dưới khóa dòng yêu cầu. Tiêu chí "Recruiter chỉ nhìn thấy ứng viên của vị trí được giao" vì vậy chưa đạt đủ (câu hỏi 19.8).

## Tạo bản nháp

`POST /requisitions` tạo một yêu cầu tuyển dụng ở trạng thái `DRAFT`, người tạo là người gọi (lấy từ token), trả **201**. Mỗi lần gọi tạo một bản nháp mới, kể cả khi nội dung giống hệt bản đã có; chưa có kiểm tra trùng.

```json
{
  "positionId": "00000000-0000-0000-0000-000000000003",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "headcount": 2,
  "reason": "NEW_HEADCOUNT",
  "proposedSalaryMin": 15000000,
  "proposedSalaryMax": 25000000,
  "salaryJustification": null,
  "neededBy": "2026-12-31",
  "jobDescription": "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java."
}
```

| Trường | Quy tắc |
|---|---|
|positionId|Bắt buộc, UUID của chức danh có trong bảng `positions` và đang áp dụng (`active = true`)|
|departmentId|Bắt buộc, UUID của phòng ban có trong bảng `departments` và đang áp dụng (`active = true`)|
|headcount|Bắt buộc, số nguyên JSON từ 1 đến 999|
|reason|Bắt buộc, chuỗi đúng một trong hai mã `REPLACEMENT` (tuyển thay thế) hoặc `NEW_HEADCOUNT` (tăng mới): đúng chữ hoa, không có khoảng trắng đầu/cuối|
|proposedSalaryMin|Không bắt buộc. Lương đề xuất tối thiểu, số nguyên đồng VND từ 0 đến 1.000.000.000.000|
|proposedSalaryMax|Không bắt buộc. Lương đề xuất tối đa, cùng quy tắc; khi có cả hai mức thì không nhỏ hơn `proposedSalaryMin` (được phép bằng)|
|salaryJustification|Tối đa 2.000 ký tự, không chứa ký tự NUL. **Bắt buộc** khi một mức lương đề xuất đã nhập nằm ngoài dải lương chuẩn của chức danh (task 247); ngoài trường hợp đó thì không bắt buộc|
|neededBy|Không bắt buộc. Ngày cần người dạng `yyyy-MM-dd`, không có giờ. Nếu có thì không được **trước ngày hôm nay** theo múi giờ nghiệp vụ, mặc định giờ Việt Nam (task 248); hôm nay được phép|
|jobDescription|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|
|candidateRequirements|Không bắt buộc, tối đa 10.000 ký tự, không chứa ký tự NUL|
|headcountOverrideReason|Không bắt buộc, tối đa 1.000 ký tự, không chứa ký tự NUL. Chỉ dùng khi lần lưu vượt định biên: Trưởng phòng Nhân sự nhập lý do để xác nhận vượt (task 275, mục "Kiểm tra định biên"). Không phải trường của yêu cầu: không được lưu vào yêu cầu và không có trong response|

Bản nháp được lưu dù chưa viết xong: chỉ bốn trường đầu là bắt buộc. Trường không gửi, gửi `null`, hoặc văn bản không có ký tự nhìn thấy được (rỗng, chỉ có khoảng trắng và xuống dòng, kể cả khoảng trắng không ngắt như U+00A0, U+2007, U+202F, hay chỉ có ký tự rộng 0 như U+200B, U+FEFF) được lưu là `null` (V13 lưu phần chưa viết là `NULL`). Văn bản có nội dung được giữ nguyên như người dùng nhập, kể cả xuống dòng, thụt đầu dòng và khoảng trắng đầu/cuối. Có thể nhập một đầu của dải lương đề xuất. Lương phải là số nguyên JSON: `1.5`, `1e3` hoặc chuỗi `"15000000"` bị từ chối với `INVALID_JSON`, giống [API chức danh](positions.md). `headcount` cũng vậy: `1.5`, `0.9`, `2.0`, `1e1`, chuỗi `"2"` hoặc `true` bị từ chối với `INVALID_JSON`, không bị làm tròn thành `1`/`0` hay tự đổi thành số; số vượt 2.147.483.647 (giới hạn của cột `INTEGER`) cũng là `INVALID_JSON`. Số nguyên từ 1.000 đến 2.147.483.647 là `VALIDATION_ERROR` "Số lượng cần tuyển tối đa 999 người." (task 246).

Ký tự NUL (mã 0, trong JSON viết là `\u0000`, đôi khi dính vào khi dán từ tệp khác) không lưu được vào cột `TEXT` của PostgreSQL, nên `salaryJustification`, `jobDescription`, `candidateRequirements` chứa ký tự này bị từ chối với `VALIDATION_ERROR` (ví dụ `fieldErrors.jobDescription` là "Mô tả công việc chứa ký tự không hợp lệ."). Mọi ký tự khác, kể cả tab và xuống dòng kiểu Windows `\r\n`, được giữ nguyên.

Trường ngoài hợp đồng, kể cả `id`, `status`, `createdBy`, `createdAt`, bị từ chối với 400 `INVALID_JSON`: trạng thái và người tạo do server quyết định.

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (thiếu quyền thì 403 trước khi đọc body).
2. Từng trường riêng lẻ: thiếu trường bắt buộc, số lượng ngoài 1–999, lý do tuyển không phải một trong hai mã, lương âm hoặc vượt trần, văn bản quá dài hoặc chứa ký tự NUL. Mọi trường sai được trả cùng lúc trong `fieldErrors` với mã `VALIDATION_ERROR`.
3. Khóa tài khoản và phiên, kiểm lại quyền (403 nếu vừa mất quyền, 401 `SESSION_INVALID` nếu phiên/tài khoản/token không còn hợp lệ).
4. So hai mức lương đề xuất: tối thiểu lớn hơn tối đa trả `REQUISITION_SALARY_RANGE_INVALID`.
5. Ngày cần người trước ngày hôm nay theo múi giờ nghiệp vụ: `NEEDED_BY_IN_PAST` (task 248).
6. Chức danh tồn tại (`INVALID_REQUISITION_POSITION`) và đang áp dụng (`REQUISITION_POSITION_INACTIVE`), rồi phòng ban tồn tại (`INVALID_REQUISITION_DEPARTMENT`) và đang áp dụng (`REQUISITION_DEPARTMENT_INACTIVE`).
7. Phòng ban trong body thuộc phạm vi người gọi (chỉ kiểm với người `SCOPED`): nếu không thì 403 `FORBIDDEN` (task 249, mục "Phạm vi dữ liệu").
8. Mức lương đề xuất ngoài dải lương chuẩn của chức danh mà không có giải trình: `SALARY_JUSTIFICATION_REQUIRED` (task 247).
9. Vượt định biên của phòng ban trong năm kế hoạch: 409 `HEADCOUNT_LIMIT_EXCEEDED` hoặc `SALARY_BUDGET_EXCEEDED` (task 274, mục "Kiểm tra định biên").

Mỗi lần chỉ trả lỗi đầu tiên gặp từ bước 4 trở đi.

## Kiểm tra trường bắt buộc và lý do tuyển (task 246)

Kết quả Jira: xác thực chức danh, phòng ban, số lượng và lý do tuyển là thay thế hoặc tăng mới. Các quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp).

| Trường | Sai thế nào | HTTP / mã | Lời nhắn (`fieldErrors.<trường>`) |
|---|---|---|---|
|positionId|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Chức danh không được để trống.|
|positionId|Không có chức danh này|400 `INVALID_REQUISITION_POSITION`|Chức danh không tồn tại.|
|positionId|Chức danh `active = false`|400 `REQUISITION_POSITION_INACTIVE`|Chức danh đã ngừng áp dụng, hãy chọn chức danh khác.|
|departmentId|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Phòng ban không được để trống.|
|departmentId|Không có phòng ban này|400 `INVALID_REQUISITION_DEPARTMENT`|Phòng ban không tồn tại.|
|departmentId|Phòng ban `active = false`|400 `REQUISITION_DEPARTMENT_INACTIVE`|Phòng ban đã ngừng áp dụng, hãy chọn phòng ban khác.|
|headcount|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Số lượng cần tuyển không được để trống.|
|headcount|0 hoặc số âm|400 `VALIDATION_ERROR`|Số lượng cần tuyển phải lớn hơn 0.|
|headcount|Từ 1.000 trở lên|400 `VALIDATION_ERROR`|Số lượng cần tuyển tối đa 999 người.|
|reason|Thiếu hoặc `null`|400 `VALIDATION_ERROR`|Lý do tuyển không được để trống.|
|reason|Chuỗi khác hai mã, kể cả chữ thường (`replacement`), có khoảng trắng (`" REPLACEMENT"`) hoặc chuỗi rỗng|400 `VALIDATION_ERROR`|Lý do tuyển chỉ được là REPLACEMENT (tuyển thay thế) hoặc NEW_HEADCOUNT (tăng mới).|

Với bốn lỗi `INVALID_REQUISITION_*` và `REQUISITION_*_INACTIVE` của chức danh và phòng ban, `message` và lời nhắn trong `fieldErrors` là cùng một câu; response có `Cache-Control: no-store`.

Lý do tuyển sai (trước task 246 là `INVALID_JSON` chung chung, không nói trường nào sai) nay là lỗi của đúng trường `reason`, và được trả **cùng lúc** với các trường sai khác. Ví dụ body thiếu `positionId`, `headcount` là 1000 và `reason` là `"OTHER"`:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Vui lòng kiểm tra dữ liệu đã nhập.",
  "fieldErrors": {
    "positionId": "Chức danh không được để trống.",
    "headcount": "Số lượng cần tuyển tối đa 999 người.",
    "reason": "Lý do tuyển chỉ được là REPLACEMENT (tuyển thay thế) hoặc NEW_HEADCOUNT (tăng mới)."
  }
}
```

Thứ tự các khóa trong `fieldErrors` không cố định. `reason` gửi dạng số hoặc `true`/`false` được đọc thành chuỗi (`"1"`, `"true"`) nên cũng nhận lời nhắn trên; gửi object hoặc mảng (`{}`, `["REPLACEMENT"]`) thì cả JSON không đúng hợp đồng và trả `INVALID_JSON`.

Quyết định của backend (chờ BA/PO xác nhận):

- **Tối đa 999 người mỗi yêu cầu.** Mục đích là chặn gõ nhầm (ví dụ 10000 thay vì 10); nhu cầu lớn hơn thì tách thành nhiều yêu cầu. Giới hạn này chỉ nằm ở API; CHECK của V13 vẫn chỉ là `headcount > 0` vì task không được sửa migration đã có.
- **Chức danh và phòng ban phải đang áp dụng, kể cả khi sửa.** HR đặt `active = false` để ngừng tuyển cho chức danh/phòng ban đó, nên nháp mới không chọn được. Nháp tạo trước khi chức danh/phòng ban bị ngừng vẫn **xem được** như cũ, nhưng muốn lưu lại thì phải chọn chức danh/phòng ban khác đang áp dụng (hoặc HR bật lại). Điểm này khác API quản trị tài khoản (cho giữ phòng ban cũ đã ngừng): yêu cầu tuyển dụng xin người cho hiện tại, không chỉ ghi nhận quá khứ.
- **Chỉ xét cờ của chính phòng ban được chọn.** Phòng ban đang áp dụng nằm dưới một phòng cha đã ngừng vẫn chọn được, khớp với [API phòng ban](departments.md): ngừng phòng cha không tự ngừng phòng con.
- Người `SCOPED` sửa nháp ngoài phạm vi nhận 403 trước bước kiểm chức danh/phòng ban (bước 5 trước bước 7 trong thứ tự kiểm tra của mục "Cập nhật bản nháp"). Lỗi từng trường (bước 2) vẫn trả trước bước phạm vi như mọi API ghi khác.

**Đồng thời:** dòng chức danh và dòng phòng ban được đọc bằng `SELECT active ... FOR SHARE` trong transaction ghi (mức cô lập mặc định READ COMMITTED). Khóa chia sẻ giữ đến khi tạo/sửa xong, nên `PUT /positions/{id}` hoặc `PUT /departments/{id}` của HR (ví dụ ngừng áp dụng) phải chờ, không thể chen vào giữa lúc kiểm và lúc lưu. Ngược lại, nếu HR đang ngừng áp dụng dở dang, yêu cầu tạo/sửa chờ HR xong rồi dùng giá trị mới: HR commit thì trả `*_INACTIVE`, HR hủy thì lưu bình thường. Nhiều yêu cầu dùng cùng chức danh/phòng ban vẫn chạy song song vì khóa chia sẻ không chặn nhau. `DELETE /departments/{id}` (task 197) khóa dòng phòng ban `FOR UPDATE`: đang có yêu cầu tạo/sửa chọn phòng đó thì lệnh xóa chờ rồi trả 409 `DEPARTMENT_HAS_OPEN_REQUISITIONS`; lệnh xóa chạy trước thì yêu cầu tạo/sửa chờ xóa xong rồi nhận 400 `INVALID_REQUISITION_DEPARTMENT`.

Response của tạo (cũng là cấu trúc của chi tiết và response của sửa):

```json
{
  "id": "00000000-0000-0000-0000-000000000010",
  "positionId": "00000000-0000-0000-0000-000000000003",
  "departmentId": "00000000-0000-0000-0000-000000000002",
  "headcount": 2,
  "reason": "NEW_HEADCOUNT",
  "proposedSalaryMin": 15000000,
  "proposedSalaryMax": 25000000,
  "salaryJustification": null,
  "neededBy": "2026-12-31",
  "jobDescription": "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL",
  "candidateRequirements": "Tối thiểu 2 năm kinh nghiệm Java.",
  "status": "DRAFT",
  "createdBy": "00000000-0000-0000-0000-000000000001",
  "createdAt": "2026-10-07T08:00:00.123456Z",
  "updatedAt": "2026-10-07T08:00:00.123456Z"
}
```

UUID trong ví dụ chỉ minh họa. Trường chưa nhập có giá trị `null` (khóa vẫn có trong JSON). `createdAt`/`updatedAt` là UTC, độ chính xác micro giây như PostgreSQL lưu; lúc tạo hai giá trị bằng nhau. `proposedSalaryMin`/`proposedSalaryMax` là mức người tạo đề xuất, không phải dải lương chuẩn của chức danh; response không chứa dải chuẩn, nên người không có `SALARY_RANGES_READ_ALL` không thấy được con số của dải chuẩn qua API này (lỗi giải trình của task 247 cũng không chứa con số, xem mục "Giải trình khi dải lương đề xuất ngoài chuẩn").

## Danh sách

`GET /requisitions?status=DRAFT&page=0&size=20` trả các yêu cầu người gọi được xem theo mục "Phạm vi dữ liệu", trả **200**. Từ task 297 danh sách lọc được theo trạng thái, phòng ban, recruiter chính và khoảng ngày cần người. Mọi bộ lọc đều không bắt buộc và được kết hợp bằng AND; gửi giá trị rỗng (ví dụ `departmentId=`) giống như không gửi. Tên tham số đúng như frontend đang gửi.

| Tham số | Ý nghĩa | Giá trị sai |
|---|---|---|
|status|Không bắt buộc. Mã trạng thái đúng chữ hoa: `DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `REJECTED` (task 257, xem [API duyệt yêu cầu tuyển dụng](requisition-approvals.md)), `PAUSED`, `FILLED`, `CANCELLED` (task 293, xem [Tạm dừng, đóng và hủy yêu cầu tuyển dụng](requisition-status.md)). Nhận nhiều giá trị (task 297): lặp lại tham số (`status=DRAFT&status=APPROVED`) hoặc ngăn bằng dấu phẩy (`status=DRAFT,APPROVED`), không trộn hai cách trong một yêu cầu. Phần tử rỗng (`status=DRAFT,`) bị bỏ qua. Bỏ qua (hoặc để rỗng) để lấy mọi trạng thái|400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ." (mã sai, chữ thường, hoặc `PENDING`, `OPEN`, `CLOSED`)|
|departmentId|Không bắt buộc. UUID của phòng ban: chỉ yêu cầu của **đúng** phòng ban đó, không gồm phòng ban con (task 297)|Không phải UUID: như dòng trên|
|recruiterId|Không bắt buộc. UUID tài khoản: yêu cầu mà người này đang là **recruiter chính** (`PRIMARY`, [API phân công recruiter](requisition-recruiters.md)). Recruiter hỗ trợ không khớp; sau bàn giao chỉ recruiter chính mới khớp (task 297)|Không phải UUID: như dòng trên|
|startDate, endDate|Không bắt buộc. Khoảng **ngày cần người** (`neededBy`), dạng `yyyy-MM-dd`, tính cả hai đầu; gửi một đầu cũng được. Khi có ít nhất một đầu, yêu cầu chưa có ngày cần người bị loại (task 297)|Sai định dạng (`2026-02-30`, `11/10/2026`, `2026-10-11T00:00`, `2026-10-11+07:00`): như dòng trên. `startDate` sau `endDate`, hoặc năm ngoài 1–9999 (`0000-01-01`, `+10000-01-01`): 400 `VALIDATION_ERROR` "Khoảng ngày cần người không hợp lệ."|
|page|Từ 0, mặc định 0; `page × size` (số dòng bỏ qua) không được vượt 2.147.483.647|Không phải số nguyên: 400 "Tham số đường dẫn hoặc bộ lọc không hợp lệ."; ngoài giới hạn: 400 `VALIDATION_ERROR` "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ."|
|size|Từ 1 đến 100, mặc định 20|Như dòng trên|

Ví dụ:

```http
GET /api/v1/requisitions?status=APPROVED&status=RETURNED&departmentId=6f2c…&recruiterId=1c9e…&startDate=2026-10-01&endDate=2026-12-31&page=0&size=20
```

```json
{
  "items": [
    {
      "id": "…",
      "positionId": "…",
      "departmentId": "6f2c…",
      "headcount": 2,
      "reason": "NEW_HEADCOUNT",
      "proposedSalaryMin": 20000000,
      "proposedSalaryMax": 22000000,
      "salaryJustification": null,
      "neededBy": "2026-10-08",
      "jobDescription": "…",
      "candidateRequirements": "…",
      "status": "APPROVED",
      "createdBy": "…",
      "createdAt": "2026-09-26T03:00:00.123456Z",
      "updatedAt": "2026-09-26T03:00:00.123456Z",
      "daysOpen": 15,
      "daysUntilNeededBy": -3,
      "overdue": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

**Bộ lọc chỉ thu hẹp.** Điều kiện phạm vi của mục "Phạm vi dữ liệu" luôn nằm trong câu SQL, bộ lọc chỉ thêm điều kiện AND. Người `SCOPED` vẫn chỉ thấy yêu cầu của phòng ban mình phụ trách (kể cả phòng ban con) và, từ task 285, yêu cầu mình đang là recruiter chính hay hỗ trợ: lọc theo phòng ban ngoài phạm vi, phòng ban không tồn tại, hoặc recruiter không tồn tại hay không phụ trách yêu cầu nào trong phạm vi đều trả **200 với trang rỗng**, không bao giờ 403. Ô chọn phòng ban của frontend liệt kê mọi phòng ban, và frontend đưa mọi 403 sang `/unauthorized`, nên trang rỗng là câu trả lời đúng. Phạm vi của người `SCOPED` là phòng ban mình phụ trách **hoặc** yêu cầu mình đang là recruiter chính hay hỗ trợ (task 285); bộ lọc `recruiterId` (recruiter chính) chỉ thu hẹp phạm vi đó, không mở thêm. Người tham gia duyệt (task 262) không thấy thêm gì trong danh sách.

**Ngày.** `startDate`, `endDate` và `neededBy` đều là ngày theo lịch (không có giờ), nên phép so sánh không phụ thuộc múi giờ. Hai đầu được tính: `startDate=2026-11-01&endDate=2026-11-30` gồm cả yêu cầu cần người ngày 1/11 và ngày 30/11; `startDate` bằng `endDate` là đúng một ngày. Định dạng là `yyyy-MM-dd` nghiêm ngặt: ngày không có thật (`2026-02-30`), có giờ hoặc có múi giờ đều bị từ chối. Năm phải từ 1 đến 9999: năm khác có thể đọc được (`0000-01-01`, `+10000-01-01`) nhưng bị từ chối trước khi tới PostgreSQL.

**Hai lời nhắn 400.** "Tham số đường dẫn hoặc bộ lọc không hợp lệ." là giá trị không đọc được (trạng thái không tồn tại, UUID sai, ngày sai định dạng, `page`/`size` không phải số nguyên). "Khoảng ngày cần người không hợp lệ." là hai ngày đọc được nhưng `startDate` sau `endDate`, hoặc năm ngoài 1–9999. Cả hai đều là mã `VALIDATION_ERROR`, có `Cache-Control: no-store`.

**Tham số sai tên.** Spring bỏ qua tham số sai tên: lọc không có tác dụng, không báo lỗi. Ví dụ `?department=…` (thiếu `Id`) trả mọi yêu cầu trong phạm vi.

Thứ tự kiểm tra: quyền ở `SecurityConfiguration` (401/403); đọc tham số (400 "Tham số…"); tài khoản, phiên và quyền đọc trong database (401/403); `page`/`size` (400 "Trang…"); khoảng ngày (400 "Khoảng ngày…"); sau cùng mới truy vấn, với điều kiện phạm vi (phòng ban phụ trách hoặc yêu cầu được phân công, task 285) nằm trong câu SQL.

Response: `{items, page, size, totalElements, totalPages}`; mỗi item có 15 trường của response tạo ở trên và thêm `daysOpen`, `daysUntilNeededBy`, `overdue`. Ba trường tính toán chỉ có trong item danh sách; tạo, chi tiết, sửa và hộp chờ duyệt ([API duyệt](requisition-approvals.md)) giữ cấu trúc response cũ.

`daysOpen` là số ngày từ ngày tạo tới hôm nay (ngày tạo tính là 0). `daysUntilNeededBy` là số ngày từ hôm nay tới `neededBy`, âm khi đã trễ và `null` nếu chưa có ngày cần người. `overdue` chỉ là `true` khi `neededBy` trước hôm nay; đến hạn hôm nay chưa trễ. Các ngày được tính theo `app.business-zone`, mặc định `Asia/Ho_Chi_Minh`, tại thời điểm đọc danh sách.

`overdue` chỉ có thể `true` khi trạng thái là `DRAFT`, `PENDING_APPROVAL`, `RETURNED` hoặc `APPROVED`; `REJECTED` (task 297) và `PAUSED`, `FILLED`, `CANCELLED` (task 293) luôn `false` dù `daysUntilNeededBy` âm. `daysOpen` vẫn đếm từ ngày tạo với mọi trạng thái.

Sắp xếp yêu cầu tạo sau lên trước (`createdAt` giảm dần, rồi UUID) để phân trang ổn định; sửa bản nháp không đổi vị trí của nó. Trang ngoài phạm vi có `items` rỗng. Người `SCOPED` không phụ trách phòng ban nào và không được phân công yêu cầu nào nhận `items` rỗng với `totalElements` và `totalPages` bằng 0. Điều kiện phạm vi (phòng ban phụ trách, hoặc từ task 285 yêu cầu được phân công) nằm trong câu truy vấn SQL dưới dạng một điều kiện trong ngoặc, nên yêu cầu ngoài phạm vi không được đọc ra khỏi database và mọi bộ lọc thu hẹp cả hai nhánh. `totalElements` và `totalPages` tính sau khi lọc.

`status` sai (ví dụ `draft` chữ thường hoặc `SUBMITTED`), `departmentId`/`recruiterId` không phải UUID, `startDate`/`endDate` không đúng `yyyy-MM-dd`, `page`/`size` không phải số nguyên trả 400 `VALIDATION_ERROR` "Tham số đường dẫn hoặc bộ lọc không hợp lệ."; `page` âm, `size` nhỏ hơn 1 hoặc lớn hơn 100, hoặc `page × size` lớn hơn 2.147.483.647 (ví dụ `page=21474837&size=100`) trả 400 `VALIDATION_ERROR` "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ."; `startDate` sau `endDate` hoặc năm ngoài 1–9999 trả 400 `VALIDATION_ERROR` "Khoảng ngày cần người không hợp lệ.".

## Chi tiết

`GET /requisitions/{id}` trả **200** với cấu trúc như response tạo. Ngoài người trong phạm vi phòng ban và người tham gia duyệt (task 262), recruiter chính hoặc hỗ trợ hiện tại của yêu cầu cũng xem được (task 285, mục "Recruiter được phân công"). UUID không tồn tại trả 404 `REQUISITION_NOT_FOUND`; yêu cầu ngoài phạm vi trả 403 `FORBIDDEN`; `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.

## Cập nhật bản nháp

`PUT /requisitions/{id}` lưu lại bản nháp với nội dung mới, trả **200** cùng yêu cầu sau khi sửa. Body có đúng các trường và quy tắc của `POST` ở trên. `PUT` **thay toàn bộ** nội dung: trường không gửi, gửi `null` hoặc văn bản không có ký tự nhìn thấy được trở thành `null`, nên giao diện phải gửi lại mọi trường đang có trên form. `id`, `status`, `createdBy`, `createdAt` không sửa được (gửi lên là `INVALID_JSON`); `createdBy` và `createdAt` giữ nguyên kể cả khi người sửa không phải người tạo, `updatedAt` là thời điểm sửa (UTC, micro giây).

Chỉ yêu cầu ở trạng thái `DRAFT`, hoặc `RETURNED` (người duyệt yêu cầu bổ sung, task 264), được sửa; trạng thái khác trả 409 `REQUISITION_NOT_DRAFT`. Từ task 257, yêu cầu đã gửi duyệt (`PENDING_APPROVAL`), đã duyệt hay bị từ chối không sửa được; từ task 293, yêu cầu tạm dừng, đã tuyển đủ hay đã hủy cũng vậy. Yêu cầu `APPROVED` (task 263) là yêu cầu được dùng để soạn tin tuyển dụng (story S3-09).

Thứ tự kiểm tra:

1. Quyền ở `SecurityConfiguration` (thiếu quyền ghi thì 403 trước khi đọc body); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`.
2. Từng trường riêng lẻ, giống bước 2 của tạo.
3. Khóa tài khoản rồi phiên, kiểm lại quyền (403 nếu vừa mất quyền, 401 `SESSION_INVALID` nếu phiên/tài khoản/token không còn hợp lệ).
4. Khóa dòng yêu cầu (`SELECT ... FOR UPDATE`): không có thì 404 `REQUISITION_NOT_FOUND`.
5. Phạm vi: người `SCOPED` không phụ trách phòng ban hiện tại của yêu cầu thì 403 `FORBIDDEN`. Bước này chạy sau khi đã khóa dòng, nên nếu HR đổi người phụ trách phòng ban trong lúc yêu cầu đang chờ khóa, kết quả dùng người phụ trách mới.
6. Ở trạng thái sửa được (`DRAFT` hoặc `RETURNED`, task 264), nếu không thì 409 `REQUISITION_NOT_DRAFT`.
7. So hai mức lương đề xuất (`REQUISITION_SALARY_RANGE_INVALID`), rồi ngày cần người (`NEEDED_BY_IN_PAST`), rồi chức danh và phòng ban trong body tồn tại và đang áp dụng (`INVALID_REQUISITION_POSITION`, `REQUISITION_POSITION_INACTIVE`, `INVALID_REQUISITION_DEPARTMENT`, `REQUISITION_DEPARTMENT_INACTIVE`), rồi phòng ban trong body thuộc phạm vi người gọi (403 `FORBIDDEN`, task 249), rồi giải trình khi lương đề xuất ngoài dải chuẩn (`SALARY_JUSTIFICATION_REQUIRED`), rồi định biên (409, task 274), giống tạo. Kể cả khi body giữ nguyên chức danh/phòng ban của nháp, chúng vẫn phải đang áp dụng, và phòng ban vẫn được kiểm phạm vi lần nữa sau khi bị khóa `FOR SHARE`.

Hai người sửa cùng một bản nháp cùng lúc được xếp hàng nhờ khóa dòng: người đến sau chờ người trước commit rồi ghi đè toàn bộ (người lưu sau cùng thắng). Chưa có kiểm tra phiên bản (optimistic locking), nên giao diện nên tải lại chi tiết trước khi sửa. Mọi lỗi đều không đổi dòng nào.

## Giải trình khi dải lương đề xuất ngoài chuẩn (task 247)

Kết quả Jira: so sánh với dải chuẩn của chức danh và yêu cầu nhập giải trình nếu đề xuất nằm ngoài chuẩn. Quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp), cho mọi vai trò được ghi (ADMIN, HR_MANAGER và người `SCOPED`).

- **Dải chuẩn** là `salary_min`–`salary_max` hiện tại của chức danh `positionId` trong body (bảng `positions` của V7), lấy qua `SalaryBandService` của task 206. **Hai đầu dải tính là trong chuẩn.** Ví dụ dải chuẩn 15.000.000–25.000.000: đề xuất 15.000.000–25.000.000 không cần giải trình; 14.999.999 hoặc 25.000.001 là ngoài chuẩn. Dải cố định (`salary_min = salary_max`) chỉ nhận đúng một mức đó.
- **Mỗi mức đã nhập được so riêng.** `proposedSalaryMin` hoặc `proposedSalaryMax` thấp hơn `salary_min` hoặc cao hơn `salary_max` là ngoài chuẩn, tính cả phía **thấp hơn** lẫn phía **cao hơn**. Dải đề xuất rộng hơn dải chuẩn (ví dụ 10.000.000–30.000.000) cũng là ngoài chuẩn.
- **Nháp vẫn được nhập một đầu** như task 244: chỉ đầu đã nhập được so. Chưa nhập mức lương nào thì không cần giải trình.
- Ngoài chuẩn mà `salaryJustification` không gửi, `null` hoặc không có ký tự nhìn thấy được (rỗng, chỉ có khoảng trắng hay ký tự rộng 0, như ở `POST`): 400 `SALARY_JUSTIFICATION_REQUIRED`, không lưu gì.
- Có giải trình (tối đa 2.000 ký tự, không chứa NUL như trước) thì lưu bình thường, văn bản giữ nguyên như người dùng nhập. Đề xuất trong chuẩn vẫn được nhập giải trình; giải trình đó được lưu, không bị xóa.
- **So ở mỗi lần lưu với dải hiện tại.** Nếu HR thu hẹp dải sau khi nháp đã lưu, `GET` vẫn trả nháp như cũ, nhưng lần `PUT` sau (kể cả gửi nội dung y hệt) phải có giải trình. `PUT` đổi sang chức danh khác thì so với dải của chức danh mới.
- Vì `PUT` thay toàn bộ nội dung, gửi giải trình rỗng trong khi lương vẫn ngoài chuẩn bị từ chối và giải trình đã lưu được giữ nguyên. Đưa lương về trong chuẩn thì xóa được giải trình.

```json
{
  "code": "SALARY_JUSTIFICATION_REQUIRED",
  "message": "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình.",
  "fieldErrors": {
    "salaryJustification": "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình."
  }
}
```

**Không lộ dải chuẩn.** Lời nhắn chỉ nói đề xuất nằm ngoài dải chuẩn: không có con số, không nói thấp hơn hay cao hơn. Response tạo, chi tiết và sửa giữ đúng 15 trường như trên; item danh sách thêm ba trường thời gian nhưng không có `salaryMin`/`salaryMax` của chức danh hoặc cờ "ngoài chuẩn". Điều này áp dụng cho mọi người gọi, kể cả HR_MANAGER (người có `SALARY_RANGES_READ_ALL` xem dải chuẩn ở [API chức danh](positions.md)). Trong code, `RequisitionService` chỉ nhận `BELOW`/`WITHIN`/`ABOVE` từ `SalaryBandService.compare`, không cầm con số của dải. Giới hạn còn lại: chính quy tắc này cho người gọi biết một mức lương cụ thể nằm trong hay ngoài dải (lưu được hay bị đòi giải trình), nên thử nhiều mức có thể dò ra hai đầu dải. Mỗi lần thử lưu được đều tạo hoặc sửa một bản nháp có ghi người tạo và thời điểm, nên việc dò để lại dấu vết. Nếu BA/PO cần chặn hẳn việc này thì phải đổi yêu cầu nghiệp vụ (ví dụ luôn bắt giải trình).

Thứ tự: đây là bước 8 của tạo và phần cuối bước 7 của sửa ở trên, chạy sau mọi bước kiểm khác. Chức danh không tồn tại hoặc đã ngừng áp dụng trả `INVALID_REQUISITION_POSITION`/`REQUISITION_POSITION_INACTIVE` trước, không bao giờ trả 404 `POSITION_NOT_FOUND` hay 409 `POSITION_INACTIVE` của `SalaryBandService`. Giải trình dài hơn 2.000 ký tự là `VALIDATION_ERROR` ở bước 2, không phải `SALARY_JUSTIFICATION_REQUIRED`. Người thiếu quyền, sửa nháp ngoài phạm vi hoặc chọn phòng ban ngoài phạm vi (task 249) nhận 403 trước khi lương được so.

**Đồng thời:** dải chuẩn được đọc sau khi dòng chức danh đã bị khóa `FOR SHARE` (bước 6 của tạo), trong cùng transaction ghi. `PUT /positions/{id}` của HR đổi dải lương phải chờ tạo/sửa nháp xong; ngược lại, nếu HR đang đổi dải dở dang, yêu cầu tạo/sửa chờ HR xong rồi so với dải HR đã commit (HR hủy thì so với dải cũ).

Quyết định của backend (chờ BA/PO xác nhận):

- Ngoài chuẩn tính cả **thấp hơn** dải, không chỉ cao hơn: tiêu chí story ghi "nằm ngoài dải chuẩn".
- Không bắt nhập đủ hai mức lương khi một mức đã có: nháp được lưu dở (task 244). Từ task 257, gửi duyệt mới bắt đủ thông tin (cả hai mức lương, ngày cần người, mô tả công việc, yêu cầu ứng viên) và chạy lại kiểm tra dải lương, ngày cần người, chức danh và phòng ban, xem [API duyệt yêu cầu tuyển dụng](requisition-approvals.md).
- Không thêm cờ kiểu `salaryOutsideStandardBand` vào response: giao diện biết qua lỗi 400; cờ phải tính lại ở mỗi `GET`/danh sách vì HR có thể đổi dải sau khi lưu. Nháp đã lưu không được kiểm lại khi HR đổi dải; nó được kiểm ở lần lưu sau, và gửi duyệt (task 257) kiểm lại với dải hiện tại, xem `requisition-approvals.md`.

## Ngày cần người không ở quá khứ (task 248)

Kết quả Jira: từ chối ngày cần người trước ngày hiện tại theo múi giờ nghiệp vụ. Quy tắc áp dụng **giống nhau** cho `POST` (tạo) và `PUT` (lưu lại nháp), cho mọi vai trò được ghi (ADMIN, HR_MANAGER và người `SCOPED`).

- **Hôm nay** là ngày hiện tại theo **múi giờ nghiệp vụ** `app.business-zone` (trong `src/main/resources/application.properties`, mặc định `Asia/Ho_Chi_Minh`, tức UTC+7), không phải ngày UTC và không phải múi giờ của JVM (README chạy server với `-Duser.timezone=UTC`). Từ 00:00 đến 07:00 giờ Việt Nam, ngày UTC vẫn là hôm trước. Ví dụ lúc `2026-10-06T17:30:00Z` ở Việt Nam đã là 00:30 ngày 07/10, nên `neededBy = "2026-10-06"` bị từ chối dù theo UTC vẫn đang là ngày 06/10.
- `neededBy` **trước** hôm nay: 400 `NEEDED_BY_IN_PAST`, không lưu gì. **Hôm nay** và mọi ngày sau được phép; không có giới hạn ngày xa nhất (ví dụ `9999-12-31` vẫn lưu được).
- `neededBy` không gửi hoặc `null` vẫn lưu được: nháp được để trống ngày cần người như task 244.
- **So ở mỗi lần lưu với ngày hiện tại.** Nháp lưu hôm qua với ngày cần người là hôm qua: `GET` vẫn trả như cũ, nhưng `PUT` lại (kể cả gửi nội dung y hệt) bị từ chối và dòng giữ nguyên; muốn lưu thì chọn ngày từ hôm nay trở đi hoặc để trống. Nháp đã lưu không tự bị sửa khi qua ngày.
- "Hôm nay" được tính **sau khi đã khóa tài khoản, phiên (và dòng yêu cầu khi sửa)**, không phải lúc request đến. Request gửi lúc 23:59:59 nhưng phải chờ **các khóa này** tới sau 00:00 thì so với ngày mới.
- Giới hạn sát nửa đêm: ngày được so **trước** khi khóa `FOR SHARE` dòng chức danh và phòng ban (bước 6 của tạo), vì thứ tự lỗi (`NEEDED_BY_IN_PAST` trước lỗi chức danh/phòng ban) được giữ có chủ ý. Nếu request so ngày xong rồi phải chờ khóa chức danh/phòng ban qua 00:00 (ví dụ HR đang sửa đúng chức danh đó bằng `PUT /positions/{id}`), hoặc qua 00:00 trong khoảng ngắn trước khi commit, thì ngày vừa lưu có thể đã sớm hơn hôm nay một ngày tính theo lúc commit. Lần `PUT` sau sẽ bị từ chối nếu ngày đó vẫn ở quá khứ; gửi duyệt (task 257) cũng kiểm lại ngày với hôm nay, xem `requisition-approvals.md`.

```json
{
  "code": "NEEDED_BY_IN_PAST",
  "message": "Ngày cần người không được trước ngày hôm nay.",
  "fieldErrors": {
    "neededBy": "Ngày cần người không được trước ngày hôm nay."
  }
}
```

Thứ tự: bước 5 của tạo (sau so hai mức lương, trước kiểm chức danh/phòng ban và giải trình lương) và trong bước 7 của sửa (cùng vị trí). Vì vậy ngày ở quá khứ được báo trước cả chức danh không tồn tại, phòng ban đã ngừng áp dụng hay lương ngoài dải chưa có giải trình. Lỗi từng trường (bước 2) vẫn trả trước, và `fieldErrors` của `VALIDATION_ERROR` không có `neededBy`. Ngày sai định dạng hoặc không tồn tại (`31/12/2026`, `2026-02-30`) vẫn là `INVALID_JSON` như trước. Người thiếu quyền hoặc sửa nháp ngoài phạm vi nhận 403, `PUT` UUID không tồn tại nhận 404, trước khi ngày được so. Riêng phòng ban trong body ngoài phạm vi (task 249) được kiểm sau ngày, nên ngày ở quá khứ được báo trước 403 đó.

**Cấu hình.** `app.business-zone` nhận tên múi giờ IANA như `Asia/Ho_Chi_Minh` (hoặc `UTC`, `+07:00`). Tên sai làm ứng dụng dừng ngay khi khởi động thay vì tính sai ngày. Mọi máy chủ chạy backend phải dùng cùng một giá trị. Không cần sửa `.env`. Trong code, `BusinessCalendar.today()` (gói `vn.ttcs.recruitment.common`) đọc `Clock` của ứng dụng rồi đổi sang ngày theo múi giờ này; `RequisitionService` chỉ so `neededBy` với ngày đó. Giao diện nên chặn sẵn ngày trước hôm nay (theo giờ Việt Nam) trên ô chọn ngày, nhưng backend vẫn là nơi kiểm cuối cùng.

Quyết định của backend (chờ BA/PO xác nhận):

- **Hôm nay được phép**: tiêu chí story là "không được ở quá khứ", và Jira ghi "trước ngày hiện tại".
- **Kiểm cả khi sửa nháp có ngày cũ không đổi**: yêu cầu tuyển dụng xin người cho hiện tại và tương lai, giống quy tắc chức danh/phòng ban phải đang áp dụng (task 246) và dải lương hiện tại (task 247). Gửi duyệt (task 257) kiểm lại ngày đã lưu với hôm nay, xem `requisition-approvals.md`.
- **Không giới hạn ngày xa nhất** và không bắt nhập ngày khi lưu nháp.
- **Một múi giờ nghiệp vụ chung** cho cả hệ thống (công ty ở Việt Nam), không theo múi giờ của từng người dùng hay trình duyệt.

## Kiểm tra định biên (task 274)

Story S3-04: "Vượt chỉ tiêu là cảnh báo chặn". Khi tạo hoặc sửa nháp, backend so yêu cầu với [định biên](headcount-plans.md) của **phòng ban trong body** cho **năm kế hoạch** của yêu cầu: năm của `neededBy`, chưa có ngày thì năm tạo yêu cầu theo múi giờ nghiệp vụ (khi sửa, vẫn là năm tạo ban đầu, không phải năm sửa).

- Phòng ban chưa có định biên cho năm đó: không giới hạn, không kiểm.
- Chỉ tiêu headcount: số người của các yêu cầu khác đã tính vào định biên đó (mọi yêu cầu ở trạng thái `DRAFT`, `PENDING_APPROVAL`, `RETURNED`, `APPROVED`, `PAUSED` hoặc `FILLED`, kể cả của người khác) cộng `headcount` của yêu cầu này không được vượt `headcountLimit`. Lấp đúng chỉ tiêu là được.
- Ngân sách (khi định biên có `salaryBudget`): chi phí năm của các yêu cầu khác cộng của yêu cầu này (`headcount` × lương đề xuất tối đa, thiếu thì tối thiểu, × 12; chưa đề xuất lương thì 0) không được vượt `salaryBudget`.
- Vượt chỉ tiêu: 409 `HEADCOUNT_LIMIT_EXCEEDED`, lời nhắn ví dụ "Phòng ban chỉ còn 0 chỉ tiêu headcount năm 2026 nhưng yêu cầu cần 1 người. Cần Trưởng phòng Nhân sự xác nhận vượt định biên kèm lý do." Nếu vượt cả ngân sách, lời nhắn thêm "Yêu cầu cũng vượt ngân sách lương năm 2026."
- Chỉ vượt ngân sách: 409 `SALARY_BUDGET_EXCEEDED`, "Yêu cầu vượt ngân sách lương năm 2026 của phòng ban. Cần Trưởng phòng Nhân sự xác nhận vượt định biên kèm lý do." Lời nhắn **không** nêu số tiền, vì chỉ HR được xem ngân sách.
- Lỗi 409 này áp dụng cho mọi người gọi, kể cả HR_MANAGER. Không có `fieldErrors`. Không dòng nào được lưu.

**Sửa nháp chỉ bị chặn khi xin thêm.** Lần sửa bị kiểm khi: nháp chuyển sang phòng ban khác hoặc năm kế hoạch khác (xin toàn bộ số người ở định biên mới), tăng số người (kiểm chỉ tiêu headcount) hoặc tăng chi phí năm (kiểm ngân sách). Sửa nội dung khác, giảm số người hay giảm lương không bao giờ bị chặn, kể cả khi phòng ban đang vượt định biên vì HR đã hạ chỉ tiêu sau khi nháp được lưu.

**Đồng thời:** bước này chạy cuối cùng, sau khi dòng phòng ban đã bị khóa `FOR SHARE`. Backend khóa dòng định biên `SELECT ... FOR UPDATE` rồi mới đếm, và giữ khóa tới khi lưu xong. Hai người lưu yêu cầu cho cùng phòng ban, cùng năm vào cùng lúc thì chạy lần lượt: khi định biên chỉ còn 1 chỗ, một người nhận 201, người kia nhận 409. HR sửa định biên cũng khóa cùng dòng nên không chen vào giữa lúc đếm và lúc lưu: lần lưu đang chờ sẽ đếm theo chỉ tiêu mới. Phòng ban chưa có định biên thì không khóa dòng định biên nào; khi HR tạo định biên mới, lần tạo đó chờ các lần lưu đang chạy của phòng ban commit xong (xem [API định biên](headcount-plans.md#khai-báo-định-biên)), nên không có nháp nào lọt qua lúc định biên vừa được tạo. Ngay sau khi giữ được khóa định biên, backend kiểm lại hạn JWT, phiên và quyền ghi (đọc lại từ database) trước khi quyết định 409; không còn quyền thì trả 401 `SESSION_INVALID` hoặc 403 `FORBIDDEN` và không lưu gì.

**Giới hạn đã biết:** lời nhắn `SALARY_BUDGET_EXCEEDED` không nêu số tiền, nhưng Trưởng bộ phận vẫn có thể đoán gần đúng ngân sách bằng cách thử lưu nháp nhiều lần với mức lương khác nhau (409 hay 200), vì họ xem được lương đề xuất của các nháp trong phòng ban mình. Đây là hệ quả của việc chặn theo ngân sách; đã ghi vào câu hỏi 13 cho BA/PO.

Giao diện nên gọi `GET /headcount-plans/remaining?departmentId=...&year=...` (năm của ngày cần người đang chọn) để hiện số còn lại trước khi lưu.

### Trưởng phòng Nhân sự xác nhận vượt định biên (task 275)

Story S3-04: "cần Trưởng phòng Nhân sự xác nhận ghi đè kèm lý do". Lần lưu vượt định biên vẫn được lưu khi body có `headcountOverrideReason` (có ít nhất một ký tự nhìn thấy được) **và** người gọi có `HEADCOUNT_PLANS_WRITE_ALL` (hiện chỉ HR_MANAGER). Backend ghi thêm một dòng vào lịch sử ngoại lệ (bảng `requisition_headcount_overrides`, V15): người xác nhận, thời điểm (bằng `createdAt` khi tạo, `updatedAt` khi sửa), lý do, định biên lúc đó (chỉ tiêu, ngân sách), số các yêu cầu khác đã dùng, số người và chi phí năm lần lưu này xin, và giới hạn nào bị vượt.

- Không có lý do (hoặc lý do không có ký tự nhìn thấy được: rỗng, chỉ có khoảng trắng hay ký tự rộng 0, như văn bản ở `POST`): vẫn 409 như trên, kể cả với HR, và không lưu ngoại lệ nào.
- Có lý do nhưng người gọi không có `HEADCOUNT_PLANS_WRITE_ALL` (Trưởng bộ phận, ADMIN...): 403 `FORBIDDEN`, không lưu gì. Quyền được đọc lại sau khi khóa tài khoản người gọi và một lần nữa sau khi giữ được khóa dòng định biên: nếu `HEADCOUNT_PLANS_WRITE_ALL` bị gỡ trong lúc request chờ khóa đó thì trả 403 `FORBIDDEN` và không lưu gì.
- Lần lưu không vượt định biên: lý do không cần và bị bỏ qua, không ghi lịch sử.
- Mỗi lần lưu vượt cần lý do riêng: HR đã xác nhận một lần thì nháp vẫn sửa được nội dung khác mà không cần lý do (không xin thêm), nhưng lần tăng tiếp theo cần xác nhận lần nữa.

Luồng gợi ý: Trưởng bộ phận lưu nháp và nhận 409, báo cho Trưởng phòng Nhân sự. HR mở nháp của phòng ban đó (HR có `REQUISITIONS_WRITE_ALL`) và lưu lại kèm lý do; với nháp mới bị chặn ngay khi tạo, HR tạo nháp cho phòng ban đó kèm lý do. Nháp đi theo phòng ban, không theo người tạo, nên Trưởng bộ phận vẫn xem và sửa tiếp được. HR cũng có thể tăng chỉ tiêu bằng `PUT /headcount-plans/{id}` thay vì ghi đè từng yêu cầu.

`GET /requisitions/{id}/headcount-overrides` trả lịch sử ngoại lệ của một yêu cầu, mới nhất trước, với cùng quyền như `GET /requisitions/{id}` nhưng chỉ theo phạm vi phòng ban, không gồm người tham gia duyệt và recruiter được phân công: người tham gia duyệt (task 262) hay recruiter được phân công (task 285) không phụ trách phòng ban đó vẫn bị 403. 404 nếu không có yêu cầu, 403 nếu ngoài phạm vi `SCOPED`:

```json
{
  "items": [
    {
      "id": "8d1f...",
      "departmentId": "00000000-0000-0000-0000-000000000002",
      "year": 2026,
      "reason": "Dự án chuyển đổi số cần thêm người ngay trong quý 4.",
      "overriddenBy": "00000000-0000-0000-0000-000000000001",
      "overriddenAt": "2026-10-10T03:00:00.123456Z",
      "headcountExceeded": true,
      "salaryBudgetExceeded": false,
      "headcountLimit": 3,
      "headcountUsed": 3,
      "requestedHeadcount": 2,
      "salaryBudget": 900000000,
      "salaryCostUsed": 0,
      "requestedSalaryCost": 600000000
    }
  ]
}
```

`departmentId` và `year` là định biên đã bị vượt lúc đó; yêu cầu có thể đã chuyển sang phòng ban hoặc năm khác sau này. Người chỉ có `REQUISITIONS_READ_SCOPED` chỉ thấy các dòng thuộc phòng ban mình phụ trách (kể cả phòng ban con), không thấy số liệu định biên của phòng ban khác dù yêu cầu đã chuyển về phòng mình. Ba trường tiền `salaryBudget`, `salaryCostUsed`, `requestedSalaryCost` chỉ có trong response khi người gọi có `HEADCOUNT_PLANS_READ_ALL`; với HR, `salaryBudget` cũng vắng mặt khi định biên lúc đó không có ngân sách. Lịch sử chỉ được thêm, không có API sửa hay xóa.

## Sao chép yêu cầu (task 278)

Story S3-05: "Sao chép toàn bộ mô tả công việc và yêu cầu ứng viên". `POST /requisitions/{id}/copy` tạo một **bản nháp mới** từ nội dung của yêu cầu `{id}`, trả **201** với yêu cầu mới (cùng dạng như `POST /requisitions`).

- Bản sao lấy từ bản gốc: chức danh, phòng ban, số lượng, lý do tuyển, hai mức lương đề xuất, giải trình lương, ngày cần người, mô tả công việc và yêu cầu ứng viên. Văn bản được giữ nguyên từng ký tự, kể cả xuống dòng.
- Bản sao có `id` mới, ở trạng thái `DRAFT`, người tạo là người gọi, `createdAt`/`updatedAt` là lúc sao chép. Bản gốc không bị đổi hay khóa.
- Task 279 ("Bản sao luôn bắt đầu ở trạng thái Nháp, không kế thừa lịch sử duyệt"): bản sao **không** lấy gì thuộc về quá trình xử lý của bản gốc. Không lấy trạng thái: bản gốc ở trạng thái nào cũng sao chép được, bản sao luôn là `DRAFT`. Không lấy người tạo và thời điểm. Không lấy lịch sử ngoại lệ định biên (task 275): bản sao được kiểm định biên như một yêu cầu mới, `GET /requisitions/{id}/headcount-overrides` của bản sao trống cho tới khi chính nó được HR xác nhận. Lịch sử duyệt (task 257, bảng `requisition_workflow_events`) gắn với `id` của yêu cầu, nên bản sao (có `id` mới) không có lượt duyệt, quyết định hay nhận xét nào.
- Ngày cần người của bản gốc đã qua (trước hôm nay theo múi giờ nghiệp vụ) thì bản sao để **trống** thay vì báo lỗi `NEEDED_BY_IN_PAST`; người dùng chọn ngày mới khi sửa bản sao. Ngày hôm nay hoặc sau đó được giữ nguyên. Khi bản sao chưa có ngày, nó tính vào định biên của năm tạo bản sao. Hai điểm này (bỏ ngày đã qua, và sao chép được bản gốc ở mọi trạng thái) là quyết định của backend, chờ PO xác nhận; khi có trạng thái mới, nhóm cần quyết định trạng thái đó có được sao chép không (ghi chú trong `RequisitionStatus`).
- Body không bắt buộc. Nếu có, chỉ nhận `{"headcountOverrideReason": "..."}` (task 275, cùng quy tắc như khi tạo); mọi trường khác trả 400 `INVALID_JSON`. Muốn đổi nội dung thì sửa bản sao bằng `PUT /requisitions/{id}` sau khi sao chép.

Quyền: cần quyền ghi như khi tạo (`REQUISITIONS_WRITE_ALL` hoặc `REQUISITIONS_WRITE_SCOPED`) **và** quyền xem bản gốc (`REQUISITIONS_READ_*`, người `SCOPED` chỉ sao chép được yêu cầu của phòng ban mình phụ trách, kể cả phòng ban con). Vì quyền ghi không bao gồm quyền đọc, người chỉ có quyền ghi nhận 403. Được phân công hay tham gia duyệt không đủ để sao chép (403; task 262, 285). Bản sao giữ phòng ban của bản gốc, nên phòng ban đó cũng phải thuộc phạm vi ghi.

Thứ tự kiểm:

1. Quyền ở `SecurityConfiguration` (thiếu quyền ghi thì 403); `{id}` không phải UUID trả 400 `VALIDATION_ERROR`; body sai trả 400 `INVALID_JSON` hoặc `VALIDATION_ERROR`.
2. Khóa tài khoản và phiên, kiểm lại quyền ghi (403 / 401 `SESSION_INVALID`).
3. Người gọi có quyền đọc yêu cầu tuyển dụng (`REQUISITIONS_READ_*`, nếu không thì 403 `FORBIDDEN`, kể cả với UUID không tồn tại, để người chỉ có quyền ghi không dò được UUID nào có thật), rồi bản gốc tồn tại (404 `REQUISITION_NOT_FOUND`), rồi phòng ban của bản gốc thuộc phạm vi đọc (403 `FORBIDDEN`).
4. Nội dung bản gốc vẫn qua được các kiểm tra của body tạo mới (`@Valid`). Dòng được lưu qua API luôn qua; dòng sửa tay hoặc lưu trước khi giới hạn bị hạ trả 400 `VALIDATION_ERROR`, `fieldErrors` nêu trường của bản gốc.
5. Các bước 4–9 của "Tạo bản nháp" với nội dung bản gốc (ngày cần người đã qua được bỏ trước, task 279): lương đề xuất, ngày cần người, chức danh và phòng ban còn áp dụng, phạm vi phòng ban, giải trình khi lương ngoài dải chuẩn **hiện tại**, định biên (409, hoặc HR kèm lý do). Vì vậy bản sao không bao giờ là một nháp mà `POST /requisitions` sẽ từ chối. Ví dụ: chức danh của bản gốc đã ngừng áp dụng thì trả 400 `REQUISITION_POSITION_INACTIVE`. Khi đó giao diện có thể đọc bản gốc bằng `GET /requisitions/{id}`, điền sẵn form tạo mới (giữ mô tả công việc và yêu cầu ứng viên), cho người dùng chọn chức danh khác rồi gọi `POST /requisitions`; API sao chép cố ý không nhận trường sửa nội dung để giữ một nghĩa đơn giản: "chép y nguyên".

Hạn JWT, phiên và quyền ghi được kiểm lại sau các khóa dòng chức danh, phòng ban và định biên của bước 5, như khi tạo (401 `SESSION_INVALID` hoặc 403 `FORBIDDEN` nếu không còn).

## Lỗi

| HTTP | Mã | Trường hợp |
|---|---|---|
|400|VALIDATION_ERROR|Body: thiếu `positionId`/`departmentId`/`headcount`/`reason`, số lượng ngoài 1–999, lý do tuyển không phải `REPLACEMENT`/`NEW_HEADCOUNT`, lương âm hoặc vượt 1.000.000.000.000, văn bản quá dài hoặc chứa ký tự NUL; lỗi theo trường nằm trong `fieldErrors`. Sao chép (task 278): nội dung bản gốc không còn qua được kiểm tra của body tạo mới, `fieldErrors` nêu trường của bản gốc. Tham số: `{id}` không phải UUID, `status`/`departmentId`/`recruiterId`/`startDate`/`endDate`/`page`/`size` sai định dạng (task 297 thêm bốn bộ lọc), `page` âm, `size` ngoài 1–100, `page × size` vượt 2.147.483.647|
|400|VALIDATION_ERROR|Danh sách (task 297): `startDate` sau `endDate`, hoặc năm của một trong hai ngày ngoài 1–9999; lời nhắn "Khoảng ngày cần người không hợp lệ."|
|400|INVALID_JSON|JSON sai; UUID hoặc ngày không đúng định dạng (ví dụ `2026-02-30`); lương hoặc `headcount` không phải số nguyên JSON (ví dụ `1.5`, `"2"`) hoặc vượt 2.147.483.647; `reason` là object/mảng; có trường ngoài hợp đồng|
|400|REQUISITION_SALARY_RANGE_INVALID|`proposedSalaryMin` lớn hơn `proposedSalaryMax`; `fieldErrors.proposedSalaryMax` có lời nhắn cho form|
|400|NEEDED_BY_IN_PAST|`neededBy` trước ngày hôm nay theo múi giờ nghiệp vụ `app.business-zone` (mặc định `Asia/Ho_Chi_Minh`); `fieldErrors.neededBy`|
|400|INVALID_REQUISITION_POSITION|Không có chức danh với `positionId`; `fieldErrors.positionId`|
|400|REQUISITION_POSITION_INACTIVE|Chức danh `positionId` đã ngừng áp dụng (`active = false`); `fieldErrors.positionId`|
|400|INVALID_REQUISITION_DEPARTMENT|Không có phòng ban với `departmentId`; `fieldErrors.departmentId`|
|400|REQUISITION_DEPARTMENT_INACTIVE|Phòng ban `departmentId` đã ngừng áp dụng (`active = false`); `fieldErrors.departmentId`|
|400|SALARY_JUSTIFICATION_REQUIRED|Một mức lương đề xuất đã nhập nằm ngoài dải lương chuẩn của chức danh và `salaryJustification` trống; `fieldErrors.salaryJustification`. Lời nhắn không chứa dải chuẩn|
|401|UNAUTHORIZED hoặc SESSION_INVALID|Thiếu, sai, hết hạn token; phiên bị thu hồi; người gọi bị khóa, kể cả khi điều này xảy ra lúc yêu cầu ghi đang chờ khóa|
|403|FORBIDDEN|Thiếu quyền của thao tác (bảng đầu trang); hoặc người `SCOPED` xem/sửa yêu cầu của phòng ban mình không phụ trách (trừ xem yêu cầu mà người đó tham gia duyệt, task 262, hoặc đang là recruiter chính hay hỗ trợ, task 285); hoặc người `SCOPED` tạo yêu cầu cho, hay chuyển nháp sang, phòng ban mình không phụ trách (task 249)|
|404|REQUISITION_NOT_FOUND|`GET`/`PUT`, lịch sử ngoại lệ hoặc sao chép với UUID không có yêu cầu nào|
|409|REQUISITION_NOT_DRAFT|`PUT` một yêu cầu không ở trạng thái `DRAFT` hay `RETURNED` (đang chờ duyệt, đã duyệt, bị từ chối, tạm dừng, đã tuyển đủ hoặc đã hủy)|
|409|HEADCOUNT_LIMIT_EXCEEDED|Lưu yêu cầu làm phòng ban vượt chỉ tiêu headcount của năm kế hoạch (task 274); lời nhắn nêu số chỉ tiêu còn lại|
|409|SALARY_BUDGET_EXCEEDED|Lưu yêu cầu làm phòng ban vượt ngân sách lương của năm kế hoạch (task 274); lời nhắn không nêu số tiền|
|403|FORBIDDEN|Lưu vượt định biên kèm `headcountOverrideReason` nhưng người gọi không có `HEADCOUNT_PLANS_WRITE_ALL` (task 275); sao chép khi không có quyền đọc yêu cầu tuyển dụng, bản gốc ngoài phạm vi đọc, hoặc phòng ban của bản gốc ngoài phạm vi ghi (task 278)|

Ví dụ lỗi dải lương đề xuất ngược:

```json
{
  "code": "REQUISITION_SALARY_RANGE_INVALID",
  "message": "Lương đề xuất tối thiểu không được lớn hơn lương đề xuất tối đa.",
  "fieldErrors": {
    "proposedSalaryMax": "Lương đề xuất tối đa phải lớn hơn hoặc bằng lương đề xuất tối thiểu."
  }
}
```

## Database và phạm vi

Dùng bảng `recruitment_requisitions` của V13 (task 243) và quyền `REQUISITIONS_*` có sẵn từ V3; không thêm migration, không đổi quyền, không cần sửa `.env`. Danh sách lọc theo chỉ mục `recruitment_requisitions_department_id_idx` và `..._status_idx` của V13. Các phòng ban người gọi phụ trách được tìm bằng một truy vấn đệ quy (`WITH RECURSIVE`) trên `departments.parent_id`; truy vấn dùng `UNION` nên vẫn dừng nếu dữ liệu sửa tay tạo vòng lặp cha–con. Các CHECK và khóa ngoại của V13 vẫn là lớp chặn cuối; API kiểm trước để trả lỗi tiếng Việt thay vì 500. Task 246 cũng không thêm migration: giới hạn 999 người và điều kiện chức danh/phòng ban đang áp dụng chỉ nằm ở API (`RequisitionRequest`, `RequisitionService`). Không có API xóa chức danh; API xóa phòng ban (task 197) không xóa phòng còn yêu cầu tuyển dụng chưa đóng. Dòng đã kiểm được giữ khóa `FOR SHARE` đến khi lưu xong, nên chức danh/phòng ban đã kiểm không bị xóa hay bị ngừng áp dụng trước khi lưu, kể cả bằng SQL tay (lệnh đó phải chờ khóa). Task 247 cũng không thêm migration và không đổi quyền: dải chuẩn đọc từ cột `salary_min`/`salary_max` của V7 qua `SalaryBandService`, giải trình lưu vào cột `salary_justification` có sẵn của V13. Task 248 cũng không thêm migration và không đổi quyền: `needed_by` vẫn là cột `DATE` (không có giờ) của V13; quy tắc phụ thuộc ngày hiện tại nên chỉ API kiểm, không phải CHECK của database (một nháp hợp lệ hôm nay sẽ "sai" vào ngày mai). Task 249 cũng không thêm migration và không đổi quyền: phạm vi phòng ban dùng lại truy vấn đệ quy ở trên với cột `manager_user_id`, `parent_id` của V5 và `department_id` của V13; nó phụ thuộc người gọi và cây phòng ban lúc lưu nên chỉ API kiểm, không phải CHECK hay khóa ngoại. Task 274 cũng không thêm migration và không đổi quyền: dùng bảng `headcount_plans` của V14 (task 272) và tính số đã dùng từ chính bảng `recruitment_requisitions`. Task 275 thêm V15 (bảng `requisition_headcount_overrides`, lịch sử ngoại lệ chỉ được thêm) và dùng lại quyền `HEADCOUNT_PLANS_WRITE_ALL` của V14, không thêm quyền mới. Task 278 không thêm migration và không đổi quyền: bản sao là một dòng mới của `recruitment_requisitions`, bản gốc chỉ được đọc. Task 297 không thêm migration; dùng chỉ mục phòng ban/trạng thái của V13 và khóa chính hoặc `requisition_recruiters_recruiter_id_idx` của V16; ba trường của task 298 tính khi đọc. Task 285 không thêm migration và không đổi quyền: điều kiện phân công là một truy vấn con cố định trên `requisition_recruiters` (`RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS`), cả danh sách lẫn kiểm tra một yêu cầu (`isAssigned`) đều dùng `requisition_recruiters_recruiter_id_idx` của V16: kiểm tra một yêu cầu đọc các dòng phân công của recruiter đó (thường rất ít) rồi so `requisition_id`, không dùng khóa chính `(requisition_id, recruiter_id)`; đổi lại danh sách và một bản ghi dùng chung một đoạn SQL nên không thể trả lời khác nhau. `created_at` và `needed_by` không có chỉ mục: với vài chục tới vài trăm yêu cầu, quét bảng rồi sắp xếp lấy một trang là đủ nhanh. Câu SQL của danh sách (`RequisitionListRepository`) chỉ ghép các đoạn cố định, mọi giá trị lọc đều là tham số bind, nên giá trị lọc không bao giờ thành SQL. Câu đó trả id của một trang và tổng số; `RequisitionService.list` đọc các yêu cầu theo id bằng JPA trong cùng transaction REPEATABLE READ, nên phạm vi, tổng số, id và nội dung đến từ một snapshot.

## Ghi chú cho frontend

Frontend nhánh `UI` (`src/services/business.service.ts`, `RequisitionList.tsx`) đã có màn danh sách và bộ lọc (task 295, 296):

| Frontend hiện tại | Backend | Cần sửa? |
|---|---|---|
|`getRequisitions` gửi `status`, `departmentId`, `recruiterId`, `startDate`, `endDate`, `page`, `size` và bỏ tham số rỗng|Đúng tên và ý nghĩa; `startDate`/`endDate` là ngày cần người, dạng `yyyy-MM-dd`, tính cả hai đầu|Không|
|`STATUSES` = `DRAFT, PENDING, APPROVED, REJECTED, OPEN, CLOSED, CANCELLED`|`DRAFT, PENDING_APPROVAL, RETURNED, APPROVED, REJECTED, PAUSED, FILLED, CANCELLED` (ba trạng thái cuối của story S3-07 có từ task 293; "Đã tuyển đủ" là `FILLED`). `PENDING`, `OPEN`, `CLOSED` trả 400|**Có** (một dòng)|
|`status` chỉ gửi một giá trị|Nhận nhiều giá trị (`status=A&status=B` hoặc `status=A,B`)|Tùy chọn|
|Tự tính `openDays`/`daysLeft`/`isOverdue` bằng date-fns theo giờ của trình duyệt|Dùng `daysOpen`, `daysUntilNeededBy` (`null` là chưa có ngày cần người) và `overdue` của item (ngày nghiệp vụ giờ Việt Nam; `false` với yêu cầu không còn đang tuyển, ví dụ bị từ chối). Thêm ba trường vào một kiểu `RequisitionListItem` (kiểu `Requisition` hiện chưa có). `GET /requisitions/{id}` không có ba trường này|Nên sửa|
|`recruiterId` đã khai báo nhưng chưa có ô chọn|Recruiter chính hiện tại; danh sách chọn lấy từ `GET /accounts?role=RECRUITER` (chỉ HR_MANAGER và ADMIN có `USER_ADMIN_READ_ALL`)|Tùy chọn|
|Ô chọn phòng ban liệt kê mọi phòng ban|Đúng phòng ban đã chọn, không gồm phòng ban con; ngoài phạm vi trả bảng rỗng, không bao giờ 403|Không|
|—|Spring bỏ qua tham số sai tên: lọc không có tác dụng, không báo lỗi|Lưu ý|

Task 285 (recruiter được phân công):

- `RequisitionList`: RECRUITER nay nhận các yêu cầu mình đang được phân công (chính hoặc hỗ trợ); frontend không cần sửa.
- Chi tiết yêu cầu của người chỉ xem qua phân công là **chỉ đọc**: chỉ hiện "Sửa", "Gửi duyệt", "Sao chép" khi người dùng có `REQUISITIONS_WRITE_ALL` hoặc phụ trách phòng ban của yêu cầu (tính từ `GET /departments/tree` như mục "Phạm vi dữ liệu"); `REQUISITIONS_WRITE_SCOPED` một mình không đủ (bấm sẽ nhận 403 rồi bị chuyển sang `/unauthorized`). Nút đổi trạng thái và "Phân công" vẫn theo `REQUISITIONS_WRITE_ALL`; quyết định duyệt theo `actions.canDecide`.
- Không gọi `/headcount-overrides` hay `/headcount-plans/remaining` cho yêu cầu như vậy (403); frontend nhánh `UI` hiện không gọi.
- Sau khi bàn giao, lần tải tiếp theo của recruiter cũ nhận 403, và interceptor chuyển sang `/unauthorized`. Gợi ý: trang chi tiết bắt 403 và hiện "Bạn không còn được phân công yêu cầu này" kèm liên kết về danh sách (frontend quyết định).
- `JobPostingForm.tsx` (`jobPostingService.getRequisitionById` gọi `GET /requisitions/{id}`) nay chạy được với recruiter được phân công (trước đây 403 rồi bị chuyển trang).

## Giả định chờ BA/PO

Các quy tắc của task 297 và 298 là giả định của backend, liệt kê ở câu hỏi 17 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md). Gồm:

- khoảng ngày lọc theo ngày cần người (`neededBy`), tính cả hai đầu, tham số `startDate`/`endDate` như frontend; yêu cầu chưa có ngày cần người bị loại khi có một đầu; chưa lọc theo ngày tạo;
- "recruiter phụ trách" là recruiter chính hiện tại; recruiter hỗ trợ không khớp; sau bàn giao chỉ người mới khớp;
- lọc phòng ban chỉ đúng phòng ban đó, không gồm phòng con;
- bộ lọc chỉ thu hẹp phạm vi: lọc phòng ban ngoài phạm vi nhận trang rỗng (không 403); lọc theo recruiter chính không mở thêm phạm vi, phạm vi theo phân công là task 285 (câu hỏi 19);
- lọc được nhiều trạng thái một lúc;
- trễ hạn chỉ với yêu cầu còn đang tuyển (nháp, chờ duyệt, cần bổ sung, đã duyệt) khi ngày cần người đã qua; đến hạn hôm nay chưa trễ; bị từ chối không bao giờ trễ, và các trạng thái tạm dừng, đã tuyển đủ, đã hủy của story S3-07 (task 293) cũng không. Điều này thu hẹp định nghĩa của task 298 (TRAN DUC LUONG) cho các trạng thái chưa có lúc bạn ấy làm;
- "số ngày mở" tính từ ngày tạo (ngày tạo = 0) với mọi trạng thái và vẫn tăng sau khi kết thúc;
- hộp chờ duyệt giữ 15 trường của yêu cầu như trước (task 267); ba trường thời gian chỉ có ở danh sách;
- chưa có bộ lọc "chỉ yêu cầu trễ hạn".

Task 285 (câu hỏi 19 trong [ma trận vai trò và quyền](../architecture/role-permission-matrix.md)), backend tạm áp dụng:

- "vị trí được phân công" là yêu cầu tuyển dụng, không phải chức danh trong danh mục;
- recruiter chính và hỗ trợ đều xem được yêu cầu, danh sách, phân công, lịch sử phân công (kể cả ghi chú và tên recruiter trước), tiến độ và lịch sử duyệt (kể cả nhận xét người duyệt và lý do tạm dừng, đóng, hủy), lý do tuyển và giải trình lương; chưa ẩn trường nào với người chỉ xem theo phân công;
- xem ở mọi trạng thái, cho tới khi bị bàn giao hoặc bỏ; mất quyền ngay khi lần bàn giao hoặc bỏ đó được lưu;
- không mở rộng: ngoại lệ định biên, số còn lại của định biên, sao chép, sửa, gửi duyệt, quyết định duyệt, đổi trạng thái, phân công;
- theo mã quyền, không theo tên vai trò;
- danh sách hiện cả yêu cầu được phân công; người tham gia duyệt vẫn không thấy yêu cầu của họ trong danh sách;
- hồ sơ ứng viên chờ module ứng viên, sẽ dùng cùng quy tắc.
