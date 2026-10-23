# Mở rộng hồ sơ ứng viên (Sprint 5–6, lane ứng viên)

Tài liệu này mô tả các API nội bộ mới quanh hồ sơ ứng viên: tìm kiếm nhiều điều kiện, bộ lọc đã lưu, xuất Excel, thêm
hồ sơ thủ công, điểm phù hợp và kho ứng viên tiềm năng. Mọi API dùng Bearer access token, trả `Cache-Control: no-store`,
lỗi theo dạng chung `{code, message, fieldErrors}` và đọc lại quyền hiện tại trong database ở mỗi lần gọi (không tin
quyền hoặc userId gửi từ client). Phạm vi hồ sơ luôn là `CandidateAccess.READABLE`: `CANDIDATES_READ_ALL` (ADMIN,
HR_MANAGER) đọc mọi hồ sơ; `CANDIDATES_READ_SCOPED` chỉ đọc hồ sơ của requisition mình đang là recruiter phụ trách
(PRIMARY/SUPPORTING) hoặc của phòng ban mình quản lý (kể cả phòng ban con). Mỗi hồ sơ hiển thị được ghi một dòng
`candidate_access_events` loại `PROFILE`.

## 392 — Tìm kiếm ứng viên nhiều điều kiện

`GET /api/v1/candidate-search`

Quyền: `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED`. Không có quyền: 403 trước mọi truy vấn; thiếu token: 401.

Tham số query (tất cả không bắt buộc, danh sách lặp lại tham số, ví dụ `stage=NEW&stage=SCREENING`):

| Tham số | Kiểu | Ý nghĩa |
|---|---|---|
| `requisitionId` | UUID | Chỉ hồ sơ của yêu cầu tuyển dụng này. Không tồn tại: 404 `REQUISITION_NOT_FOUND`; ngoài phạm vi: 403. |
| `positionId` | UUID | Chỉ hồ sơ của các requisition thuộc vị trí này. Không tồn tại: 404 `POSITION_NOT_FOUND`; SCOPED mà không có requisition nào đọc được: 403. |
| `stage` | lặp, enum | `NEW, SCREENING, INTERVIEW, OFFER, HIRED, ONBOARDED, REJECTED, WITHDRAWN`. Bỏ trống = mọi giai đoạn trừ `WITHDRAWN` (giống bảng Kanban). Tối đa 20. |
| `sourceId` | lặp, UUID | Nguồn ứng viên (catalog `CANDIDATE_SOURCE`, kể cả giá trị đã ngừng dùng). Id không phải nguồn: 400. Tối đa 20. |
| `tagId` | lặp, UUID | Nhãn sàng lọc (catalog `CANDIDATE_TAG`). Id không phải nhãn: 400. Tối đa 20. |
| `tagMatch` | `ANY` \| `ALL` | `ANY` (mặc định): có ít nhất một nhãn đã chọn; `ALL`: có đủ mọi nhãn đã chọn. |
| `submittedFrom`, `submittedTo` | `yyyy-MM-dd` | Ngày nộp theo giờ Việt Nam (`app.business-zone`), tính cả hai đầu; từ 2000-01-01 đến 2100-12-31; `from <= to`. |
| `minRating`, `maxRating` | 1..5 | Mức đánh giá sàng lọc chung; hồ sơ chưa đánh giá bị loại khi có điều kiện này; `min <= max`. |
| `keyword` | 2..100 ký tự | Tìm trong họ tên (không phân biệt hoa thường và dấu tiếng Việt: `nguyen van` tìm ra `Nguyễn Văn`), email (một phần, không phân biệt hoa thường) và số điện thoại (khi từ khóa chỉ gồm chữ số, khoảng trắng, `.`, `-`, `()`, `+`, ít nhất 3 chữ số; `+84…` và `0…` được coi là cùng một số, ở cả từ khóa lẫn số đã lưu). `%`, `_`, `!` được tìm đúng như ký tự gõ vào, không phải ký tự đại diện. Khoảng trắng không ngắt dòng (U+00A0, U+2007, U+202F…) được coi là khoảng trắng; ký tự ẩn ở hai đầu bị bỏ, từ khóa chỉ gồm ký tự ẩn được coi là không có từ khóa. Không chấp nhận ký tự điều khiển/ẩn ở giữa. |
| `page` | 0..1000000 | Mặc định 0. |
| `size` | 1..100 | Mặc định 20. |

