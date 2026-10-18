# Đánh giá sàng lọc nội bộ theo hồ sơ ứng tuyển

Áp dụng cho TKNHTTDNB1-386, 387, 388. Backend Java/PostgreSQL; frontend gọi API bên dưới bằng access token của nhân viên.

Một ứng viên có thể nộp vào hai vị trí do hai recruiter khác nhau quản lý. Vì vậy mọi thao tác dùng **applicationId** của lần ứng tuyển cụ thể. Không lấy candidateId thay cho applicationId và không dùng các route `/candidates/{id}/tags`, `/rating`, `/notes`: các route này vẫn bị từ chối vì không xác định được phạm vi vị trí.

|Thao tác|Method và URL|Body|
|---|---|---|
|Đọc đánh giá và ghi chú|GET `/api/v1/applications/{applicationId}/screening?page=0&size=20`|Không có|
|Thay toàn bộ nhãn đã chọn|PUT `/api/v1/applications/{applicationId}/screening/tags`|`{"tagIds":["UUID-nhan-1","UUID-nhan-2"]}`|
|Cập nhật điểm chung|PUT `/api/v1/applications/{applicationId}/screening/rating`|`{"rating":4}`|
|Thêm ghi chú nội bộ|POST `/api/v1/applications/{applicationId}/screening/notes`|`{"content":"Cần hỏi thêm về kinh nghiệm Java."}`|

Các API thành công trả HTTP 200 và cùng dạng dữ liệu. Thời gian dùng UTC ISO 8601; frontend có thể đổi sang giờ Việt Nam khi hiển thị.

```json
{
  "applicationId": "uuid-ho-so",
  "tags": [{"applicationId":"uuid-ho-so","id":"uuid-nhan","code":"JAVA","name":"Java","active":true}],
  "rating": {"value":4,"updatedBy":"uuid-nhan-vien","updatedByName":"Nguyễn Văn A","updatedAt":"2026-10-17T10:00:00Z"},
  "notes": [{"id":"uuid-ghi-chu","applicationId":"uuid-ho-so","content":"Cần hỏi thêm.","authorId":"uuid-nhan-vien","authorName":"Nguyễn Văn A","createdAt":"2026-10-17T10:00:00Z"}],
  "totalNotes": 1,
  "notesPage": 0,
  "notesSize": 20
}
```

- `rating` là null khi chưa đánh giá. Đây là điểm chung gần nhất, không phải điểm trung bình. Chỉ nhận số nguyên JSON từ 1 đến 5; chuỗi `"4"`, số thập phân `4.0` hoặc `null` đều bị từ chối.
- Lấy lựa chọn nhãn bằng GET `/api/v1/recruitment-catalogs/CANDIDATE_TAG/items?active=true`. Quyền đọc danh mục là ORGANIZATION_READ_ALL, hiện có cho các vai trò nhân viên mặc định. HR/ADMIN có ORGANIZATION_WRITE_ALL tạo nhãn qua POST cùng URL với body `{"code":"JAVA","name":"Java","active":true}`; không tự nhập tag ngoài danh mục ở API screening.
- Tối đa 30 nhãn khác nhau, không có null. Gửi `{"tagIds":[]}` để bỏ hết nhãn. Nhãn inactive không được chọn lại; nếu nó đã được gắn trước khi ngừng dùng, GET vẫn hiện với `active:false` để giữ ngữ cảnh.
- Ghi chú là văn bản thuần tối đa 2000 ký tự có ký tự nhìn thấy được. Backend lấy người viết và thời gian từ phiên đăng nhập/máy chủ. Không có API sửa hoặc xóa ghi chú.
- GET phân trang notes từ 0, size 1–100, mặc định 20. Sắp xếp mới nhất trước, thời gian bằng nhau dùng UUID giảm dần để ổn định. `totalNotes` là tổng số ghi chú của application, không phải số dòng trang hiện tại. Khi vừa có người thêm ghi chú, tải lại trang 0.
- Sau thao tác ghi, response chứa trang notes đầu tiên, tối đa 20 dòng.

Đọc cần CANDIDATES_READ_ALL hoặc READ_SCOPED và được phép xem đúng application. Ghi cần thêm CANDIDATES_WRITE_ALL hoặc WRITE_SCOPED; scoped writer phải là recruiter đang được phân công vào yêu cầu tuyển dụng của application. Quản lý phòng ban có quyền đọc không tự có quyền ghi. Backend kiểm lại phiên, quyền và phạm vi sau mỗi lần chờ khóa, kể cả khi nhãn đang bị HR sửa.

|HTTP|Ý nghĩa|
|---|---|
|400|Body/điểm/note/phân trang không hợp lệ; SCREENING_TAG_INVALID nếu nhãn không tồn tại, inactive hoặc sai loại|
|401|Chưa đăng nhập, phiên bị thu hồi hoặc token hết hạn|
|403|Thiếu quyền hoặc application ngoài phạm vi hiện tại|
|404|APPLICATION_NOT_FOUND: không tồn tại application có liên kết profile|
|503|SCREENING_BUSY: một giao dịch đang giữ khóa quá lâu; không có thay đổi từng phần|

Mỗi lần đọc/response sau ghi được ghi vào candidate_access_events dạng PROFILE cho đúng application. API trả Cache-Control:no-store.

Trang unified-profile tiếp tục lọc application theo phạm vi cũ. Mỗi application đã có dữ liệu sẽ có `screening`; không có đánh giá thì bỏ field này. Mục đó có tối đa 5 note mới nhất và totalNotes để frontend mở API phân trang. `tags` và `notes` đầu profile tổng hợp từ những application được đọc, luôn kèm applicationId; notes đầu profile là preview tối đa 20 dòng. Điểm nằm riêng ở từng application, không gộp một điểm cho cả candidate.

API tra cứu bằng mã/email của ứng viên, API trạng thái giới thiệu và email xác nhận không có tags/rating/notes/tên người đánh giá. Frontend phải hiển thị note dưới dạng văn bản và chỉ gọi API nội bộ khi có phiên nhân viên hợp lệ.

V41 thêm 3 bảng con và loại catalog mới, không sửa migration cũ hoặc dữ liệu ứng tuyển cũ. Bản triển khai chứa cả V40/V41/V42 phải được root kiểm thứ tự migration; không chạy riêng V41 lên database production trước các nhánh phụ thuộc.
