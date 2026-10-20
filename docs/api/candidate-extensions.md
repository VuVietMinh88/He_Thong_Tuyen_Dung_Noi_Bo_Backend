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