Mọi điều kiện áp dụng trên **cùng một lần ứng tuyển**: một người có hai hồ sơ, từ khóa khớp hồ sơ A sẽ không kết hợp
với giai đoạn của hồ sơ B. Kết quả xếp theo thời điểm nộp mới nhất trước (`receivedAt DESC`, rồi id).

Response 200:

```json
{
  "items": [
    {
      "applicationId": "4b8f…",
      "candidateId": "0c1d…",
      "requisitionId": "77aa…",
      "positionId": "19e0…",
      "positionCode": "DEV-JAVA",
      "positionName": "Lập trình viên Java",
      "postingTitle": "Java Developer",
      "fullName": "Trần Thị Bình",
      "email": "binh@example.test",
      "phone": "0987654321",
      "stage": "NEW",
      "stageName": "Hồ sơ mới",
      "sourceName": "LinkedIn",
      "tags": ["Java", "Nhãn nội bộ"],
      "rating": 4,
      "receivedAt": "2026-10-15T03:00:00Z",
      "matchedIn": ["EMAIL"]
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

`matchedIn` cho biết trường nào của hồ sơ chứa `keyword` (`NAME`, `EMAIL`, `PHONE`); rỗng khi không có từ khóa. Item
không bao giờ chứa ghi chú sàng lọc, nội dung CV, mã tra cứu hay token.

Lỗi:

| HTTP | code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Tham số sai kiểu, enum lạ, khoảng ngày/đánh giá ngược, từ khóa quá ngắn/dài, trang ngoài giới hạn, id nguồn/nhãn không hợp lệ. `fieldErrors` nêu từng trường khi kiểm tra được theo trường. |
| 401 | `UNAUTHORIZED` / `SESSION_INVALID` | Thiếu token, token hết hạn, phiên đã thu hồi. |
| 403 | `FORBIDDEN` | Không có quyền đọc ứng viên; lọc theo requisition/vị trí ngoài phạm vi. |
| 404 | `REQUISITION_NOT_FOUND`, `POSITION_NOT_FOUND` | Bộ lọc trỏ tới bản ghi không tồn tại. |
| 503 | `CANDIDATE_SEARCH_BUSY` | Câu truy vấn quá thời gian (ví dụ từ khóa quét quá nhiều hồ sơ) hoặc chờ khóa; thu hẹp điều kiện rồi thử lại. |

Ghi chú cho frontend:

- Gửi lại đúng các tham số đang hiển thị trên form; không cần gửi trường rỗng. Sau mỗi thay đổi bộ lọc quay về `page=0`.
- `total` đếm cả các trang; dùng `hasNext` để bật nút "Trang sau".
- Hiển thị `stageName` thay vì tự dịch `stage`. `rating` có thể `null` (chưa đánh giá).
- Muốn xem hồ sơ rút lui phải chọn rõ giai đoạn `WITHDRAWN`.
- Câu hỏi BA/PO: nhiều nhãn mặc định là "có ít nhất một" (`ANY`); nếu nghiệp vụ muốn "có đủ" làm mặc định, chỉ cần đổi giá trị gửi lên.

## 393 — Tìm từ khóa trong thư giới thiệu

Cùng endpoint `GET /api/v1/candidate-search`, thêm tham số:

| Tham số | Kiểu | Ý nghĩa |
|---|---|---|
| `coverLetterKeyword` | 2..100 ký tự | Tìm trong thư giới thiệu của chính lần ứng tuyển đó, không phân biệt hoa thường và dấu tiếng Việt, khoảng trắng liền nhau tính là một. `%`, `_`, `!` là ký tự thường. Hồ sơ không có thư giới thiệu không bao giờ khớp. |

`coverLetterKeyword` được **kết hợp (AND)** với `keyword` (họ tên/email/điện thoại) và mọi bộ lọc khác trên cùng một hồ
sơ. Ví dụ `keyword=binh&coverLetterKeyword=java&stage=SCREENING` chỉ trả hồ sơ của "Bình" đang ở Sàng lọc có chữ "Java"
trong thư. Phạm vi vẫn là `CandidateAccess.READABLE`: thư của hồ sơ ngoài phạm vi không bao giờ được tìm.

Khi có `coverLetterKeyword`, mỗi item có `"COVER_LETTER"` trong `matchedIn`. Response **không** trả nội dung thư; muốn
đọc thư, mở hồ sơ hợp nhất (`GET /api/v1/candidates/{id}/unified-profile`).

Lỗi thêm: 400 `VALIDATION_ERROR` với `fieldErrors.coverLetterKeyword` khi từ khóa ngắn hơn 2, dài hơn 100 ký tự hoặc chứa
ký tự điều khiển/ẩn.

Hiệu năng: mỗi hồ sơ trong phạm vi chỉ được chuẩn hóa (NFC, bỏ dấu, chữ thường) một lần cho mỗi lệnh tìm; tổng số và
trang lấy trong cùng một câu truy vấn, chi tiết chỉ đọc cho các dòng của trang. Quá thời gian → 503
`CANDIDATE_SEARCH_BUSY` (xem 392).

Ghi chú cho frontend:

- Có thể đặt một ô "Từ khóa trong thư giới thiệu" riêng cạnh ô tìm tên/email/điện thoại; hai ô cùng lọc.
- Đánh dấu dòng có `COVER_LETTER` bằng biểu tượng "khớp thư giới thiệu"; không tự trích nội dung thư từ API khác vào bảng.

## 394 — Bộ lọc cá nhân đã lưu

Migration: `V65__create_candidate_saved_filters.sql` (bảng `candidate_saved_filters`: chủ sở hữu, tên, tên chuẩn hóa,
điều kiện JSONB tối đa 8 KB, version, thời điểm tạo/sửa; tên duy nhất theo chủ sở hữu, không phân biệt hoa thường).

Quyền: `CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED` (bộ lọc chỉ có ích với người được tìm ứng viên). Chủ sở hữu
luôn là `sub` của JWT; client **không** gửi được `ownerId` (trường lạ bị từ chối). Id bộ lọc của người khác trả **404**
giống hệt id không tồn tại, kể cả với HR/Admin, nên không dò được id.

| Method | Path | Mô tả | Thành công |
|---|---|---|---|
| GET | `/api/v1/candidate-search/saved-filters` | Danh sách bộ lọc của tôi, theo tên | 200, mảng |
| GET | `/api/v1/candidate-search/saved-filters/{id}` | Một bộ lọc của tôi | 200 |
| POST | `/api/v1/candidate-search/saved-filters` | Tạo bộ lọc | 201 |
| PUT | `/api/v1/candidate-search/saved-filters/{id}` | Đổi tên/điều kiện, cần `expectedVersion` | 200 |
| DELETE | `/api/v1/candidate-search/saved-filters/{id}` | Xóa | 204 |

Request POST/PUT:

```json
{
  "name": "Java tháng 10",
  "criteria": {
    "requisitionId": "77aa…",
    "positionId": null,
    "stages": ["NEW", "SCREENING"],
    "sourceIds": ["5e2c…"],
    "tagIds": [],
    "tagMatch": "ANY",
    "submittedFrom": "2026-10-01",
    "submittedTo": null,
    "minRating": 3,
    "maxRating": null,
    "keyword": "binh",
    "coverLetterKeyword": "java"
  },
  "expectedVersion": 0
}
```

`criteria` dùng đúng tên và quy tắc của tham số tìm kiếm (392/393, danh sách là mảng). Trường bỏ qua = không lọc.
`expectedVersion` bắt buộc với PUT (version đã đọc), bị bỏ qua với POST.
Giá trị lặp trong một danh sách (ví dụ cùng `sourceId` gửi nhiều lần) chỉ được giữ một lần, nên bộ lọc lưu đúng
những gì đã kiểm tra. Tên bộ lọc được bỏ ký tự ẩn ở hai đầu (kể cả khoảng trắng không ngắt dòng, khoảng trắng rộng 0) và
gộp khoảng trắng liền nhau: `"Java"` và `"JAVA "` (có khoảng trắng không ngắt dòng) là cùng một tên; tên chỉ gồm ký tự
ẩn bị từ chối (400). Nếu ràng buộc của database vẫn từ chối dữ liệu, API trả 400 `VALIDATION_ERROR`, không trả 500.

Response (một bộ lọc):

```json
{
  "id": "c0f1…",
  "name": "Java tháng 10",
  "criteria": { "...": "như trên, đã chuẩn hóa" },
  "createdAt": "2026-10-15T03:00:00Z",
  "updatedAt": "2026-10-15T03:00:00Z",
  "version": 0
}
```

Lỗi:

| HTTP | code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Tên trống/quá 80 ký tự/không phải văn bản thuần; thiếu `criteria`; điều kiện sai (`fieldErrors` dạng `criteria.minRating`); PUT thiếu `expectedVersion`. |
| 400 | `INVALID_JSON` | JSON hỏng, trường lạ (kể cả `ownerId`), giá trị enum lạ trong `stages`/`tagMatch`. |
| 401 | `SESSION_INVALID` | Phiên hết hạn/bị thu hồi (kiểm lại sau khi khóa tài khoản và phiên). |
| 403 | `FORBIDDEN` | Không có quyền đọc ứng viên. |
| 404 | `SAVED_FILTER_NOT_FOUND` | Không có bộ lọc đó **của bạn**. |
| 409 | `SAVED_FILTER_NAME_TAKEN` | Bạn đã có bộ lọc trùng tên (không phân biệt hoa thường). |
| 409 | `SAVED_FILTER_CHANGED` | `expectedVersion` cũ: bộ lọc vừa được sửa ở tab/thiết bị khác. |
| 409 | `SAVED_FILTER_LIMIT` | Đã có 50 bộ lọc. |
| 503 | `SAVED_FILTER_BUSY` | Hết thời gian chờ khóa; thử lại. |

Ghi chú cho frontend:

- Áp dụng bộ lọc = đổ `criteria` vào form rồi gọi `GET /api/v1/candidate-search` như bình thường. Requisition/vị trí/nhãn
  trong bộ lọc được kiểm tra phạm vi **lúc áp dụng**: nếu bạn không còn phụ trách requisition đó, lệnh tìm trả 403/404 và
  nên báo "Bộ lọc tham chiếu vị trí bạn không còn quyền xem".
- Khi 409 `SAVED_FILTER_CHANGED`, tải lại bộ lọc rồi cho người dùng sửa lại.
- Không hiển thị bộ lọc của người khác; không có chức năng chia sẻ bộ lọc.

## 423 — API xuất Excel ứng viên theo bộ lọc

`GET /api/v1/candidate-search/export`

Quyền: như tìm kiếm (`CANDIDATES_READ_ALL` hoặc `CANDIDATES_READ_SCOPED`). Tham số: **đúng các tham số lọc** của
`GET /api/v1/candidate-search` (392/393), không có `page`/`size`.

Cách hoạt động: dịch vụ dùng lại nguyên điều kiện, các bước kiểm tra (401/403 trước mọi truy vấn, 400 điều kiện sai,
404/403 requisition/vị trí, 400 nguồn/nhãn không hợp lệ) và **chính câu truy vấn trang** của danh sách (offset 0), nên
tệp chứa đúng các hồ sơ danh sách sẽ hiển thị, cùng thứ tự (mới nộp trước), cùng phạm vi `CandidateAccess.READABLE`. Mỗi
hồ sơ xuất ra được ghi một dòng `candidate_access_events` loại `PROFILE` như khi xem.

Giới hạn: tối đa `app.candidate-export.max-rows` hồ sơ (mặc định 5000, cấu hình 1..10000). Kết quả lớn hơn bị **từ chối**
(400 `CANDIDATE_EXPORT_TOO_LARGE`), không cắt bớt âm thầm, và không ghi nhật ký truy cập nào.

Response 200:

- `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- `Content-Disposition: attachment; filename="…"; filename*=UTF-8''…`
- `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`
- Body: tệp `.xlsx` (Apache POI), sheet `Ứng viên`, dòng 1 là tiêu đề, mỗi dòng sau là một hồ sơ. Mọi giá trị là ô
  chuỗi, không có công thức. Định dạng cột và tên tệp chi tiết ở mục 424.

