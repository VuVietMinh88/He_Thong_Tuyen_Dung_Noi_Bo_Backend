# Email ứng viên, nhật ký gửi, thông báo trong ứng dụng và SLA

Tài liệu của lane messaging (Sprint 7–8 và S5-10): mẫu email (story S8-01), nhật ký gửi email (S8-02), gửi thư từ chối
hàng loạt (S7-09), thông báo trong ứng dụng (S8-03), tùy chọn kênh thông báo (S8-04) và cảnh báo quá hạn SLA (S5-10).
Mọi API trả `Cache-Control: no-store`, lỗi theo dạng `{code, message, fieldErrors}` (thông báo tiếng Việt), từ chối
trường JSON lạ (`INVALID_JSON`). 401 khi token/phiên không còn hiệu lực, 403 trước 404.

## Mẫu email theo loại (task 531)

Migration `V66__create_email_templates.sql` tạo bảng `email_templates`, một dòng cho mỗi loại thư gửi ứng viên:

| `template_type` | Thư | Biến được hỗ trợ | Gửi tự động mặc định |
|---|---|---|---|
| `APPLICATION_RECEIPT` | Xác nhận đã nhận hồ sơ | `candidateName`, `positionTitle`, `companyName` | Bật |
| `INTERVIEW_INVITATION` | Mời phỏng vấn | `candidateName`, `positionTitle`, `interviewTime`, `companyName` | Bật |
| `INTERVIEW_THANKS` | Cảm ơn sau phỏng vấn | `candidateName`, `positionTitle`, `interviewTime`, `companyName` | Tắt |
| `REJECTION` | Thư từ chối | `candidateName`, `positionTitle`, `companyName` | Tắt |
| `OFFER` | Thư offer | `candidateName`, `positionTitle`, `companyName` | Bật |

Cột: `subject` (một dòng, tối đa 200 ký tự), `body` (văn bản thuần, tối đa 5.000 ký tự, dòng trống tách đoạn),
`supported_variables` (danh sách cố định của loại, API không sửa), `auto_send`, `version` (tăng mỗi lần lưu),
`updated_by` (`ON DELETE SET NULL`), `updated_at`. CHECK của database chặn tiêu đề trống hoặc có ký tự điều khiển (kể
cả CR/LF, nên mẫu không thể chèn header thư), nội dung có ký tự điều khiển ngoài xuống dòng/tab, loại lạ và danh sách
biến rỗng.

Quy tắc văn bản (`TemplateText`, dùng chung cho API, bản xem trước và mọi luồng gửi):

- Biến viết `{{tenBien}}`, tên chỉ gồm chữ cái và phải thuộc danh sách của loại. `{{ candidateName }}`, `{{salary}}`,
  `{{` hoặc `}}` thừa đều bị từ chối, không bao giờ gửi nguyên văn.
- Không dùng thẻ HTML (`<b>`…); `<` đứng riêng như "lương < 20 triệu" vẫn hợp lệ.
- Giá trị chèn vào luôn nằm trên một dòng: ký tự điều khiển, ký tự định dạng hoặc phân cách dòng trong tên ứng viên
  thành khoảng trắng.
- Phần bắt buộc (mã tra cứu, liên kết tra cứu/rút hồ sơ, chi tiết và liên kết phản hồi lời mời phỏng vấn, điều khoản
  và liên kết phản hồi offer) **không** nằm trong mẫu: hệ thống luôn thêm sau phần mẫu, nên sửa mẫu không làm mất các
  nội dung này.

Mặc định của V66 tái tạo đúng thư đang gửi trước đây; hai thư mới (cảm ơn sau phỏng vấn, từ chối) mặc định tắt gửi tự
động để triển khai bản này không gửi thêm thư nào cho tới khi HR bật.

Câu hỏi BA/PO: mặc định bật/tắt của hai thư mới; có cần lưu lịch sử từng phiên bản mẫu (hiện chỉ giữ người sửa cuối và
`version`).
