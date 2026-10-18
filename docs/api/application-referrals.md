# Giới thiệu ứng viên nội bộ

Phạm vi TKNHTTDNB1-350 (lưu nguồn giới thiệu và người giới thiệu), story TKNHTTDNB1-46 (S4-07): nhân viên nội bộ giới thiệu ứng viên cho một vị trí đang tuyển. Tiêu chí của story:

- "Nhân viên nội bộ nộp hồ sơ giúp người quen kèm ghi chú giới thiệu": lớp lưu trữ đã có (350); API cho nhân viên đã đăng nhập là task 351.
- "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu": đã có (350). Hồ sơ ứng viên hợp nhất hiện `source.type = "INTERNAL_REFERRAL"`, nhãn "Giới thiệu nội bộ", id và họ tên người giới thiệu cùng ghi chú.
- "Người giới thiệu theo dõi được trạng thái ở mức tổng quát": task 352.

Task 350 thêm migration V36 và lớp lưu trữ nội bộ `ReferralApplicationStorage`; **chưa có endpoint**, không thêm mã quyền, ma trận phân quyền không đổi (1581 ca).

## Lưu giới thiệu (task 350)

`ReferralApplicationStorage.store(postingId, submission, cv, trackingHash, referral)` (gói `vn.ttcs.recruitment.application`) lưu một hồ sơ do nhân viên nộp thay ứng viên, cùng bản ghi giới thiệu, trong **transaction của bên gọi** (`MANDATORY`). `referral` là `ReferralDeclaration(referrerId, note, candidateConsent)`:

| Thành phần | Ý nghĩa |
|---|---|
| `referrerId` | tài khoản người giới thiệu. API của task 351 lấy từ token của người gọi, **không bao giờ** nhận từ form |
| `note` | ghi chú giới thiệu, đúng như người giới thiệu gõ; `store()` chuẩn hóa và kiểm |
| `candidateConsent` | người giới thiệu xác nhận ứng viên đã đồng ý cho gửi hồ sơ; phải là `true` |

### Thứ tự xử lý

1. Lỗi lập trình của bên gọi, trước mọi câu SQL: không có transaction → `IllegalTransactionStateException`; transaction chỉ đọc hoặc không phải READ COMMITTED → `IllegalStateException`; thiếu tin tuyển dụng, bản khai, người giới thiệu, chưa xác nhận đồng ý, hoặc hồ sơ có nguồn danh mục (`sourceId`) → `IllegalArgumentException`. API của task 351 chặn từng trường hợp này trước khi gọi.
2. Ghi chú, vẫn trước mọi câu SQL: xuống dòng `\r\n`, `\r` thành `\n`; ghi chú không có ký tự nhìn thấy được coi như không có (cùng quy tắc với lý do rút hồ sơ, `ApplicationText`); bắt buộc; tối đa 2.000 ký tự (đếm sau khi chuẩn hóa xuống dòng); văn bản thuần (`@PlainText(multiline = true)`: không thẻ HTML, không ký tự điều khiển trừ xuống dòng và tab, không ký tự đảo chiều). Sai: 400 `VALIDATION_ERROR` "Vui lòng kiểm tra ghi chú giới thiệu." với `fieldErrors.referralNote` là một trong "Vui lòng nhập ghi chú giới thiệu.", "Ghi chú giới thiệu tối đa 2.000 ký tự.", "Ghi chú giới thiệu chỉ được là văn bản thuần, không chứa thẻ HTML hoặc ký tự điều khiển.". Thông báo không lặp lại ghi chú.
3. Đọc yêu cầu tuyển dụng của tin (không khóa: tin không bao giờ đổi yêu cầu). Không có tin: 404 `JOB_POSTING_NOT_FOUND` "Tin tuyển dụng không còn nhận hồ sơ." như `store()`.
4. **Khóa yêu cầu tuyển dụng `FOR NO KEY UPDATE`** (mục "Khóa").
5. `ApplicationStorageService.store` của TRAN DUC LUONG, chung transaction: kiểm yêu cầu `APPROVED`, tin `PUBLISHED` còn hạn (404 như trên), quy tắc trùng chung của task 327 (409 `APPLICATION_ALREADY_SUBMITTED`), liên kết hồ sơ ứng viên (task 360), CV.
6. Ghi dòng `application_referrals`: người giới thiệu, ghi chú đã chuẩn hóa, `consent_declared_at` = thời điểm nhận hồ sơ (`received_at`, cắt tới micro giây).