Lỗi (body JSON chuẩn): 400 `VALIDATION_ERROR`, 400 `CANDIDATE_EXPORT_TOO_LARGE`, 401, 403 `FORBIDDEN`,
404 `REQUISITION_NOT_FOUND`/`POSITION_NOT_FOUND`.

Ghi chú cho frontend:

- Nút "Xuất Excel" gửi đúng query string đang dùng cho danh sách (bỏ `page`, `size`).
- Gọi bằng `fetch` với Bearer token, `Accept: */*` (hoặc không đặt `Accept`), đọc `response.blob()` và tên tệp từ
  `Content-Disposition`; **không** mở URL trực tiếp trong tab mới (không có token). Nếu `response.ok` là false, đọc
  body JSON để lấy `code`/`message`.
- Khi nhận `CANDIDATE_EXPORT_TOO_LARGE`, nhắc người dùng thu hẹp bộ lọc (ví dụ chọn vị trí hoặc khoảng ngày).

## 424 — Định dạng tệp xuất và loại ghi chú nhạy cảm

Áp dụng cho tệp của `GET /api/v1/candidate-search/export` (423).

**Tên tệp:** `ung-vien_<mã vị trí>_<yyyy-MM-dd>.xlsx`.

- Mã vị trí lấy từ vị trí của `requisitionId` (nếu lọc theo requisition) hoặc của `positionId`; không lọc theo vị trí
  thì là `TAT-CA`.
