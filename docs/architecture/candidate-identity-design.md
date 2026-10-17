# Nhóm ứng viên, cảnh báo trùng và hợp đồng gộp/hoàn tác

## Phạm vi

Thiết kế chung cho 367–370. Bản triển khai đầu có 367: phát hiện và đọc cảnh báo.
Nền lưu trữ audit gộp được mô tả tại [candidate-merge-audit](candidate-merge-audit.md).
API xem trước/gộp/đọc kết quả và hoàn tác đã có trong [candidate-merge-api.md](candidate-merge-api.md), mặc định tắt.
Không tự liên kết hai application vì email/điện thoại trùng.

## Dữ liệu và quyền

- `job_applications`, CV, mã tra cứu, khóa chống trùng cùng requisition và receipt outbox giữ nguyên.
- `candidate_profiles` định danh nhóm; `candidate_memberships` liên kết application vào nhóm hiện tại.
  Worker tạo nhóm một thành viên với UUID bằng application ID, chỉ khi chưa có membership.
  Membership hiện có luôn được giữ nguyên, kể cả membership do module gộp tạo trong tương lai.
- Thông tin có hiệu lực lấy theo từng application: confirmed-contact nếu có, nếu chưa dùng submitted.
  OCR chưa xác nhận không tham gia nhận diện. Không chỉ lấy thông tin của một application đại diện để tìm trùng.
- Mỗi người dùng cần quyền `CANDIDATES_READ` thực tế. Scope ALL theo grant hiện có; không thêm grant hoặc sửa seed.
  Scope SCOPED: requisition được phân công chính/hỗ trợ; hiring manager có thể xem cây phòng ban họ quản lý.
  Quy tắc cây phòng ban được người dùng chốt ngày16/10/2026 và dùng lại DepartmentRepository.
- API chỉ trả chi tiết application trong phạm vi. Trùng ngoài phạm vi chỉ có thông báo chung, không ID,
  PII, vị trí hay số lượng. Không trả candidate ID trong API cảnh báo này.
- Gộp không phải cấp quyền: các consumer hồ sơ hợp nhất/timeline/CV sau này vẫn lọc từng application.

## Phát hiện nền

- Chỉ so email trim/Locale.ROOT lowercase; không bỏ dấu chấm hoặc +tag.
- Phone dùng normalization hiện có của ApplicationSubmission: bỏ separator, giữ0,+,mã nước;
  `0912345678` KHÁC `+84912345678`. Không fuzzy match hoặc so đuôi.
- `candidate_effective_contacts` là view nội bộ dùng thông tin đã xác nhận hoặc bản nộp.
  Sử dụng normalized_email Java đã lưu; không dùng PostgreSQL lower() theo locale khác.
- `candidate_contact_index` có key email/phone không unique, revision nguồn, candidate_id hiện tại,
  normalizer version và thời điểm lập chỉ mục. Đổi membership làm index cần cập nhật, kể cả khi contact không đổi.
- Cặp trùng lưu theo thứ tự UUID PostgreSQL, lý do email/phone và revision hai bên. Không có cạnh tự trùng.
  Quan hệ không bắc cầu: A-B và B-C không tạo A-C hoặc tự gộp ba người.
- Worker dùng transaction riêng REPEATABLE_READ, advisory try-lock chung và batch hữu hạn.
  Phát hiện bản chưa lập chỉ mục hoặc nguồn/nhóm/version đã đổi; không dùng watermark thời gian.
  Transaction nộp muộn, sửa liên hệ trong lúc worker đang chạy hoặc worker restart sẽ được quét bù ở lượt sau.
- Trong cùng transaction: đảm bảo singleton membership nếu thiếu, upsert index, xóa cặp cũ của application,
  tạo cặp với các index còn khớp nguồn hiện tại. Lỗi rollback cả batch, không có cập nhật một phần.
- API đọc trong snapshot REPEATABLE_READ, kiểm lại revision/key của cả hai application và nhóm hiện tại.
  Cảnh báo nguồn cũ hoặc hai application đã cùng nhóm bị ẩn ngay, không chờ worker.
