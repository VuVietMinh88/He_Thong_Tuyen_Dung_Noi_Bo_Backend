# Phát hiện và gộp hồ sơ ứng viên trùng

Phạm vi story TKNHTTDNB1-49 (S5-01): "Là Nhân viên tuyển dụng, tôi muốn được cảnh báo và gộp hồ sơ ứng viên trùng, để không gọi phỏng vấn hai lần cho cùng một người." Xây trên mô hình hồ sơ ứng viên sẵn có của task 360 (`candidates`, `candidate_applications`, xem [hồ sơ ứng viên hợp nhất](candidate-profiles.md)), không tạo mô hình danh tính song song.

Tiêu chí của story:

- "Phát hiện trùng theo email và số điện thoại khi có hồ sơ mới": task 367, [Gợi ý ứng viên trùng](#gợi-ý-ứng-viên-trùng-task-367).
- "Hiển thị so sánh cạnh nhau trước khi gộp": chưa có (task 368).
- "Gộp giữ lại toàn bộ lịch sử ứng tuyển của cả hai hồ sơ": chưa có (task 368).
- "Gộp nhầm có thể hoàn tác trong 24 giờ": chưa có (task 369 lưu dấu vết, task 370 hoàn tác).

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