- Mã chỉ giữ chữ Latin không dấu, số, `.`, `-`, `_`; ký tự khác thành `-` (ví dụ `DEV/JAVA 01` → `DEV-JAVA-01`), tối đa 50
  ký tự; mã không còn ký tự nào thì là `VI-TRI`.
- Ngày là **ngày xuất theo giờ Việt Nam** (`app.business-zone`), không phải ngày UTC.

**Sheet `Ứng viên`** — các cột cố định (danh sách cho phép, không tự thêm theo câu truy vấn):

| Cột | Kiểu ô |
|---|---|
| STT | số |
| Mã vị trí, Vị trí, Tin tuyển dụng, Họ tên, Email, Số điện thoại, Giai đoạn, Nguồn, Nhãn | chuỗi |
| Đánh giá | số (trống nếu chưa đánh giá) |
| Ngày nộp | ngày giờ `dd/mm/yyyy hh:mm` theo giờ Việt Nam |

Dòng tiêu đề được cố định khi cuộn và có bộ lọc tự động.

**Không bao giờ có trong tệp:** ghi chú sàng lọc nội bộ, nội dung thư giới thiệu, tệp/tên tệp CV, mã tra cứu và mã băm,
id nội bộ (hồ sơ, ứng viên, requisition), email nhân viên, hồ sơ ngoài phạm vi.

