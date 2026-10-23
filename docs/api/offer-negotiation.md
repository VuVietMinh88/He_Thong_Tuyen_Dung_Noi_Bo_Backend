# Thương lượng và thay thế offer — TKNHTTDNB1-513–516

Phần này nối tiếp [tạo và duyệt offer](offers.md) và [gửi, phản hồi offer](offer-delivery.md). Các API dành cho nhân viên dùng Bearer access token và kiểm tra quyền hiện tại trong database. Không tự đưa ứng viên vào talent pool.

## Lý do từ chối

`POST /api/v1/public/offers/respond` nhận thêm `declineReason`:

| Mã | Hiển thị |
| --- | --- |
| `SALARY` | Lương |
| `LOCATION` | Địa điểm |
| `OTHER_JOB` | Nhận việc nơi khác |
| `PERSONAL` | Lý do cá nhân |

```json
{
  "token": "<mã từ fragment của liên kết offer>",
  "action": "DECLINE",
  "declineReason": "SALARY",
  "note": "Tôi mong muốn mức lương cao hơn."
}
```

Mã phân biệt hoa/thường. Trường này có thể bỏ qua hoặc là `null` để giữ tương thích client cũ; không tự đoán lý do cho dữ liệu cũ. Trang phản hồi có sẵn yêu cầu chọn một lý do khi từ chối. `ACCEPT` chỉ nhận lý do bị bỏ qua hoặc `null`. Giá trị sai hoặc sai kiểu trả HTTP 400. Retry chỉ thành công khi lựa chọn, ghi chú đã chuẩn hóa và lý do đều giống lần đầu; khác nội dung trả HTTP 409. Lý do và ý kiến được lưu cùng giao dịch phản hồi, giữ cả lịch sử ứng viên.

## Ghi nhận trao đổi với ứng viên

`POST /api/v1/offers/{id}/negotiations` trả HTTP 201:

```json
{
  "expectedVersion": 4,
  "reason": "SALARY",
  "comment": "Ứng viên đề nghị điều chỉnh lương; recruiter đang trao đổi lại."
}
```

`reason` thuộc bốn mã trên; `comment` bắt buộc, tối đa 2.000 ký tự. Chỉ ghi nhận trên offer `APPROVED`, `SENT` hoặc `DECLINED` còn đúng ngữ cảnh hồ sơ. Cần quyền đọc/ghi offer và đọc ứng viên, vai trò ADMIN/HR_MANAGER/RECRUITER, đúng phạm vi được giao. Không sửa hoặc xóa dòng lịch sử đã ghi. Ghi chú nhân viên không thay đổi lý do ứng viên đã gửi.

`GET /api/v1/offers/{id}/negotiations?page=0&size=20` trả `items`, `page`, `size`, `totalElements`, `totalPages`; mỗi dòng có lý do, nhận xét, người ghi và thời gian. Quyền đọc giống xem offer. `page` từ 0 đến 100.000, `size` từ 1 đến 100.

Ghi chú không thay đổi điều khoản hoặc tăng `version` của offer. Vì vậy, hai nhân viên có thể cùng thêm các dòng lịch sử độc lập; API ghi chú không có bảo đảm khử trùng khi client gửi lại cùng yêu cầu.

## Tạo offer thay thế

`POST /api/v1/offers/{id}/revisions` trả HTTP 201 với offer mới:

```json
{
  "expectedVersion": 4,
  "salary": 25000000,
  "allowance": 2000000,
  "startDate": "2026-12-01",
  "responseDeadline": "2026-11-25T10:00:00Z",
  "explanation": null,
  "comment": "Điều chỉnh lương sau trao đổi với ứng viên."
}
```

Ngày trong ví dụ phải đổi thành ngày phù hợp khi chạy. Lương và phụ cấp là số nguyên VND/tháng; kiểm tra dải lương, giải trình ngoài dải, ngày nhận việc và hạn trả lời giống tạo offer. `comment` bắt buộc, tối đa 2.000 ký tự. Quyền giống ghi offer.

Trong cùng giao dịch, hệ thống:

1. Kiểm tra phiên đăng nhập, quyền, phạm vi, `expectedVersion` và tình trạng hồ sơ.
2. Chuyển bản cũ từ `APPROVED`, `SENT` hoặc `DECLINED` sang `SUPERSEDED`, giữ nguyên điều khoản và phản hồi cũ.
3. Thu hồi link cũ và hủy email còn chờ. Email SMTP đã nhận không thể thu hồi, nhưng link cũ không còn dùng được.
4. Tạo bản mới ở `DRAFT`, `version=0`, chưa duyệt và chưa có link phản hồi. Bản mới phải trình duyệt rồi gửi như một offer mới.

