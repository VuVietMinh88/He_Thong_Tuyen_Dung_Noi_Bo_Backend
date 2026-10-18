# Theo dõi hồ sơ ứng tuyển

Phạm vi TKNHTTDNB1-340 (ánh xạ trạng thái nội bộ thành trạng thái công khai), story TKNHTTDNB1-44 (S4-05): ứng viên tra cứu trạng thái hồ sơ của mình bằng mã tra cứu, để biết hồ sơ đã được xem chưa mà không phải gọi điện hỏi. Task 340 chỉ thêm quy tắc ánh xạ dùng chung `PublicApplicationStatus` (mục "Trạng thái công khai (task 340)"); không thêm API, migration hay mã quyền. Hồ sơ được nộp qua [API nhận hồ sơ công khai](public-applications.md) (task 326); mã tra cứu được trả trong response nộp hồ sơ và gửi trong [email xác nhận](application-confirmation.md) (task 329).

Tiêu chí của story S4-05:

- "Nhập mã tra cứu và email để xem trạng thái": chưa có; API tra cứu là task 339.
- "Chỉ hiển thị trạng thái ở mức tổng quát: Đã nhận, Đang xem xét, Mời phỏng vấn, Kết thúc": quy tắc đã có (340): mỗi giai đoạn nội bộ hiện ra đúng một trong bốn trạng thái này. API nộp hồ sơ đã lấy trạng thái từ quy tắc này; API tra cứu (339) sẽ dùng cùng quy tắc.
- "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn": chưa có (task 341).

## Trạng thái công khai (task 340)

Hồ sơ có bảy giai đoạn nội bộ (`ApplicationStage`, task 325), dành cho người tuyển dụng. Ứng viên (API nộp hồ sơ; tra cứu, task 339; rút hồ sơ, task 345) và người giới thiệu (danh sách hồ sơ đã giới thiệu, task 352) chỉ thấy một trong bốn trạng thái công khai của `PublicApplicationStatus`:

| Giai đoạn nội bộ (`stage`) | Trạng thái công khai (`status`) | Tên hiển thị (`statusName`) |
|---|---|---|
| `NEW` (Hồ sơ mới) | `RECEIVED` | Đã nhận |
| `SCREENING` (Sàng lọc) | `IN_REVIEW` | Đang xem xét |
| `INTERVIEW` (Phỏng vấn), `OFFER` (Đề xuất offer) | `INTERVIEWING` | Mời phỏng vấn |
| `HIRED` (Nhận việc), `REJECTED` (Loại), `WITHDRAWN` (Ứng viên rút) | `COMPLETED` | Kết thúc |

Quy tắc:

- Mã giai đoạn nội bộ và tên của nó (cột đầu) không bao giờ có trong response hay email gửi ứng viên hoặc người giới thiệu; chỉ có `status` và `statusName` ở hai cột sau. Không mã hay tên công khai nào trùng một mã hay tên nội bộ (test ghim), nên hai bộ giá trị không lẫn được với nhau.
- Chỉ một hàm quyết định trạng thái công khai: `PublicApplicationStatus.of(stage)`. Mọi API trả trạng thái công khai gọi hàm này, không tự viết lại bảng: API nộp hồ sơ (response 201 có `status: "RECEIVED"`, `statusName: "Đã nhận"`; từ task 340 lấy từ hàm này, JSON không đổi), tra cứu (339), rút hồ sơ (345) và danh sách của người giới thiệu (352).
- `switch` trong hàm liệt kê đủ bảy giai đoạn, không có nhánh `default`. Thêm một giai đoạn vào `ApplicationStage` thì code không biên dịch được cho tới khi có người quyết định giai đoạn đó hiện là trạng thái nào; `PublicApplicationStatusTest` có `switch` riêng, nên test cũng không biên dịch được cho tới khi ghi cùng quyết định.
- Trạng thái công khai chỉ đi tới, không lùi. Bốn giá trị được khai báo theo thứ tự ứng viên đi qua. Đi từ `NEW` tới `OFFER`, bước công khai không bao giờ giảm; từ bất kỳ giai đoạn nào còn trong quy trình sang một giai đoạn kết thúc, bước công khai luôn tăng (test ghim).
- `OFFER` hiện "Mời phỏng vấn". Offer đến sau phỏng vấn: nếu `OFFER` là "Đang xem xét", ứng viên đã thấy "Mời phỏng vấn" sẽ thấy hồ sơ lùi một bước; nếu là "Kết thúc", ứng viên đang chờ offer tưởng hồ sơ đã xong, và lúc nhận việc hay bị loại thì trạng thái không đổi. Offer chưa phải kết quả; kết quả là nhận việc, bị loại hoặc rút hồ sơ.
- "Kết thúc" không cho biết kết quả: trúng tuyển, không phù hợp và đã rút hiện giống nhau.
- Trạng thái chỉ theo giai đoạn của chính hồ sơ: tạm dừng, đóng hay hủy yêu cầu tuyển dụng (kể cả khi tin của yêu cầu bị đóng theo) không đổi trạng thái công khai, vì hồ sơ giữ nguyên giai đoạn (câu hỏi 20, ý 5).
- Sprint 4 chưa có API chuyển giai đoạn cho người tuyển dụng (S5-02), nên tới Sprint 5 hồ sơ hiện "Đã nhận", trừ hồ sơ ứng viên tự rút (task 345) hiện "Kết thúc".

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 24, ý (1)–(4).

### Ghi chú cho frontend

- Màn hình giới thiệu (task 348, 349) đã dùng đúng bốn mã này (`ReferralStatus`), nhưng `mapToGeneralStatus` thiếu `INTERVIEW` (rơi về `RECEIVED`) và xếp `OFFER` vào `COMPLETED`. Khi có danh sách của task 352, hiển thị `status` và `statusName` của server, không tự ánh xạ.
- Trang tra cứu (task 337, 338) đang dùng sáu mã (`RECEIVED`, `IN_REVIEW`, `INTERVIEW`, `HIRED`, `REJECTED`, `WITHDRAWN`); API tra cứu (339) chỉ trả bốn mã ở bảng trên, kèm `statusName`.