**Chống chèn công thức:** mọi văn bản là ô chuỗi, không ô nào là công thức. Văn bản bắt đầu bằng `=`, `+`, `-`, `@`, tab,
xuống dòng (kể cả dạng full-width `＝＋－＠`) được gắn kiểu "quote prefix", nên Excel/LibreOffice luôn hiểu là chữ, kể cả
khi người dùng sửa ô. Ký tự điều khiển bị bỏ.

**Sheet `Thông tin xuất`:** thời điểm xuất (giờ Việt Nam), số hồ sơ, và bộ lọc đã áp dụng bằng chữ (vị trí, giai đoạn,
nguồn, nhãn, khoảng ngày nộp, mức đánh giá, từ khóa).

**Tài nguyên:** tệp được ghi theo kiểu streaming (Apache POI SXSSF): chỉ 100 dòng nằm trong bộ nhớ, phần còn lại ở tệp tạm
nén của POI và bị xóa ngay sau khi tạo xong. Mỗi máy chủ chỉ tạo đồng thời tối đa `app.candidate-export.max-concurrent`
tệp (mặc định 2, cấu hình 1..8); vượt quá → 503 `CANDIDATE_EXPORT_BUSY` kèm `Retry-After: 1`. Truy vấn quá thời gian →
503 `CANDIDATE_SEARCH_BUSY`.

Ghi chú cho frontend:

- Lấy tên tệp từ `Content-Disposition` (ưu tiên `filename*`), không tự đặt tên.
- Không cần xử lý định dạng: mở tệp là đọc được ngay, cột ngày sắp xếp đúng.
- Câu hỏi BA/PO: khi không lọc theo vị trí, tên tệp dùng `TAT-CA`; nếu muốn bắt buộc chọn vị trí trước khi xuất, chỉ cần
  FE ẩn nút xuất khi chưa chọn vị trí.

## 407 — Thêm hồ sơ ứng viên thủ công

`POST /api/v1/requisitions/{id}/manual-applications` — `multipart/form-data`

Migration: `V65_1__create_manual_application_intakes.sql` (bảng `manual_application_intakes`: hồ sơ, người nhập,
thời điểm nhập).