Lỗi ở bất kỳ bước nào làm transaction của bên gọi rollback: không còn hồ sơ, CV, hồ sơ ứng viên, liên kết hay dòng giới thiệu nào. Dòng giới thiệu ghi **sau** hồ sơ (khóa ngoại của nó cần dòng hồ sơ), nên một hồ sơ trùng trả 409 chứ không phải lỗi khóa ngoại.

### Trùng hồ sơ

Hồ sơ giới thiệu và hồ sơ tự nộp dùng **cùng** bộ chặn trùng của task 327: một email đã chuẩn hóa chỉ có một hồ sơ trong mỗi yêu cầu tuyển dụng, theo cả hai chiều. Ứng viên đã tự nộp thì lần giới thiệu sau trả 409; hồ sơ đã được giới thiệu thì ứng viên tự nộp sau nhận 409 (câu hỏi 27 sẽ hỏi BA/PO có nên cho thay thế không, task 351).

### Khóa

Mọi thao tác làm một hồ sơ trở thành "đang xử lý" phải khóa yêu cầu tuyển dụng `FOR NO KEY UPDATE` (quy tắc của task 321, `RequisitionPipelineSource`), để việc đóng hay tạm dừng yêu cầu chờ lần giới thiệu rồi đếm cả nó, không bao giờ đếm thiếu. `ReferralApplicationStorage` tự khóa, dù ai gọi. Khóa `FOR SHARE` của `store()` trên yêu cầu sau đó không tốn gì (transaction đang giữ khóa mạnh hơn thì nhận khóa yếu hơn ngay). Thứ tự: yêu cầu (`FOR NO KEY UPDATE`) → tin (`FOR SHARE`, trong `store()`) → chỉ mục unique của hồ sơ và của hồ sơ ứng viên → CV → dòng giới thiệu (khóa ngoại lấy `FOR KEY SHARE` trên tài khoản người giới thiệu; API của task 351 đã giữ tài khoản đó `FOR NO KEY UPDATE`). Khi `store()` được nối với `JobPostingApplicationGate` của cổng việc làm (task 321), khóa riêng này được thay bằng lời gọi cổng.

Transaction phải là READ COMMITTED và ghi được: liên kết hồ sơ ứng viên (task 360) và việc đọc lại dữ liệu sau khi chờ khóa cần READ COMMITTED.

### Nhãn "Giới thiệu nội bộ" lấy từ bản ghi, không từ danh mục

Hồ sơ được giới thiệu có `source_id` để trống. Nhãn "Giới thiệu nội bộ" suy ra từ dòng `application_referrals`, không từ danh mục "Nguồn ứng viên" (`CANDIDATE_SOURCE`, V10), vì:

- HR đổi tên hoặc ngừng dùng giá trị danh mục được, còn nhãn của hồ sơ đã giới thiệu không được đổi theo;
- V10 không seed sẵn giá trị nào, nên không có giá trị "Giới thiệu nội bộ" chắc chắn tồn tại;
- nguồn ứng viên tự chọn trên form công khai chỉ là lời khai: chọn "Nhân viên giới thiệu" ở đó không có người giới thiệu, không có ghi chú và không phải một lần giới thiệu.

HR không cần tạo giá trị danh mục cho giới thiệu nội bộ (xem [danh mục tuyển dụng](recruitment-catalogs.md)).

### Thời điểm xác nhận đồng ý

Người giới thiệu gửi dữ liệu cá nhân của người khác, nên API của task 351 bắt người giới thiệu xác nhận ứng viên đã đồng ý (`candidateConsent`). `consent_declared_at` ghi **khi** lời xác nhận đó được đưa ra: cùng lúc với việc nộp, nên bằng `received_at` của hồ sơ. Cột này ghi lại rằng có lời xác nhận và lúc nào; nó không phải bằng chứng ứng viên đã đồng ý (câu hỏi 27).

### Ai thấy ghi chú

