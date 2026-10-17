# Lưu dấu vết gộp và dữ liệu hoàn tác

Nền lưu trữ nội bộ cho thao tác gộp. Chưa có HTTP preview, merge, đọc audit hoặc undo.
Không tự gộp từ cảnh báo; không mở tính năng hoặc thay quyền trong seed.

## Dữ liệu

Migration V32 bổ sung `representative_application_id`, `merge_head_id` vào `candidate_profiles`.
Đại diện khác NULL phải thuộc membership của nhóm, bằng FK composite deferred.
Nhóm rỗng lịch sử được giữ lại. V31 chỉ tạo singleton; migration khởi tạo đại diện từ member đầu theo UUID,
không thay liên kết hoặc revision. Worker mới tạo singleton cùng đại diện trong transaction hiện có.
Xóa application đại diện làm tham chiếu đại diện thành NULL; không tự chọn application khác.

`candidate_merge_operations` là một hàng bất biến cho mỗi thao tác:

- UUID, actor UUID/tên tại thời điểm ghi, UUID request key và SHA-256 payload phiên bản hóa.
- Hai nhóm nguồn theo thứ tự UUID PostgreSQL; nhóm kết quả là một trong hai nhóm.
- Revision/đại diện/head trước của mỗi nhóm; revision sau tăng đúng một; đại diện kết quả do caller chọn.
- `members_before`: toàn bộ member của cả hai nhóm, gồm application UUID, nhóm trước và contact revision.
  Partition sau được suy ra chính xác: toàn bộ member thuộc `result_group_id`.
- Head trước là các dependency trực tiếp, có FK vào thao tác gộp trước; có thể là hai nhánh gộp độc lập.
- Thời gian DB và hạn hoàn tác cách đúng 24 giờ.

Snapshot JSONB nằm trong chính hàng audit, không có bảng con để append member vào lịch sử đã ghi.
DB kiểm shape, ID duy nhất, đủ hai nguồn, đại diện và trạng thái sau khớp profile/membership/contact hiện tại.
Nguồn trước được đọc bởi primitive dưới khóa; trigger không chứng minh lịch sử trước nếu owner tự viết SQL giả.
Snapshot tối đa 1 MiB, không lưu thêm tên/email/phone/CV của ứng viên; schema từ chối field JSON ngoài hợp đồng.

Audit FK vào hai nhóm, giữ nhóm lịch sử dù đã rỗng và detector đã cập nhật index.
Actor/application UUID là snapshot độc lập: xóa account/application theo hành vi hiện có không xóa audit.
Điều này không cam kết undo được hồ sơ đã bị xóa; module undo phải từ chối precondition không còn đáp ứng.

## Primitive nội bộ

`CandidateMergeJournal` có các thao tác `snapshot`, `append`, `find`, đều yêu cầu transaction của caller.
`append` chỉ nhận transaction READ_COMMITTED có quyền ghi; từ chối isolation khác trước khi đổi dữ liệu.

1. Advisory lock theo actor + request key, rồi kiểm kết quả đã ghi.
2. Cùng key/payload trả thao tác cũ; cùng key khác payload bị từ chối.
3. Khóa nhóm theo UUID bằng FOR NO KEY UPDATE, sau đó requisition và application/member theo UUID.
4. Đọc lại metadata và toàn bộ liên hệ có hiệu lực; so fingerprint của preview.
5. Chọn đại diện thuộc tập member, chuyển liên kết, tăng revision hai nhóm, đặt head mới.
6. INSERT audit là bước ghi chính cuối cùng; trigger lấy clock_timestamp() sau các bước chờ khóa/ghi.

Membership, representative, head, revision và audit nằm trong CÙNG transaction.
Caller phải kết thúc transaction ngay sau ghi, không làm SMTP/OCR/network hoặc công việc chậm sau append.
Timestamp DB là mốc lưu tại bước ghi cuối; hàng chỉ có hiệu lực khi commit, không phải timestamp HTTP/client.
Lỗi JDBC, constraint hoặc caller rollback không để lại thay đổi một phần.
Không gọi detector dưới khóa; index đang có tự cũ khi candidate_id đổi, worker xử lý bù sau commit.

## Ranh giới bảo mật

Primitive này KHÔNG xác thực JWT, grant hoặc scope. Không được nối trực tiếp từ controller vào repository.
Module API tiếp theo phải kiểm READ+WRITE của toàn bộ hai nhóm, cả khi retry, và kiểm lại session/quyền/phân công
sau khi lấy khóa. Orchestration/callback cần thiết cho bước kiểm sau chờ khóa phải được thực hiện trước khi mở API;
việc gọi authorization một lần trước `append` là chưa đủ.
Không trả snapshot/actor/key trên endpoint công khai, không log các object này hoặc SQL exception chứa giá trị bind.
Các record tổng hợp có toString che dữ liệu; SQL chỉ bind giá trị và ghép fragment cố định.

## Hợp đồng cho undo sau này

`candidate_merge_undo_events` chỉ là mô hình lưu trữ: UUID, merge ID duy nhất, actor/key/hash,
revision thực tế trước/sau undo và timestamp DB. Chưa có Java writer hoặc thực thi undo.
Partition, đại diện và dependency cần khôi phục lấy từ sự kiện gộp bất biến.

- Chỉ undo thao tác mới nhất còn hiệu lực của nhóm, trong hạn DB; tại deadline là hết hạn.
- A+B rồi AB+C phải undo AB+C trước; hạn cũ của A+B không đổi.
- Phục hồi membership/đại diện/head trước; revision hiện tại tiếp tục tăng, tuyệt đối không trả số revision về trước.
- Khi undo thao tác sau, nhóm có thể có revision cao hơn snapshot sau của thao tác trước.
  Khả năng undo thao tác trước dựa vào active head/partition/hạn gốc và revision hiện tại cho chống ghi đè,
  không yêu cầu revision hiện tại bằng revision sau lịch sử của thao tác trước.
- So toàn bộ membership/current head trước khôi phục; không ghi đè member mới hoặc tái tạo application đã xóa.
- Giữ contact/CV/stage/email phát sinh sau gộp; không snapshot-restore application.
- Đổi liên kết và thêm undo event phải atomic; kiểm quyền hiện tại và chống retry như thao tác gộp.

Hai bảng audit chặn UPDATE/DELETE; TRUNCATE kể cả CASCADE bị chặn khi có lịch sử.
TRUNCATE bảng đang rỗng được phép vì không thể xóa lịch sử. Không có thao tác sửa/xóa audit trong Java.
DB owner vẫn có thể vô hiệu trigger/drop schema; deployment phải quản lý quyền owner/migration riêng phù hợp.

## Kiểm chứng

Unit test kiểm canonical order/fingerprint/hash/validation/redaction.
Integration kiểm transaction/isolation, metadata/partition/time, retry, stale contact, rollback, immutability,
retention, cạnh tranh, detector và nâng V31 có dữ liệu. Audit tests dùng schema/context riêng cho từng ca;
không vô hiệu hóa trigger để làm sạch fixture.