Quyền: `CANDIDATES_WRITE_ALL` (ADMIN, HR_MANAGER) hoặc `CANDIDATES_WRITE_SCOPED` (RECRUITER) **và** quyền đọc ứng viên.
Người có quyền SCOPED phải đang là recruiter PRIMARY/SUPPORTING của requisition `{id}`; trưởng phòng chỉ có quyền đọc
nên không thêm được. Quyền và phạm vi được đọc lại sau khi khóa tài khoản, phiên và sau mỗi lần chờ khóa.

Các phần của form (đúng tên, mỗi phần tối đa một lần, tối đa 7 phần):

| Phần | Bắt buộc | Quy tắc |
|---|---|---|
| `fullName` | có | như cổng nộp hồ sơ: tối đa 120 ký tự, văn bản thuần một dòng |
| `email` | có | email hợp lệ, tối đa 254 ký tự; chuẩn hóa trim + chữ thường |
| `phone` | có | 8–15 chữ số, có thể có `+` đầu; khoảng trắng, `.`, `-`, `()` được bỏ |
| `sourceId` | không | UUID của nguồn `CANDIDATE_SOURCE` **đang hoạt động** (ví dụ "Hội chợ việc làm") |
| `coverLetter` | không | tối đa 5000 ký tự, văn bản thuần |
| `candidateConsent` | có | đúng chuỗi `true`: người nhập xác nhận ứng viên đồng ý cho công ty lưu hồ sơ |
| `cv` | có | một tệp PDF hoặc DOCX ≤ 10 MiB, kiểm bằng `CvValidator` chung (đúng định dạng, không nội dung chủ động) |

Không có phần `requisitionId`, `jobPostingId`, `stage`, `trackingCode`, `enteredBy`…: requisition lấy từ đường dẫn, người
nhập lấy từ JWT; phần lạ bị từ chối.

Hồ sơ đi qua **đúng luồng của cổng nộp hồ sơ** (`ApplicationStorageService`): tin tuyển dụng của requisition phải đang
nhận hồ sơ (requisition APPROVED, tin PUBLISHED, chưa quá hạn), nguồn phải đang hoạt động, email đã nộp vào requisition
này bị chặn (409), email đã có ở requisition khác được gộp vào cùng hồ sơ ứng viên, CV được lưu cùng giao dịch. Ứng viên
nhận email xác nhận kèm mã tra cứu/link rút hồ sơ như khi tự nộp (outbox mã hóa); người nhập **không** thấy mã tra cứu.
Hồ sơ mới ở giai đoạn `NEW`.

Response 201:

```json
{
  "applicationId": "9d1e…",
  "candidateId": "0c1d…",
  "requisitionId": "77aa…",
  "postingTitle": "Java Developer",
  "fullName": "Hoàng Thị Mai",
  "stage": "NEW",
  "stageName": "Hồ sơ mới",
  "receivedAt": "2026-10-15T03:00:00Z",
  "enteredBy": { "id": "5b7c…", "fullName": "Recruiter A" }
}
```

Lỗi:

| HTTP | code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Trường sai/thiếu, thiếu CV, phần lạ, chưa xác nhận đồng ý, nguồn không còn áp dụng |
| 400 | `INVALID_MULTIPART`, `APPLICATION_CV_INVALID`, `APPLICATION_CV_ACTIVE_CONTENT` | Form hỏng, CV hỏng/có nội dung chủ động |
| 401 | `SESSION_INVALID` | Phiên hết hạn/thu hồi |
| 403 | `FORBIDDEN` | Không có quyền ghi ứng viên, hoặc requisition ngoài phạm vi |
| 404 | `REQUISITION_NOT_FOUND` | Requisition không tồn tại |
| 404 | `JOB_POSTING_NOT_FOUND` | Requisition chưa có tin, hoặc tin không còn công khai |
| 409 | `JOB_POSTING_EXPIRED` | Tin đã hết hạn nhận hồ sơ |
| 409 | `APPLICATION_ALREADY_SUBMITTED` | Email này đã có hồ sơ trong requisition (xem 408 để kiểm tra trước) |
| 413 | `APPLICATION_CV_TOO_LARGE` | CV > 10 MiB hoặc form > 11 MiB |
| 415 | `APPLICATION_FORM_TYPE_UNSUPPORTED`, `APPLICATION_CV_TYPE_UNSUPPORTED` | Không phải multipart, CV không phải PDF/DOCX |
| 503 | `MANUAL_APPLICATION_UNAVAILABLE` | Chưa cấu hình khóa mã hóa email xác nhận |
| 503 | `MANUAL_APPLICATION_BUSY` (có `Retry-After`), `MANUAL_APPLICATION_FAILED` | Đang bận/lỗi tạm thời |

