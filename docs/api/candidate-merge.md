# Phát hiện và gộp hồ sơ ứng viên trùng

Phạm vi story TKNHTTDNB1-49 (S5-01): "Là Nhân viên tuyển dụng, tôi muốn được cảnh báo và gộp hồ sơ ứng viên trùng, để không gọi phỏng vấn hai lần cho cùng một người." Xây trên mô hình hồ sơ ứng viên sẵn có của task 360 (`candidates`, `candidate_applications`, xem [hồ sơ ứng viên hợp nhất](candidate-profiles.md)), không tạo mô hình danh tính song song.

Tiêu chí của story:

- "Phát hiện trùng theo email và số điện thoại khi có hồ sơ mới": task 367, [Gợi ý ứng viên trùng](#gợi-ý-ứng-viên-trùng-task-367).
- "Hiển thị so sánh cạnh nhau trước khi gộp": task 368, [Xem trước khi gộp](#xem-trước-khi-gộp-task-368).
- "Gộp giữ lại toàn bộ lịch sử ứng tuyển của cả hai hồ sơ": task 368, [Gộp hồ sơ](#gộp-hồ-sơ-task-368).
- "Gộp nhầm có thể hoàn tác trong 24 giờ": dấu vết và dữ liệu hoàn tác có từ task 369, [Dấu vết gộp](#dấu-vết-gộp-và-dữ-liệu-hoàn-tác-task-369); API hoàn tác chưa có (task 370).

## Gợi ý ứng viên trùng (task 367)

### Thế nào là "có thể trùng"

Các lần ứng tuyển cùng **email đã chuẩn hóa** vốn đã thuộc một hồ sơ ứng viên ngay khi nộp (task 360). Task 367 tìm những gì việc liên kết tự động đó không thấy: một **hồ sơ ứng viên khác** có lần ứng tuyển trùng với một lần ứng tuyển của hồ sơ đang xem ở

- **email hiệu lực đã chuẩn hóa**, hoặc
- **số điện thoại hiệu lực ở dạng trong nước**: `+84…` được đọc là `0…`, cùng biểu thức với tìm kiếm ứng viên và kiểm tra trùng khi nhập tay (`CandidateSearchRepository.NATIONAL_PHONE`); dấu cách, gạch nối, dấu chấm và ngoặc đã bị bỏ khi lưu (`ApplicationSubmission`).

"Hiệu lực" là liên hệ recruiter đã xác nhận cho lần ứng tuyển đó (task 335, `application_confirmed_contacts`, xem [xác nhận liên hệ](confirmed-contacts.md)) nếu có, nếu không là bản ứng viên tự nộp. Khi đã xác nhận, giá trị đã nộp **không còn** được dùng để so khớp. Email xác nhận được chuẩn hóa như email nộp (bỏ ký tự vô hình ở hai đầu, viết thường theo `Locale.ROOT`), nên `LAN@example.test` khớp `lan@example.test`. Không so họ tên, không bỏ dấu chấm hay phần `+tag` của email.

Gợi ý được tính **khi đọc**: không có job nền, không lưu kết quả. Một hồ sơ vừa nộp xong được so ngay ở lần đọc tiếp theo, nên recruiter mở hồ sơ ứng viên là thấy cảnh báo.

### API

`GET /api/v1/candidates/{id}/duplicates`

Quyền: `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED` (ADMIN, HR_MANAGER, RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER). Thứ tự kiểm tra giống API hồ sơ hợp nhất ([ai được xem](candidate-profiles.md#ai-được-xem-task-363)):

1. Token, phiên hoặc tài khoản không hợp lệ: 401. Không có quyền đọc CANDIDATES: 403, trước mọi tra cứu (id lạ hay id có thật trả như nhau). Không ghi nhật ký.
2. Hồ sơ ứng viên không có lần ứng tuyển nào (id lạ): 404 `CANDIDATE_NOT_FOUND`, không ghi nhật ký.
3. Không lần ứng tuyển nào của hồ sơ nằm trong phạm vi người xem (`CandidateAccess.READABLE`): 403, ghi một dòng `DENIED` loại `PROFILE` không kèm lần ứng tuyển.
4. Ngược lại 200. Mỗi hồ sơ ứng viên được liệt kê ghi một dòng `GRANTED` loại `PROFILE` cho mỗi lần ứng tuyển được trả. Hồ sơ đang xem không lộ dữ liệu của chính nó nên không ghi thêm.

**Phạm vi ở cả hai phía:** chỉ những lần ứng tuyển của hồ sơ đang xem mà người xem đọc được mới cung cấp email và số điện thoại để so, và chỉ những lần ứng tuyển đọc được của hồ sơ khác mới được trả. Người xem không biết thêm gì về lần ứng tuyển mình không mở được.

Response 200 (`Cache-Control: no-store`):

```json
{
  "candidateId": "6b0f…",
  "duplicates": [
    {
      "candidateId": "c41e…",
      "matchedBy": ["PHONE"],
      "applications": [
        {
          "applicationId": "9a2d…",
          "requisitionId": "51c7…",
          "postingTitle": "Lập trình viên Java",
          "fullName": "Trần Thị Mai",
          "stage": "NEW",
          "stageName": "Hồ sơ mới",
          "receivedAt": "2026-10-15T03:00:00Z",
          "matchedBy": ["PHONE"]
        }
      ],
      "moreApplications": false
    }
  ],
  "truncated": false
}
```

- `duplicates`: tối đa 20 hồ sơ ứng viên, hồ sơ có lần ứng tuyển khớp mới nhất trước; `truncated = true` khi còn hồ sơ khớp không được liệt kê.
- `matchedBy` của một hồ sơ: `EMAIL` và/hoặc `PHONE`, tính trên mọi lần ứng tuyển khớp đọc được của hồ sơ đó (kể cả lần không được liệt kê); của một lần ứng tuyển: lý do của chính lần đó.
- `applications`: tối đa 10 lần ứng tuyển khớp của mỗi hồ sơ, mới nhất trước; `moreApplications = true` khi còn lần khớp khác.
- `fullName` là họ tên hiệu lực (đã xác nhận nếu có). Không trả email, số điện thoại, CV, mã tra cứu hay lần ứng tuyển của hồ sơ đang xem.
- `id` không phải UUID: 400 `VALIDATION_ERROR`.

### Hiệu năng

Câu SQL đọc toàn bộ `job_applications` nối liên hệ đã xác nhận (biểu thức hiệu lực đi qua phép nối nên không dùng được chỉ mục V65_2), như kiểm tra trùng khi nhập tay đọc theo email. Với quy mô tuyển dụng nội bộ thì đủ; nếu bảng lớn lên, tách phần không có liên hệ xác nhận để dùng chỉ mục V65_2 và thêm chỉ mục cho `job_applications.normalized_email`.

## Dấu vết gộp và dữ liệu hoàn tác (task 369)

Task 369 thêm phần dữ liệu (migration V68_2) và lớp đọc/ghi `CandidateMergeRecords`; API gộp ở task 368. Gộp hồ sơ ứng viên **nguồn** vào hồ sơ **đích** là chuyển các dòng `candidate_applications` của nguồn sang đích (task 368): mỗi lần ứng tuyển giữ nguyên dòng `job_applications`, CV, lịch sử giai đoạn, đánh giá, offer… của chính nó, nên lịch sử ứng tuyển của cả hai hồ sơ còn đủ. Dấu vết lưu đúng những gì cần để đặt mọi liên kết về như cũ, không lưu bản chụp JSON của hồ sơ.

### Schema (V68_2)

| Bảng / cột | Nội dung |
|---|---|
| `candidates.merged_into`, `merged_at` | Hồ sơ đã bị gộp vẫn giữ dòng của nó và chỉ tới hồ sơ đích, cùng thời điểm gộp. Cả hai cùng có hoặc cùng trống (CHECK `candidates_merge_complete`), không bao giờ chỉ vào chính nó (`candidates_not_merged_into_itself`). Email chuẩn hóa của nó vẫn unique, nên một hồ sơ nộp sau với email đó vẫn tìm thấy dòng này rồi đi theo `merged_into` (task 368). Gộp một hồ sơ cũng chuyển hướng các hồ sơ đã gộp vào nó sang đích mới, nên `merged_into` luôn chỉ một bước. |
| `candidate_merges` | Một dòng cho mỗi lần gộp: `target_candidate_id`, `source_candidate_id` (khác nhau), `reason` (tùy chọn, tối đa 1000 ký tự, có ký tự hiển thị), `merged_by`, `merged_at`, `undo_deadline` (sau `merged_at`), `undone_by`, `undone_at` (cùng có hoặc cùng trống; `merged_at ≤ undone_at < undo_deadline`). Chỉ mục unique một phần `candidate_merges_live_source_idx`: một hồ sơ chỉ bị gộp đi một lần tại một thời điểm, hoàn tác thì được gộp lại. Khóa tới nhân viên và hồ sơ ứng viên là `RESTRICT`. |
| `candidate_merge_applications` | Các lần ứng tuyển lần gộp đã chuyển, mỗi lần kèm `linked_at` cũ (thời điểm nó được liên kết với nguồn), để hoàn tác chuyển đúng những lần đó về và trả lại thời điểm liên kết. Chỉ lưu id, không khóa ngoại: lần ứng tuyển bị xóa sau đó (liên kết bị xóa theo, V32) vẫn để lại id trong lịch sử và hoàn tác bỏ qua nó. |
| `candidate_merge_redirects` | Các hồ sơ đã gộp vào nguồn trước đó và được chuyển hướng sang đích, mỗi hồ sơ kèm `merged_at` của lần gộp của chính nó, để hoàn tác chỉ chúng về lại nguồn. |

**Chỉ ghi thêm.** Trigger chặn mọi `UPDATE`/`DELETE` trên ba bảng, trừ đúng một thay đổi: lần hoàn tác duy nhất điền `undone_by` và `undone_at` của một lần gộp chưa hoàn tác, mọi cột khác giữ nguyên (như V67_1 và các lịch sử chỉ ghi thêm của V20, V33). Lỗi dùng mã `23514`. Test làm rỗng bảng bằng `TRUNCATE` (không kích hoạt trigger theo dòng).

`CandidateMergeRecords` (mọi phương thức `MANDATORY`, chạy trong transaction của người gọi): `record` ghi lần gộp cùng chi tiết (không lặp id), `find` / `lock` (`FOR UPDATE`, để hai lần hoàn tác cùng một lần gộp chạy lần lượt), `movedApplications`, `redirects`, `markUndone` (trả `false` nếu đã hoàn tác) và `involving` (các lần gộp có hồ sơ là đích hoặc nguồn, mới nhất trước). `CandidateMerge.undoableAt(t)` đúng khi chưa hoàn tác và `t` còn trước hạn; đúng hạn là đã muộn.

## Xem trước khi gộp (task 368)

`GET /api/v1/candidates/{id}/merge-preview?sourceCandidateId={sourceId}`

`{id}` là hồ sơ **đích** (được giữ lại), `sourceCandidateId` là hồ sơ **nguồn** (sẽ gộp vào đích). Quyền như gợi ý trùng: `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED`. Thứ tự kiểm tra:

1. 401 / 403 không có quyền đọc, trước mọi tra cứu, không ghi nhật ký.
2. Thiếu `sourceCandidateId`: 400 `VALIDATION_ERROR`. Hai id giống nhau: 400 `CANDIDATE_MERGE_SAME_CANDIDATE`. Id không phải UUID: 400 `VALIDATION_ERROR`.
3. Một trong hai hồ sơ không có (không có dòng, hoặc chưa bị gộp mà không còn lần ứng tuyển nào): 404 `CANDIDATE_NOT_FOUND`, không ghi nhật ký.
4. Một bên không có lần ứng tuyển nào người xem đọc được: 403, ghi một dòng `DENIED` loại `PROFILE` (không kèm lần ứng tuyển) cho **mỗi** bên bị từ chối. Hồ sơ đã bị gộp không còn lần ứng tuyển của riêng nó, nên đọc được khi hồ sơ nó đã gộp vào đọc được.
5. Ngược lại 200. Mỗi lần ứng tuyển được trả ghi một dòng `GRANTED` loại `PROFILE` cho bên của nó.

Response 200 (`Cache-Control: no-store`):

```json
{
  "target": {
    "candidateId": "6b0f…",
    "mergedInto": null,
    "mergedAt": null,
    "fullNames": ["Nguyễn Thị Lan"],
    "emails": ["lan@example.test"],
    "phones": ["0912345678"],
    "applications": [
      {
        "applicationId": "1f3c…",
        "requisitionId": "51c7…",
        "postingTitle": "Lập trình viên Java",
        "departmentName": "Phòng Công nghệ",
        "fullName": "Nguyễn Thị Lan",
        "email": "lan@example.test",
        "phone": "0912345678",
        "stage": "SCREENING",
        "stageName": "Sàng lọc",
        "receivedAt": "2026-10-14T03:00:00Z",
        "sameRequisition": true
      }
    ]
  },
  "source": { "candidateId": "c41e…", "mergedInto": null, "mergedAt": null, "fullNames": ["Trần Thị Mai"], "emails": ["mai@example.test"], "phones": ["+84912345678"], "applications": [ … ] },
  "matchedBy": ["PHONE"],
  "sharedRequisitionIds": ["51c7…"],
  "mergeable": true,
  "blockers": []
}
```

- Mỗi bên chỉ liệt kê các lần ứng tuyển người xem đọc được, mới nhất trước, với liên hệ **hiệu lực** (đã xác nhận nếu có). `fullNames`, `emails`, `phones`: các giá trị khác nhau của các lần đó.
- `matchedBy`: hai bên trùng email hiệu lực và/hoặc số điện thoại dạng trong nước (như task 367), trên các lần ứng tuyển được trả. Rỗng nếu recruiter muốn gộp hai hồ sơ không trùng gì: API vẫn cho xem và cho gộp, quyết định là của người gộp.
- `sharedRequisitionIds` và `sameRequisition`: yêu cầu tuyển dụng có lần ứng tuyển ở cả hai bên. Sau khi gộp, hồ sơ đích có hai lần ứng tuyển ở yêu cầu đó; cả hai được giữ nguyên.
- `mergeable = false` khi lần gộp của chính người xem lúc này sẽ bị từ chối; `blockers` nói lý do:

| Blocker | Nghĩa | Lỗi khi gộp |
|---|---|---|
| `TARGET_MERGED`, `SOURCE_MERGED` | Bên đó đã bị gộp vào hồ sơ khác (`mergedInto`, `mergedAt`); `applications` rỗng | 409 `CANDIDATE_ALREADY_MERGED` |
| `NOT_WRITABLE` | Người xem không có quyền ghi CANDIDATES, hoặc không ghi được một lần ứng tuyển nào đó của hai hồ sơ (kể cả lần mình không đọc được) | 403 |
| `SOURCE_ACTIVE_OFFER` | Nguồn đang giữ offer chưa kết thúc (`DRAFT` … `ACCEPTED`) | 409 `CANDIDATE_MERGE_ACTIVE_OFFER` |
| `SOURCE_IN_TALENT_POOL` | Nguồn đang nằm trong kho tiềm năng | 409 `CANDIDATE_MERGE_TALENT_POOL` |

Hai blocker cuối chỉ được tính khi người xem ghi được mọi lần ứng tuyển của cả hai hồ sơ, để bản xem trước không cho recruiter biết về offer ngoài phạm vi của mình. Bản xem trước là ảnh chụp: lần gộp kiểm tra lại tất cả dưới khóa.

## Gộp hồ sơ (task 368)

`POST /api/v1/candidates/{id}/merge`

```json
{ "sourceCandidateId": "c41e…", "reason": "Cùng số điện thoại, đã gọi xác nhận." }
```

Gộp hồ sơ nguồn vào hồ sơ đích `{id}`. `reason` tùy chọn (tối đa 1000 ký tự, văn bản thuần, được xuống dòng; ký tự vô hình ở hai đầu bị bỏ). Người gộp là chủ token, không phải một trường; trường lạ: 400 `INVALID_JSON`. Quyền: `CANDIDATES_WRITE_ALL` hoặc `CANDIDATES_WRITE_SCOPED`.

Thứ tự kiểm tra:

1. Body sai: 400 `VALIDATION_ERROR` (thiếu `sourceCandidateId`, lý do quá dài hoặc có HTML/ký tự điều khiển) hoặc `INVALID_JSON`. Hai id giống nhau: 400 `CANDIDATE_MERGE_SAME_CANDIDATE`.
2. Token, phiên, tài khoản: 401 `SESSION_INVALID`. Không có quyền đọc và ghi CANDIDATES (đọc lại từ cơ sở dữ liệu): 403.
3. Một trong hai hồ sơ không có: 404 `CANDIDATE_NOT_FOUND`.
4. Người gộp phải ghi được **mọi** lần ứng tuyển của cả hai hồ sơ: đọc được (`CandidateAccess.READABLE`), và với `CANDIDATES_WRITE_SCOPED` thì đang là recruiter phụ trách yêu cầu tuyển dụng của lần đó (luật của `CandidateWriteGuard.requireWritable`). Thiếu một lần: 403. Lần gộp đổi hồ sơ của từng lần ứng tuyển nên không cho gộp một phần.
5. Một trong hai hồ sơ đã bị gộp: 409 `CANDIDATE_ALREADY_MERGED`.
6. Nguồn đang giữ offer chưa kết thúc: 409 `CANDIDATE_MERGE_ACTIVE_OFFER`. Nguồn đang trong kho tiềm năng: 409 `CANDIDATE_MERGE_TALENT_POOL`. Offer và mục kho tiềm năng ghi id hồ sơ ứng viên của chúng (`offers.candidate_id` không đổi được, V61), nên lần ứng tuyển của chúng không được rời hồ sơ đó khi còn hiệu lực. Có thể gộp theo chiều ngược lại (hồ sơ đang có offer làm đích).
7. Chờ khóa quá lâu, deadlock: 503 `CANDIDATE_MERGE_BUSY`, không thay đổi gì.

Response 200 (`Cache-Control: no-store`):

```json
{
  "mergeId": "0d4e…",
  "targetCandidateId": "6b0f…",
  "sourceCandidateId": "c41e…",
  "mergedAt": "2026-10-22T04:00:00Z",
  "undoDeadline": "2026-10-23T04:00:00Z",
  "movedApplicationIds": ["9a2d…"],
  "redirectedCandidateIds": []
}
```

Trong một transaction:

- Mọi dòng `candidate_applications` của nguồn chuyển sang đích (`linked_at` là thời điểm gộp; thời điểm cũ nằm trong dấu vết). Mỗi lần ứng tuyển giữ nguyên dòng, giai đoạn, CV, đánh giá, lịch sử… nên hồ sơ hợp nhất của đích liệt kê lịch sử ứng tuyển của cả hai.
- Nguồn có `merged_into = đích`, `merged_at` = thời điểm gộp. Các hồ sơ đã gộp vào nguồn trước đó được chuyển hướng sang đích (`redirectedCandidateIds`), giữ `merged_at` của chính chúng.
- Dấu vết task 369 được ghi với `undo_deadline = merged_at + 24 giờ` (theo `Clock` của ứng dụng).
- Hồ sơ nguồn không còn lần ứng tuyển nên biến khỏi gợi ý trùng; bản xem trước của nó trả `SOURCE_MERGED`.

**Hồ sơ nộp sau khi gộp.** `CandidateLinks` (task 360) đi theo `merged_into`: một lần ứng tuyển mới với email của nguồn (hoặc của hồ sơ đã chuyển hướng) vào thẳng hồ sơ đích. Nó khóa hồ sơ đích `FOR SHARE` rồi kiểm tra lại email vẫn dẫn tới đó, nên hồ sơ nộp đúng lúc đang gộp vào đích, hồ sơ nộp đúng lúc đang hoàn tác vào nguồn đã được khôi phục.

**Khóa.** Tài khoản và phiên của người gộp, rồi các yêu cầu tuyển dụng và lần ứng tuyển của cả hai hồ sơ `FOR NO KEY UPDATE` theo thứ tự id (thứ tự chung của mọi thao tác ghi một lần ứng tuyển, nên chuyển giai đoạn, tạo offer hay thêm vào kho tiềm năng chờ lần gộp hoặc ngược lại), rồi các dòng `candidates` (đích, nguồn, các hồ sơ đã gộp vào nguồn) `FOR UPDATE` theo thứ tự id. Liên kết được đọc lại dưới khóa hồ sơ; lần ứng tuyển vừa liên kết xen giữa được khóa thêm. Quyền đọc và ghi được đọc lại sau các lần chờ khóa.

**Offer cũ của nguồn.** Offer đã kết thúc (từ chối, hết hạn, hủy…) của một lần ứng tuyển được chuyển vẫn ghi `candidate_id` của nguồn, vì cột này không đổi được.