- Phát hiện có độ trễ: AVAILABLE nghĩa liên hệ của application đã được lập chỉ mục,
  KHÔNG bảo đảm mọi application khác vừa commit đã được xử lý. Có thể xuất hiện cảnh báo mới ở lần đọc sau.
- Scheduler tắt mặc định, retry ở lần poll kế tiếp, không log SQL/PII khi lỗi.
  Luồng nộp không gọi detector, không có FK từ application gốc tới bảng detector, không lấy advisory lock này.

## Hợp đồng thiết kế cho 369/368/370

Nền lưu trữ 369, orchestration API gộp và thực thi hoàn tác 368/370 đã có; xem tài liệu API để biết cấu hình và hợp đồng hiện tại.

1. Bổ sung đại diện nhóm bằng một application thuộc membership của nhóm, cùng ràng buộc/revision phù hợp.
   Hiển thị liên hệ có hiệu lực của đại diện, không trộn trường hoặc ghi sang các application còn lại.
2. Audit gộp append-only: actor, thời gian DB, hai nhóm, partition thành viên/đại diện/revision trước-sau,
   hạn hoàn tác và quan hệ phụ thuộc. Không xóa các nhóm lịch sử vẫn được tham chiếu bởi audit.
3. Preview phải kiểm quyền xem toàn bộ hai nhóm, trả version; merge cần READ+WRITE trên toàn bộ thành viên.
   Version phải bao gồm revision nhóm, tập thành viên và revision liên hệ của từng application, để sửa contact
   sau preview cũng làm preview hết hiệu lực. Lấy khóa theo thứ tự nhất quán, kiểm lại session/quyền/phân công
   và version sau chờ khóa.
4. Membership, revision nhóm và audit ghi trong CÙNG transaction; lỗi audit rollback gộp.
   Idempotency kiểm cả payload, không tạo thêm audit khi retry cùng thao tác.
5. Timestamp gộp lấy clock_timestamp() tại bước ghi cuối sau khi lấy đủ khóa; transaction thành công
   mới làm audit có hiệu lực. Hạn24h tính theo timestamp DB được lưu, không dùng thời gian client hoặc lúc request tới.
6. Undo chỉ thao tác mới nhất còn hiệu lực ảnh hưởng nhóm hiện tại, trong24h; kiểm quyền hiện tại.
   A+B rồi AB+C phải undo AB+C trước. Không gia hạn hạn của A+B khi undo AB+C.
7. Undo phục hồi partition/đại diện, không snapshot-restore toàn application. Dữ liệu mới, stage, CV,
   liên hệ và email phát sinh sau gộp được giữ nguyên. Ghi sự kiện undo riêng, không sửa audit gốc.
8. Merge/undo phải làm detector nhận biết membership đã đổi; không để cache cũ quyết định quyền hoặc chặn thao tác.
   Index lưu candidate_id nên tự bị xem là cũ khi membership đổi; không gọi worker đồng bộ dưới khóa gộp.
   Preview/merge luôn kiểm lại dữ liệu gốc hiện tại, không coi cảnh báo cache là bằng chứng danh tính.
9. Email mới dùng contact của đúng application theo ApplicationContactResolver; email đã queue/gửi không đổi.
   Không tự chặn thư mời lần2 trong367–370. Các module phỏng vấn/timeline/hồ sơ hợp nhất tích hợp riêng.

## Điều kiện chốt từng bước

367: kiểm thử key/OR/scoped redaction/backfill/nguồn đổi/nhóm đổi/late commit/worker lỗi/intake độc lập.
369: schema audit và dữ liệu undo nguyên tử, không sửa/xóa lịch sử trực tiếp.
368: preview+merge có version/khóa/idempotency/quyền và audit369.
370: undo dependency/deadline/concurrency/data mới. Không mở chức năng gộp trước khi370 hoàn tất.