Ghi chú cho frontend:

- Dùng `FormData`, **không** tự đặt header `Content-Type` (để trình duyệt thêm boundary).
- Nút "Thêm ứng viên" chỉ hiện ở requisition mà người dùng phụ trách (hoặc với HR/Admin); server vẫn kiểm tra lại.
- Ô `candidateConsent` là checkbox bắt buộc "Ứng viên đã đồng ý cho công ty lưu hồ sơ".
- Câu hỏi BA/PO: (1) có bắt buộc chọn nguồn cho hồ sơ nhập tay không (hiện không bắt buộc); (2) có gửi email xác nhận cho
  ứng viên nhập tay không (hiện có, giống cổng nộp, để ứng viên tra cứu/rút hồ sơ).

## 408 — Dùng chung kiểm tra trùng cho hồ sơ thủ công

Migration: `V65_2__index_application_national_phone.sql` (chỉ mục biểu thức trên số điện thoại dạng nội địa, không đổi dữ
liệu).

Hồ sơ nhập tay đi qua **đúng luồng phát hiện trùng của cổng nộp hồ sơ**, không có luật riêng:

1. Cùng email chuẩn hóa trong cùng requisition → 409 `APPLICATION_ALREADY_SUBMITTED` (bộ phân xử UNIQUE của storage).
2. Email đã có ở requisition khác → hồ sơ mới được gộp vào **cùng hồ sơ ứng viên** (`candidateId`), xem chung ở hồ sơ hợp
   nhất.
3. Thêm cho nhân viên: báo cáo các hồ sơ cùng email (sẽ/đã gộp) và hồ sơ **email khác nhưng cùng số điện thoại** để so
   sánh bằng mắt. Số điện thoại so ở dạng nội địa: `+84 912 345 678` trùng `0912345678`.

Mọi danh sách chỉ gồm hồ sơ người gọi **đọc được** (`CandidateAccess.READABLE`); hồ sơ ngoài phạm vi không bao giờ được
nhắc tới. Mỗi hồ sơ hiện ra được ghi `candidate_access_events` loại `PROFILE`. Tối đa 20 hồ sơ mỗi danh sách, mới nhất
trước.

### Kiểm tra trước khi nhập

`POST /api/v1/requisitions/{id}/manual-applications/duplicate-check`

Quyền và phạm vi: như API nhập tay (407). Request:

```json
{ "email": "lan@example.test", "phone": "0912 345 678" }
```

Email/điện thoại được kiểm và chuẩn hóa đúng như khi lưu (trim, chữ thường, bỏ khoảng trắng/`.`/`-`/`()`). Trường lạ bị
từ chối (400 `INVALID_JSON`). Không ghi gì ngoài nhật ký truy cập.

Response 200:

```json
{
  "emailInRequisition": true,
  "sameEmail": [
    {
      "applicationId": "4b8f…",
      "candidateId": "0c1d…",
      "requisitionId": "77aa…",
      "postingTitle": "Java Developer",
      "fullName": "Nguyễn Văn A",
      "stage": "NEW",
      "stageName": "Hồ sơ mới",
      "receivedAt": "2026-10-15T03:00:00Z",
      "sameRequisition": true
    }
  ],
  "samePhone": []
}
```

`emailInRequisition = true` nghĩa là lệnh nhập sẽ bị 409. Cờ này được tính riêng (EXISTS trên requisition + email
chuẩn hóa), không suy ra từ danh sách `sameEmail` vốn chỉ có tối đa 20 hồ sơ.

Lỗi: 400 `VALIDATION_ERROR` (`fieldErrors.email` / `fieldErrors.phone`), 400 `INVALID_JSON`, 401, 403 `FORBIDDEN`,
404 `REQUISITION_NOT_FOUND`.

### Báo cáo sau khi nhập

Response 201 của `POST /api/v1/requisitions/{id}/manual-applications` có thêm:

```json
"duplicates": {
  "emailInRequisition": false,
  "sameEmail": [ { "...": "hồ sơ khác của cùng ứng viên" } ],
  "samePhone": [ { "...": "hồ sơ email khác, cùng số điện thoại" } ],
  "empty": false
}
```

Ghi chú cho frontend:

- Luồng gợi ý: người dùng nhập email + điện thoại → gọi `duplicate-check` → nếu `emailInRequisition` thì chặn nút lưu và
  mở hồ sơ cũ; nếu có `sameEmail`/`samePhone` thì hiện bảng so sánh (họ tên, vị trí, giai đoạn, ngày nộp) với link tới
  hồ sơ hợp nhất `/candidates/{candidateId}`; người dùng xác nhận rồi mới gửi form nhập.
- Trùng số điện thoại chỉ là **gợi ý**, không chặn lưu; hệ thống không tự gộp hai email khác nhau.
- Câu hỏi BA/PO: có cần chức năng gộp thủ công hai hồ sơ ứng viên khác email nhưng cùng người không (hiện chưa có).

## 412 — Chuẩn hóa tiêu chí bắt buộc của vị trí

Migration: `V65_3__create_position_required_criteria.sql` (bảng `position_required_criteria`: số năm kinh nghiệm tối
thiểu, trình độ tối thiểu, version, người/thời điểm sửa; bảng `position_required_skills`: kỹ năng bắt buộc theo thứ tự,
khóa so khớp không phân biệt hoa thường/dấu).

Tiêu chí gắn với **vị trí trong danh mục** (`positions`), dùng chung cho mọi requisition của vị trí đó.

| Method | Path | Quyền |
|---|---|---|
| GET | `/api/v1/positions/{id}/required-criteria` | `ORGANIZATION_READ_ALL` (mọi vai trò nội bộ) |
| PUT | `/api/v1/positions/{id}/required-criteria` | `ORGANIZATION_WRITE_ALL` (ADMIN, HR_MANAGER) |

Request PUT:

```json
{
  "minExperienceYears": 3,
  "requiredEducation": "BACHELOR",
  "requiredSkills": ["Java", "Spring Boot"],
  "expectedVersion": 0
}
```

- `minExperienceYears`: số nguyên JSON 0..50 hoặc `null` (không yêu cầu). `2.5`, `"3"` → 400 `INVALID_JSON`.
- `requiredEducation`: `HIGH_SCHOOL`, `COLLEGE`, `BACHELOR`, `MASTER`, `DOCTORATE` hoặc `null`. Bậc cao hơn đáp ứng bậc thấp hơn.
- `requiredSkills`: tối đa 20 kỹ năng khác nhau, mỗi kỹ năng 1..60 ký tự văn bản thuần; khoảng trắng thừa bị gộp; trùng
  (không phân biệt hoa thường và dấu: "Kế toán" = "ke toan") chỉ giữ cách viết đầu tiên. `null` hoặc `[]` = không yêu cầu.
  Khoảng trắng không ngắt dòng/khoảng trắng rộng 0 ở hai đầu bị bỏ, khoảng trắng liền nhau bên trong gộp làm một; kỹ năng
  chỉ gồm ký tự ẩn bị từ chối (400).
- `expectedVersion`: bắt buộc, là `version` vừa đọc (0 nếu chưa từng lưu).
- Gửi mọi giá trị `null`/rỗng là xóa tiêu chí (version vẫn tăng, `configured=false`).

Response 200 (GET và PUT):

```json
{
  "positionId": "19e0…",
  "configured": true,
  "minExperienceYears": 3,
  "requiredEducation": "BACHELOR",
  "requiredEducationName": "Đại học",
  "requiredSkills": ["Java", "Spring Boot"],
  "version": 1,
  "updatedAt": "2026-10-15T03:00:00Z",
  "updatedBy": { "id": "a1b2…", "fullName": "HR Nội Bộ" }
}
```

Chưa cấu hình: `configured=false`, `version=0`, các giá trị `null`/rỗng, `updatedBy=null`.

Lỗi: 400 `VALIDATION_ERROR`, 400 `INVALID_JSON` (sai kiểu, enum lạ, trường lạ), 401, 403 `FORBIDDEN`,
404 `POSITION_NOT_FOUND`, 409 `FIT_CRITERIA_CHANGED` (version cũ), 503 `FIT_CRITERIA_BUSY`.

Ghi chú cho frontend:

- Màn hình "Tiêu chí bắt buộc" trong chi tiết vị trí: input số năm, dropdown trình độ (hiển thị `requiredEducationName`),
  ô nhập kỹ năng dạng tag. Chỉ HR/Admin thấy nút Lưu.
- Luôn gửi `expectedVersion` lấy từ lần GET gần nhất; khi 409 thì tải lại và cho sửa lại.
