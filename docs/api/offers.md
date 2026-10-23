# Offer nháp và phê duyệt — TKNHTTDNB1-493–497, 500–503

Offer lưu điều kiện làm việc gửi cho ứng viên: chức danh, lương cơ bản tháng, phụ cấp tháng, ngày nhận việc và hạn phản hồi. Luồng: **Nháp → Trình HR → Duyệt cấp cao nếu ngoài dải → Đã duyệt → Gửi ứng viên**. Gửi và phản hồi là phần delivery riêng.

Mọi API nội bộ dùng Bearer access token, trả `Cache-Control: no-store`. Chỉ làm backend; giao diện nhân viên cần nối các API dưới đây.

## Quyền

- Tạo/sửa/trình/hủy/gửi: `RECRUITER`, `HR_MANAGER` hoặc `ADMIN`, có quyền đọc/ghi `OFFERS` và đọc `CANDIDATES`. Phạm vi là giao của các quyền; khi có quyền trong giao này là `SCOPED`, người thao tác phải đang được phân công vào yêu cầu tuyển dụng. Quản lý phòng ban có thể được đọc hồ sơ theo quyền hiện có, nhưng quyền đọc đó không thay thế phân công khi ghi offer. HR/Admin có đủ các quyền `ALL` không cần phân công.
- HR duyệt cấp một, `APPROVER` duyệt cấp cao. Người duyệt được chỉ định bằng ID khi trình offer; phải có đúng vai trò và quyền đọc/ghi `OFFERS` hiện tại. Không được tự duyệt offer mình tạo hoặc trình; hai cấp phải là hai người khác nhau.
- Người được chỉ định được đọc **đúng offer đó**, xem trước và lịch sử mà không cần quyền đọc hồ sơ ứng viên nói chung. Không được dùng quyền này để liệt kê offer của các hồ sơ khác.
- Dải lương chuẩn (`bandMin`, `bandMax`) chỉ trả khi có `SALARY_RANGES_READ_ALL`. Recruiter vẫn biết cờ `outsideBand` để nhập giải trình. Thư ứng viên không chứa dải chuẩn, giải trình hay ý kiến duyệt.

## Tạo và sửa nháp

`POST /api/v1/applications/{applicationId}/offers` → HTTP 201.

```json
{
  "salary": 15000000,
  "allowance": 1000000,
  "startDate": "2026-11-15",
  "responseDeadline": "2026-11-10T16:59:59Z",
  "explanation": null
}
```

`PUT /api/v1/offers/{id}` → HTTP 200, cùng body và thêm `expectedVersion` lấy từ offer đang xem. PUT thay toàn bộ điều kiện, không cập nhật từng trường. Chỉ sửa khi `DRAFT` hoặc `CHANGES_REQUESTED`.

- Chức danh/người nhận lấy từ hồ sơ và yêu cầu tuyển dụng; client không tự chọn một ứng viên hay chức danh khác trong body.
- `salary`, `allowance`: số nguyên VND từ 0 đến 1.000.000.000.000. Chuỗi số, số thập phân, số âm bị từ chối. So dải theo **lương cơ bản**, không cộng phụ cấp.
- Hai biên của dải được tính là trong dải. Lương thấp hơn hoặc cao hơn dải đều cần `explanation` có nội dung, tối đa 2000 ký tự văn bản thuần.
- Ngày nhận việc không ở quá khứ; hạn phản hồi phải ở tương lai và không sau ngày nhận việc theo múi giờ nghiệp vụ.
- Hồ sơ phải đang `OFFER`, yêu cầu tuyển dụng `APPROVED`, chức danh đang hoạt động.
- Mỗi ứng viên chỉ có một offer còn hiệu lực trên **tất cả hồ sơ ứng tuyển**. `DRAFT`, `CHANGES_REQUESTED`, `PENDING_HR`, `PENDING_HIGHER`, `APPROVED`, `SENT`, `ACCEPTED` giữ chỗ này. `REJECTED`, `DECLINED`, `EXPIRED`, `CANCELLED`, `SUPERSEDED` giải phóng chỗ. Offer đã chấp nhận vẫn hiệu lực; chưa có nghiệp vụ thu hồi quyết định nhận việc. Xem [thương lượng và thay thế offer](offer-negotiation.md).

Response gồm ID offer/hồ sơ/ứng viên/chức danh, tên/email nhận, tên chức danh, điều kiện trên, `outsideBand`, `status`, `version`, `approvalRound`, người tạo/trình/người duyệt và các mốc thời gian. Có thêm `deliveryStatus`, `sentAt`, `respondedAt`, `responseNote` phục vụ phần gửi/phản hồi. Trường không có giá trị được bỏ khỏi JSON. `SENT` nghĩa là đã xếp email vào hàng đợi; dùng `deliveryStatus` để biết trạng thái giao email.

## Đọc, liệt kê và xem trước

| API | Kết quả |
| --- | --- |
| `GET /api/v1/offers/{id}` | Chi tiết hiện tại, kể cả offer đã kết thúc. |
| `GET /api/v1/applications/{id}/offers?page=0&size=20` | `{items,page,size,totalElements,totalPages}`, mới nhất trước. |
| `GET /api/v1/offers/{id}/preview` | `{subject,plainText,html}` dùng cùng payload và renderer với email thật. |
| `GET /api/v1/offers/{id}/history?page=0&size=20` | Lịch sử theo thời gian, gồm loại sự kiện, vòng duyệt, người/thời gian/ý kiến. |
| `GET /api/v1/offers/approvers?role=HR_MANAGER` | Tối đa 200 người đang hoạt động và đủ quyền, chỉ có `{id,fullName}`. `role` mặc định `HR_MANAGER`, hoặc `APPROVER`. |