Offer `ACCEPTED` không được thay thế. Với offer nháp, dùng API sửa nháp hiện có. Nếu hồ sơ đang `REJECTED`, chỉ mở lại thành `OFFER` khi lịch sử mới nhất xác nhận chính phản hồi từ chối offer này gây ra việc đó. Một quyết định loại/rút hồ sơ về sau, hoặc lịch sử không xác định được thứ tự, sẽ ngăn việc mở lại. Không thay đổi quy tắc chuyển giai đoạn chung.

Response xem offer có thêm `previousOfferId`, `replacedByOfferId`, `revisionNumber` và `declineReason` khi có dữ liệu. `revisionNumber` là số thứ tự trong chuỗi offer (1, 2, 3…); **`version` là số kiểm soát cập nhật đồng thời**, lấy từ response mới nhất để gửi làm `expectedVersion`. Mỗi bản chỉ có tối đa một bản thay thế trực tiếp. Lỗi ở bất kỳ bước nào sẽ hoàn tác cả việc thay thế và mở lại hồ sơ.

## Gợi ý talent pool

`GET /api/v1/offers/{id}/talent-pool-suggestion` cần quyền đọc offer và đọc ứng viên đúng phạm vi. Response có `offerId`, `applicationId`, `candidateId`, `suggested`, `reason` và các trường lý do từ chối khi có.

Chỉ gợi ý khi offer hiện tại đã bị từ chối, lịch sử chứng minh đúng lần từ chối đó và ứng viên không có offer hiệu lực khác. Offer đã được thay thế hoặc hồ sơ đã có quyết định tiếp theo không còn được gợi ý theo lần từ chối cũ. `reason` giải thích điều kiện gợi ý; `declineReason` là danh mục lý do từ chối. `declineComment` là ý kiến tương ứng; `declineSource` là `CANDIDATE` hoặc `STAFF`. Với dữ liệu cũ chưa có danh mục, hệ thống có thể lấy dòng thương lượng mới nhất của nhân viên làm thông tin tham khảo, không sửa phản hồi gốc.

Đây là dữ liệu hỗ trợ recruiter quyết định. API không tự lưu thành viên vào kho tiềm năng và không xóa lịch sử offer/hồ sơ.

| `reason` | Ý nghĩa |
| --- | --- |
| `ELIGIBLE_DECLINED` | Có thể hiển thị gợi ý (`suggested=true`) |
| `NOT_CURRENT_DECLINED` | Offer không còn là bản bị từ chối hiện tại |
| `OFFER_CONTEXT_CHANGED` | Hồ sơ, người nhận, chức danh hoặc yêu cầu đã đổi |
| `DECLINE_NOT_CURRENT` | Lịch sử không xác nhận lần từ chối hiện tại hoặc chức danh ngừng hoạt động |
| `CANDIDATE_HAS_EFFECTIVE_OFFER` | Ứng viên đang có offer hiệu lực khác |
| `NEWER_OFFER_EXISTS` | Ứng viên đã có offer mới hơn |

Các trường tùy chọn không có dữ liệu được bỏ khỏi JSON. Frontend nên dựa vào `suggested` để hiện hành động; dùng `reason` để giải thích tại sao chưa gợi ý.

## Lỗi riêng của thương lượng

| HTTP / mã | Cách xử lý |
| --- | --- |
| 400 `VALIDATION_ERROR` hoặc `INVALID_JSON` | Sửa trường dữ liệu được báo lỗi |
| 401 / 403 | Đăng nhập lại hoặc kiểm tra quyền/phạm vi được giao |
| 409 `OFFER_VERSION_CHANGED` | Tải lại offer rồi thực hiện lại quyết định |
| 409 `OFFER_NOT_REVISABLE` | Trạng thái không cho thương lượng hoặc thay thế |
| 409 `OFFER_DECLINE_CONTEXT_CHANGED` | Lần từ chối không còn là quyết định hiện tại của hồ sơ |
| 409 `OFFER_ALREADY_ACTIVE` | Xử lý offer hiệu lực khác của ứng viên trước |
| 409 `OFFER_REVISION_LIMIT` | Chuỗi đã đạt giới hạn số phiên bản |
| 503 `OFFER_BUSY` | Giao dịch đã hoàn tác; tải lại và thử sau |

## Database và kiểm thử

V63 bổ sung cột lý do/quan hệ phiên bản, trạng thái `SUPERSEDED`, bảng lịch sử thương lượng bất biến; áp dụng cho cả hai lineage Flyway hiện có. Không sửa V61/V62 đã phát hành. Dữ liệu cũ giữ nguyên và có `revisionNumber=1`, lý do chưa biết là `null`.

Các kiểm thử mới dùng PostgreSQL tạm, tài khoản giả và email giả; bao gồm lý do hợp lệ/không hợp lệ, retry, chuỗi thay thế, duyệt lại, quyền/phạm vi, lịch sử, rollback và nâng cấp database. Kết quả thực chạy được ghi trong handoff của worktree, không suy ra rằng đã triển khai lên VPS.