- Người được đọc lần ứng tuyển đó trong [hồ sơ ứng viên hợp nhất](candidate-profiles.md#response-200) (task 361, quy tắc của task 363): ghi chú nằm trong `source.note`, người giới thiệu là `source.referrerId` và `source.referrerName` (họ tên hiện tại), không bao giờ là email.
- Người đọc không được xem lần ứng tuyển đó không thấy gì của nó, kể cả việc nó được giới thiệu. Là người giới thiệu cũng không cho quyền xem hồ sơ ứng viên.
- Ứng viên không bao giờ thấy ghi chú: không có trong API tra cứu, rút hồ sơ hay email.
- Task 352 sẽ cho người giới thiệu xem lại ghi chú của chính mình trong danh sách giới thiệu.

### Schema (V36)

```sql
CREATE TABLE application_referrals (
    application_id UUID PRIMARY KEY REFERENCES job_applications(id) ON DELETE CASCADE,
    referrer_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    note VARCHAR(2000) NOT NULL,
    consent_declared_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT application_referrals_visible_note CHECK (note ~ '[^[:space:]]')
);
CREATE INDEX application_referrals_referrer_idx ON application_referrals(referrer_id);
```

- Một dòng cho mỗi hồ sơ (khóa chính `application_id`); xóa hồ sơ thì dòng đi theo (`ON DELETE CASCADE`).
- Người giới thiệu `ON DELETE RESTRICT` như mọi tham chiếu tới nhân viên: không xóa được tài khoản còn có lần giới thiệu. Chỉ mục `application_referrals_referrer_idx` phục vụ kiểm tra RESTRICT và danh sách "giới thiệu của tôi" (task 352).
- Ghi chú có ít nhất một ký tự PostgreSQL không coi là khoảng trắng; API còn chặn chặt hơn (ký tự rộng 0, văn bản thuần).
- Không có khóa ngoại tới danh mục; không cột nào của `job_applications` đổi.

## Kiểm thử (task 350)

- `ApplicationReferralStorageIntegrationTest` (PostgreSQL thật, không HTTP): một lần giới thiệu ghi hồ sơ (`NEW`, `source_id` trống, bản chụp tên tin), CV, liên kết hồ sơ ứng viên và dòng giới thiệu với ghi chú đã chuẩn hóa xuống dòng và `consent_declared_at` = `received_at` cắt tới micro giây; ghi chú đúng 2.000 ký tự được giữ, ghi chú 2.001 ký tự thô còn 2.000 sau khi `\r\n` thành `\n` cũng vậy; trùng theo cả hai chiều (tự nộp rồi giới thiệu, giới thiệu rồi tự nộp) trả 409 và không có dòng giới thiệu thừa; yêu cầu tạm dừng, tin đã đóng, quá hạn nộp, tin không tồn tại trả 404 và không lưu gì; ghi chú trống, chỉ có ký tự vô hình, 2.001 ký tự, có thẻ HTML, NUL hay ký tự đảo chiều trả 400 `referralNote` trước khi tới 404 của tin không tồn tại; chưa xác nhận đồng ý, thiếu người giới thiệu, thiếu bản khai, thiếu tin, có `sourceId` → `IllegalArgumentException`; không transaction → `IllegalTransactionStateException`; REPEATABLE READ, SERIALIZABLE, chỉ đọc → `IllegalStateException`; rollback của bên gọi và người giới thiệu không tồn tại (khóa ngoại) không để lại gì; schema: một dòng mỗi hồ sơ, CHECK ghi chú, độ dài, `NOT NULL`, khóa ngoại, RESTRICT tài khoản, CASCADE theo hồ sơ, chỉ mục; khóa: một kết nối giữ dòng tin, lần giới thiệu chờ trong `store()` (`pg_blocking_pids`), và `FOR SHARE NOWAIT` trên yêu cầu từ kết nối thứ ba bị từ chối (55P03), chứng tỏ yêu cầu bị khóa `FOR NO KEY UPDATE` chứ không chỉ `FOR SHARE`.
- `ApplicationSourceTest`: khối `source` của lần giới thiệu có đúng `type`, `label`, `referrerId`, `referrerName`, `note`; của trang tuyển dụng giữ ba khóa của task 361; dòng giới thiệu quyết định nguồn; `toString` ẩn họ tên và ghi chú.
- `CandidateProfileIntegrationTest`: HR_MANAGER, ADMIN và recruiter chính của yêu cầu thấy lần được giới thiệu với `INTERNAL_REFERRAL`, người giới thiệu (id, họ tên) và ghi chú, không có email người giới thiệu; recruiter hỗ trợ của yêu cầu khác không thấy gì của lần đó; người giới thiệu nhận 403 khi mở hồ sơ ứng viên.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 27, ý (1), (2).