Phân trang: `page` từ 0 đến 100000, `size` từ 1 đến 100. Xem trước có nội dung điều kiện và bố cục thư thật, nhưng không tạo token hay nút phản hồi thật. Cả xem trước và thư gửi dùng múi giờ `app.offers.time-zone` (`OFFER_TIME_ZONE`, mặc định `Asia/Ho_Chi_Minh`); múi giờ nghiệp vụ kiểm tra ngày nhận việc không thay đổi. Hiển thị `html` trong iframe được sandbox; không chèn thẳng vào HTML của trang quản trị.

## Trình duyệt

`POST /api/v1/offers/{id}/submit`

```json
{
  "expectedVersion": 0,
  "hrApproverId": "11111111-1111-1111-1111-111111111111",
  "higherApproverId": "22222222-2222-2222-2222-222222222222"
}
```

Offer trong dải chỉ gửi `hrApproverId`; ngoài dải phải gửi cả hai người. Server đọc lại dải chuẩn hiện tại lúc trình. Nếu dải đã thay đổi sau khi lưu nháp, offer có thể cần thêm giải trình hoặc người duyệt cấp cao.

Khi trình thành công: tăng `approvalRound` và `version`, chuyển `PENDING_HR`, chụp người nhận/chức danh/dải và toàn bộ điều kiện. Các điều kiện đã trình được bảo vệ bằng trigger PostgreSQL. Chỉnh dải chuẩn sau đó không thay đổi chuỗi duyệt của lần trình này; chức danh vẫn phải đang hoạt động khi duyệt.

## Quyết định duyệt

`POST /api/v1/offers/{id}/approval`

```json
{"expectedVersion": 1, "action": "REQUEST_CHANGES", "comment": "Cần điều chỉnh phụ cấp."}
```

| Hành động | Kết quả |
| --- | --- |
| `APPROVE` | HR duyệt trong dải → `APPROVED`; HR duyệt ngoài dải → `PENDING_HIGHER`; cấp cao duyệt → `APPROVED`. |
| `REJECT` | Bắt buộc ý kiến, chuyển `REJECTED`; offer này kết thúc. |
| `REQUEST_CHANGES` | Bắt buộc ý kiến, chuyển `CHANGES_REQUESTED`; người soạn sửa rồi trình lại. |

Trình lại luôn bắt đầu một vòng duyệt mới từ HR; không dùng lại dấu duyệt của vòng trước. Lịch sử cũ vẫn còn. Không gửi email ứng viên trong thao tác duyệt.

## Hủy offer chưa gửi

`POST /api/v1/offers/{id}/cancel`

```json
{"expectedVersion": 0, "reason": "Ứng viên chọn một vị trí khác."}
```

Lý do bắt buộc. Cho hủy từ nháp, yêu cầu sửa, chờ HR/cấp cao hoặc đã duyệt; không dùng endpoint này để thu hồi offer đã gửi/đã chấp nhận. Hủy không đổi pipeline.

## Lỗi và tính nhất quán

| Mã | Ý nghĩa |
| --- | --- |
| `VALIDATION_ERROR` (400) | Thiếu/sai điều kiện, giải trình, người duyệt hoặc ý kiến bắt buộc. |
| `FORBIDDEN` (403) | Thiếu quyền, ngoài phạm vi, sai người/cấp duyệt hoặc tự duyệt. |
| `OFFER_NOT_FOUND` / `APPLICATION_NOT_FOUND` (404) | Không tìm thấy đối tượng. |
| `OFFER_ALREADY_ACTIVE` (409) | Ứng viên đã có offer hiệu lực. |
| `OFFER_VERSION_CHANGED` (409) | Màn hình đã cũ, tải lại trước khi thao tác. |
| `OFFER_NOT_EDITABLE` / `OFFER_NOT_PENDING` / `OFFER_NOT_CANCELLABLE` (409) | Trạng thái hiện tại không cho thao tác. |
| `OFFER_APPLICATION_INACTIVE` / `POSITION_INACTIVE` (409) | Hồ sơ, yêu cầu hoặc chức danh không còn phù hợp. |
| `OFFER_CONTEXT_CHANGED` (409) | Danh tính hồ sơ/chức danh/người nhận đã đổi sau khi chụp. |
| `OFFER_BUSY` (503) | Đang có giao dịch khác; thao tác bị hủy toàn bộ, có thể thử lại. |

Migration V61 thêm `offers`, `offer_events`, unique index trên ứng viên và trigger bảo vệ lịch sử/điều kiện. Không sửa migration đã áp dụng. Danh tính offer không đổi sau khi tạo. Lịch sử chỉ ghi thêm, không sửa hoặc xóa. Transaction khóa tài khoản → phiên → yêu cầu → hồ sơ → offer → chức danh; đọc lại quyền sau khi chờ khóa. Sau khi chờ unique index của ứng viên, server kiểm tra lại phiên/quyền/hạn trước commit. Thao tác thất bại không giữ một phần thay đổi.

Kiểm thử backend: `OfferCoreIntegrationTest`; kiểm thử migration hai lịch sử do `OfferMigrationIntegrationTest` phụ trách. Không chạy test trên database thật.
