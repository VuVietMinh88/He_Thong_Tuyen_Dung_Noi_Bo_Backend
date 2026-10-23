# Giới thiệu ứng viên nội bộ

Phạm vi TKNHTTDNB1-350 (lưu nguồn giới thiệu và người giới thiệu), TKNHTTDNB1-351 (API giới thiệu cho nhân viên đã đăng nhập) và TKNHTTDNB1-352 (người giới thiệu xem trạng thái tổng quát), story TKNHTTDNB1-46 (S4-07): nhân viên nội bộ giới thiệu ứng viên cho một vị trí đang tuyển. Tiêu chí của story:

- "Nhân viên nội bộ nộp hồ sơ giúp người quen kèm ghi chú giới thiệu": đã có: lớp lưu trữ (350) và `POST /api/v1/referrals` (351).
- "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu": đã có (350). Hồ sơ ứng viên hợp nhất hiện `source.type = "INTERNAL_REFERRAL"`, nhãn "Giới thiệu nội bộ", id và họ tên người giới thiệu cùng ghi chú.
- "Người giới thiệu theo dõi được trạng thái ở mức tổng quát": đã có (352): `GET /api/v1/referrals/my-referrals` chỉ trả các hồ sơ do chính người gọi giới thiệu, với trạng thái công khai của task 340.

Task 350 thêm migration V36 và lớp lưu trữ nội bộ `ReferralApplicationStorage`, không có endpoint. Task 351 thêm `POST /api/v1/referrals` và migration V37 (giới hạn lượt gửi), không thêm mã quyền; ma trận phân quyền từ 1581 lên 1596 ca. Task 352 thêm `GET /api/v1/referrals/my-referrals`, không có migration, không thêm mã quyền; ma trận từ 1596 lên 1611 ca.

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

Hồ sơ giới thiệu và hồ sơ tự nộp dùng **cùng** bộ chặn trùng của task 327: một email đã chuẩn hóa chỉ có một hồ sơ trong mỗi yêu cầu tuyển dụng, theo cả hai chiều. Ứng viên đã tự nộp thì lần giới thiệu sau trả 409; hồ sơ đã được giới thiệu thì ứng viên tự nộp sau nhận 409 (câu hỏi 27, ý 10: có nên cho thay thế không).

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
- Người giới thiệu xem lại ghi chú của chính mình trong [danh sách giới thiệu của mình](#theo-dõi-giới-thiệu-task-352) (task 352), không thấy ghi chú của người khác.

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

## API giới thiệu (task 351)

`POST /api/v1/referrals`, `Authorization: Bearer <access token>`, `Content-Type: multipart/form-data`. Nhân viên đã đăng nhập nộp hồ sơ của một ứng viên vào một tin đang nhận hồ sơ, kèm CV, ghi chú giới thiệu và lời xác nhận ứng viên đã đồng ý.

### Quyền

- `SELF_PROFILE_WRITE` ở matcher (`SecurityConfiguration`); cả sáu vai trò nội bộ đều có (V3, V5), như quyền ghi ảnh đại diện. Không thêm mã quyền. `ApplicationReferralWriter` kiểm lại mã này sau khi khóa tài khoản và phiên, sau khi khóa yêu cầu tuyển dụng và sau `store()`.
- Người giới thiệu **luôn** là người gọi (subject của JWT). Form không có trường người giới thiệu; gửi `referrerId`, `referrerName`, `referrerEmail` thì bị 400.
- Route không nằm trong danh sách bỏ qua Bearer: không có token hoặc token hỏng → 401, không có quyền → 403, trước khi đọc form.

### Form

Đúng bảy phần, thứ tự tùy ý:

| Phần | Giới hạn byte | Quy tắc |
|---|---|---|
| `jobPostingId` | 128 | UUID dạng chuẩn (có gạch, không ngoặc); bắt buộc |
| `fullName` | 1024 | như form nộp công khai: bắt buộc, tối đa 120 ký tự, văn bản thuần một dòng |
| `email` | 2048 | bắt buộc, email hợp lệ, tối đa 254 ký tự; chuẩn hóa như form công khai (bỏ ký tự vô hình hai đầu, chữ thường) |
| `phone` | 256 | bắt buộc; bỏ khoảng trắng, ngoặc, chấm, gạch; còn lại `+` tùy chọn và 8–15 chữ số |
| `referralNote` | 8192 | quy tắc của task 350 (mục "Thứ tự xử lý", bước 2) |
| `candidateConsent` | 16 | đúng chuỗi `true` |
| `cv` | 10 MiB | một tệp PDF hoặc DOCX, kiểm bằng `CvValidator` của 325 (gồm quy tắc nội dung tự động của task 362) |

Thân tối đa 11 MiB, kiểm cả `Content-Length` lẫn khi đọc (kể cả chunked). Route này bỏ qua parser multipart của Servlet (5/6 MiB) như route nộp công khai: `ApplicationMultipartDecoder.decodeReferral` dùng cùng vòng đọc streaming với `decode()` của TRAN DUC LUONG, không tạo tệp tạm. Phần dư (kể cả các tên frontend đang gửi: `requisitionId`, `candidateName`, `candidateEmail`, `candidatePhone`, `source`, `sourceId`, `coverLetter`, `referrer*`), phần lặp, phần thứ tám, UTF-8 hỏng, tệp tên khác `cv` (ví dụ `cvFile`) hoặc thiếu CV đều trả 400. Mọi lỗi theo trường được trả **cùng lúc** trong `fieldErrors`. Không thông báo nào lặp lại giá trị đã gửi hay tên tệp.

### Response 201

```json
{"id":"…","candidateName":"Nguyễn Văn A","jobTitle":"Lập trình viên Java","submittedAt":"2026-10-15T03:00:00Z",
 "status":"RECEIVED","statusName":"Đã nhận","referralNote":"Bạn học cũ, mạnh Java"}
```

`Cache-Control: no-store`. Đúng bảy khóa, cũng là một dòng của danh sách "giới thiệu của tôi" (task 352), dùng đúng tên frontend đang đọc (`id`, `jobTitle`, `status`): `id` là id hồ sơ ứng tuyển, `jobTitle` là bản chụp tên tin, `submittedAt` là lúc nhận hồ sơ, `status`/`statusName` là trạng thái công khai của task 340, `referralNote` là ghi chú đã chuẩn hóa. **Không bao giờ** có mã tra cứu hay liên kết tra cứu (chúng thuộc về ứng viên và chỉ được gửi qua email), email, số điện thoại, CV, giai đoạn nội bộ hay id yêu cầu tuyển dụng.

### Thứ tự kiểm và lỗi

| Bước | Trả lời |
|---|---|
| Matcher `PERM_SELF_PROFILE_WRITE` | 401 `UNAUTHORIZED` / 403 `FORBIDDEN` |
| `Content-Type` không phải `multipart/form-data` | 415 `APPLICATION_FORM_TYPE_UNSUPPORTED` "Vui lòng gửi biểu mẫu multipart/form-data kèm tệp CV." (trước bước sẵn sàng) |
| `Content-Length` > 11 MiB | 413 `APPLICATION_CV_TOO_LARGE` |
| Chưa cấu hình khóa mã hóa email xác nhận | 503 `REFERRAL_UNAVAILABLE` "Chức năng giới thiệu ứng viên chưa sẵn sàng. Vui lòng thử lại sau." |
| Quá `app.referral.max-concurrent` lần giới thiệu đang đọc cùng lúc | 503 `REFERRAL_BUSY` "Hệ thống đang bận. Vui lòng thử lại sau." + `Retry-After: 1` |
| Cấu trúc form | 400 `VALIDATION_ERROR` ("Biểu mẫu chứa trường dư hoặc lặp.", "Biểu mẫu có quá nhiều phần dữ liệu.", "Biểu mẫu phải có đúng một tệp CV.", "Vui lòng chọn tệp CV.") / 400 `INVALID_MULTIPART` / 413 `APPLICATION_CV_TOO_LARGE` |
| Các trường | 400 `VALIDATION_ERROR` "Vui lòng kiểm tra thông tin giới thiệu." + `fieldErrors` (`jobPostingId`, `fullName`, `email`, `phone`, `referralNote`, `candidateConsent` "Vui lòng xác nhận ứng viên đã đồng ý cho bạn gửi hồ sơ.") |
| CV | 400 `APPLICATION_CV_INVALID` / 400 `APPLICATION_CV_ACTIVE_CONTENT` / 413 `APPLICATION_CV_TOO_LARGE` / 415 `APPLICATION_CV_TYPE_UNSUPPORTED` |
| Giới hạn lượt gửi (V37) | 429 `REFERRAL_RATE_LIMIT_EXCEEDED` "Bạn đã gửi giới thiệu quá số lượt cho phép trong 24 giờ. Vui lòng thử lại sau." + `Retry-After` |
| Khóa tài khoản, phiên và kiểm lại người gọi | 401 `SESSION_INVALID` / 403 `FORBIDDEN` |
| Email ứng viên là email tài khoản của người gọi | 400 `REFERRAL_SELF_NOT_ALLOWED` "Không thể tự giới thiệu chính mình." (`fieldErrors.email`) |
| Tin không tồn tại hoặc không còn nhận hồ sơ | 404 `JOB_POSTING_NOT_FOUND` "Tin tuyển dụng không còn nhận hồ sơ." |
| Quy tắc trùng chung (task 327) | 409 `APPLICATION_ALREADY_SUBMITTED` (`fieldErrors.email`) |
| Mọi lỗi khác (kể cả hết thời gian chờ khóa) | 503 `REFERRAL_SUBMISSION_UNAVAILABLE` "Chưa thể ghi nhận giới thiệu lúc này. Vui lòng thử lại sau." |

Mọi lỗi có thân `{code, message, fieldErrors}` và `Cache-Control: no-store`; không có SQL, tên lớp, giá trị đã gửi hay tên tệp. Giới thiệu không phụ thuộc `app.application-intake.enabled` (cờ đó chỉ bảo vệ route nộp ẩn danh); chỉ cần khóa mã hóa email xác nhận, vì ứng viên phải nhận được mã tra cứu.

### Giới hạn lượt gửi và nhật ký (V37)

Mỗi lần giới thiệu đã qua bước kiểm form và CV là **một lượt**, được ghi và commit trong transaction riêng (`ReferralAttemptQuota`) **trước** khi ghi giới thiệu, dù sau đó trả 201, 404, 409 hay bị từ chối sau khi khóa tài khoản (401, 403, 400 tự giới thiệu). Một người giới thiệu có tối đa `app.referral.max-per-day` lượt (mặc định 20, từ 1 đến 100) trong 24 giờ trượt; lượt kế tiếp trả 429 kèm `Retry-After` (số giây tới khi lượt làm số lượt còn trong cửa sổ xuống dưới giới hạn rời khỏi cửa sổ, làm tròn lên, ít nhất 1) và **không** được ghi. Lỗi form, lỗi CV, 401/403 của matcher và các lỗi 413/415/503 trước đó không tính lượt.

Vì sao tính cả 404 và 409: 409 cho người giới thiệu biết email này đã nộp vào yêu cầu đó. Nếu chỉ đếm lần lưu thành công, ai cũng dò được miễn phí và không để lại dấu vết ai đã ứng tuyển ở đâu; mỗi lần thành công còn gửi một email tới địa chỉ do người giới thiệu nhập, nên giới hạn này cũng ngăn dùng API để gửi thư hàng loạt.

Sau khi giới thiệu trả lời, `outcome` của lượt được ghi (cố gắng hết mức, trong transaction riêng; ghi lỗi thì chỉ log một câu cố định và không đổi câu trả lời): `STORED` (201), `POSTING_UNAVAILABLE` (404), `DUPLICATE` (409), `REFUSED` (400, 401, 403 sau khi khóa tài khoản), `FAILED` (503). Bảng `referral_attempts` vì vậy là nhật ký ai đã thử tin nào và kết quả ra sao; không có dữ liệu ứng viên. Chưa có hạn lưu và API đọc nhật ký (câu hỏi 27, ý 8).

```sql
CREATE TABLE referral_quotas (
    referrer_id UUID PRIMARY KEY REFERENCES user_accounts(id) ON DELETE RESTRICT,
    last_attempt_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE referral_attempts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    referrer_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    job_posting_id UUID NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(20),
    CONSTRAINT referral_attempts_outcome CHECK (outcome IS NULL OR outcome IN
        ('STORED','POSTING_UNAVAILABLE','DUPLICATE','REFUSED','FAILED'))
);
CREATE INDEX referral_attempts_referrer_idx ON referral_attempts(referrer_id, attempted_at);
```

Một dòng `referral_quotas` cho mỗi người giới thiệu, khóa `FOR UPDATE` trong transaction của hạn mức, nên các lần gửi đồng thời của cùng một người được đếm lần lượt (cách của V27 theo IP). `job_posting_id` không có khóa ngoại: tin không tồn tại cũng là một lượt (404). Thời điểm là đồng hồ database (`clock_timestamp()`), đọc sau khi khóa.

### Khóa và transaction

Phần chậm (đọc tối đa 11 MiB, kiểm CV) chạy trước mọi khóa. Hạn mức khóa riêng dòng `referral_quotas` của người gọi và commit trước, nên không bao giờ nằm trong thứ tự khóa của lần ghi, và một lần giới thiệu dài của cùng người (đang giữ dòng tài khoản) không chặn lần kiểm hạn mức sau. Lần ghi là một transaction `REQUIRES_NEW`, READ COMMITTED, tối đa 5 giây, `lock_timeout` 2 giây, `statement_timeout` 3 giây (`ApplicationReferralWriter`):

1. tài khoản người gọi rồi phiên, `FOR NO KEY UPDATE` (như ghi ảnh đại diện); kiểm lại hạn token, phiên và `SELF_PROFILE_WRITE`: một lần khóa tài khoản, đăng xuất hay đổi vai trò chờ lần ghi này, và lần ghi chờ chúng thì thấy kết quả của chúng (401, 403);
2. chặn tự giới thiệu (email tài khoản chuẩn hóa như email ứng viên);
3. yêu cầu tuyển dụng của tin (không khóa; tin không bao giờ đổi yêu cầu), không có thì 404;
4. **yêu cầu tuyển dụng `FOR NO KEY UPDATE`** (quy tắc của task 321 cho mọi thao tác làm một hồ sơ thành đang xử lý, không bao giờ `FOR SHARE`: nếu hai lần giới thiệu cùng lấy được khóa chia sẻ, chẳng hạn bên cạnh khóa `FOR SHARE` của một lần nộp công khai đang ghi, mỗi lần sẽ chờ khóa của lần kia ở bước `FOR NO KEY UPDATE` của `ReferralApplicationStorage`, tức deadlock, và PostgreSQL hủy một lần, lần đó nhận 503), rồi kiểm lại người gọi;
5. mã tra cứu mới (32 byte ngẫu nhiên, Base64URL, lưu SHA-256), như nộp công khai;
6. `ReferralApplicationStorage.store` (task 350) trong cùng transaction: khóa yêu cầu của nó lúc này không tốn gì, `store()` khóa tin `FOR SHARE`, kiểm tin còn nhận hồ sơ (404), chạy quy tắc trùng (409), liên kết hồ sơ ứng viên, kiểm lại hạn nộp sau lần chờ có thể có ở bước liên kết (404, task 360), ghi CV, rồi dòng giới thiệu (khóa ngoại lấy `FOR KEY SHARE` trên tài khoản mà lần ghi đang giữ);
7. kiểm lại người gọi (`store()` có thể đã chờ khóa tin);
8. xếp email xác nhận cho ứng viên đúng như nộp công khai (payload mã hóa, ghi chung transaction); commit; các byte của mã được xóa trong `finally`.

Không dùng `PostgresApplicationReceiptWriter.accept`: hàm đó bắt buộc transaction mới, nên khóa `FOR SHARE` của `store()` bên trong sẽ chờ chính khóa yêu cầu của transaction ngoài tới hết `lock_timeout`. Thứ tự khóa giống mọi thao tác ghi của nhân viên (tài khoản → phiên → yêu cầu → tin), nên một lần đóng yêu cầu và một lần giới thiệu chờ nhau theo cả hai chiều mà không tạo vòng: đóng yêu cầu đến sau thì chờ lần giới thiệu, rồi đếm cả hồ sơ mới (409 `REQUISITION_HAS_ACTIVE_CANDIDATES` nếu chưa xác nhận).

### Xác nhận đồng ý

Người giới thiệu gửi dữ liệu cá nhân của người khác, nên phải gửi `candidateConsent=true` (xác nhận ứng viên đã đồng ý cho gửi hồ sơ); thiếu hoặc khác `true` thì 400 và không tính lượt. Thời điểm xác nhận được lưu ở `consent_declared_at` (bằng lúc nhận hồ sơ, task 350). Đây là lời khai của người giới thiệu, không phải bằng chứng ứng viên đồng ý (câu hỏi 27, ý 5).

### Email cho ứng viên

Ứng viên nhận email xác nhận như khi tự nộp (mã tra cứu, liên kết tra cứu, liên kết rút hồ sơ), do worker của task 329 gửi sau khi commit. Ngay sau dòng chào, thư có thêm đoạn:

> Hồ sơ này do một nhân viên của công ty gửi thay bạn qua kênh giới thiệu nội bộ, kèm xác nhận rằng bạn đã đồng ý. Nếu bạn không muốn tiếp tục, bạn có thể rút hồ sơ bằng liên kết rút hồ sơ bên dưới. Để yêu cầu xóa dữ liệu của bạn, vui lòng trả lời email này hoặc liên hệ {MAIL_FROM}.

Đoạn này được dựng **lúc gửi** (`ApplicationReferralLookup` hỏi bảng `application_referrals`), nên payload, mã hóa và V28 không đổi. Thư không nêu tên người giới thiệu (câu hỏi 27, ý 6). Người giới thiệu không bao giờ thấy mã tra cứu: không có trong 201, trong danh sách của task 352 hay ở bất kỳ đâu khác. Xem [email xác nhận](application-confirmation.md#nội-dung-thư).

### Cấu hình

| Thuộc tính | Biến môi trường | Mặc định | Ý nghĩa |
|---|---|---|---|
| `app.referral.max-concurrent` | `REFERRAL_MAX_CONCURRENT` | 2 | số lần giới thiệu được đọc cùng lúc trên mỗi tiến trình backend (1..8) |
| `app.referral.max-per-day` | `REFERRAL_MAX_PER_DAY` | 20 | số lượt mỗi người giới thiệu trong 24 giờ (1..100) |

Ngoài ra cần `APPLICATION_CONFIRMATION_ENCRYPTION_KEY` (thiếu thì 503 `REFERRAL_UNAVAILABLE`), cùng `MAIL_*` và `APPLICATION_TRACKING_PAGE_URL` của email xác nhận. `APPLICATION_INTAKE_ENABLED=false` không chặn giới thiệu.

### Ví dụ PowerShell

```powershell
$token = "<access token>"
$form = @{
  jobPostingId     = "<id tin tuyển dụng>"
  fullName         = "Nguyễn Văn A"
  email            = "nguyen.van.a@example.com"
  phone            = "0912345678"
  referralNote     = "Bạn học cũ, mạnh Java"
  candidateConsent = "true"
  cv               = Get-Item ".\CV.pdf"
}
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/referrals" `
  -Headers @{ Authorization = "Bearer $token" } -Form $form
```

`-Form` cần PowerShell 7 trở lên.

### Ghi chú cho frontend (task 348)

- Gửi đúng bảy phần `jobPostingId`, `fullName`, `email`, `phone`, `referralNote`, `candidateConsent=true` và tệp `cv` (không phải `cvFile`); không gửi `requisitionId`, `candidate*`, `source`, `referrer*` (server trả 400).
- Form hiện chưa có ô xác nhận đồng ý: thêm một ô đánh dấu bắt buộc, mặc định **không** đánh dấu, đặt gần nút gửi, ví dụ "Tôi xác nhận ứng viên đã đồng ý cho tôi gửi hồ sơ này". Chỉ gửi `candidateConsent=true` khi người giới thiệu đã tự đánh dấu; không bao giờ gán cứng hay đặt sẵn giá trị này (server không phân biệt được, và `consent_declared_at` cùng câu hỏi 27, ý 5 sẽ mất ý nghĩa). Bỏ đánh dấu sau khi gửi thành công và khi đặt lại form; hiện `fieldErrors.candidateConsent` ngay cạnh ô này.
- Không còn "thành công giả" khi gọi API lỗi (`referralService.ts`, đoạn bắt lỗi rồi tạo bản ghi cục bộ): xử lý 400 (hiện `fieldErrors`), 401, 403, 404, 409, 413, 415, 429 (đọc `Retry-After`), 503 (`REFERRAL_BUSY` có `Retry-After: 1`) và hiện `message` của server.
- 201 là chính dòng mới của danh sách: `{id, candidateName, jobTitle, submittedAt, status, statusName, referralNote}`.
- Việc kiểm trùng phía client theo `candidateEmail` không bao giờ khớp (danh sách không có email): dựa vào 409 của server.
- Tải lên tới 11 MiB có thể vượt timeout 15 giây của axios (`axiosClient.ts`): tăng timeout cho lời gọi này.
- Ghi chú: server không đòi tối thiểu 10 ký tự như form hiện tại (câu hỏi 27, ý 1).

## Theo dõi giới thiệu (task 352)

`GET /api/v1/referrals/my-referrals`, `Authorization: Bearer <access token>`. Người giới thiệu xem lại các hồ sơ **mình** đã giới thiệu và trạng thái của chúng ở mức tổng quát.

### Quyền

- `SELF_PROFILE_READ` ở matcher (`SecurityConfiguration`), như xem ảnh đại diện của đồng nghiệp; cả sáu vai trò nội bộ đều có (V3, V5). Không thêm mã quyền.
- `ApplicationReferralStatusService` kiểm lại trước khi đọc dòng nào: token còn hạn (401 `SESSION_INVALID`), phiên và tài khoản còn hoạt động (401), mã `SELF_PROFILE_READ` đọc lại từ database (403), giống `AvatarService`.
- Danh sách lọc trong SQL theo `application_referrals.referrer_id` = người gọi (subject của JWT). Không đọc tham số nào trên query string: `search`, `status`, `page`, `limit` mà frontend đang gửi, hay một `referrerId`, đều bị bỏ qua, nên không ai xem được danh sách của người khác.
- Người không giới thiệu ai nhận `[]`, kể cả recruiter phụ trách yêu cầu hay HR Manager: quyền xem ứng viên không mở danh sách này. Ngược lại, là người giới thiệu không cho quyền xem hồ sơ ứng viên.

### Response 200

```json
[{"id":"…","candidateName":"Nguyễn Văn A","jobTitle":"Lập trình viên Java","submittedAt":"2026-10-15T03:00:00Z",
  "status":"INTERVIEWING","statusName":"Mời phỏng vấn","referralNote":"Bạn học cũ, mạnh Java"}]
```

`Cache-Control: no-store`. Mảng trần (không bọc trong `items` hay `data`, như `referralService.ts` đang đọc), mới nhất trước (`received_at` giảm dần, cùng thời điểm thì theo `id` giảm dần), không phân trang. Mỗi phần tử đúng bảy khóa, giống hệt response 201 của [API giới thiệu](#response-201) (task 351):

| Khóa | Nguồn | Ghi chú |
|---|---|---|
| `id` | `job_applications.id` | id hồ sơ ứng tuyển; frontend dùng làm key (không có thì tự tạo `ref-${Date.now()}`, và các dòng tạo trong cùng một mili giây trùng key) |
| `candidateName` | `job_applications.full_name` | họ tên ứng viên đúng như người giới thiệu đã nhập |
| `jobTitle` | `job_applications.posting_title` | bản chụp tên tin lúc nộp; tin bị sửa, gỡ hay đóng sau đó không đổi giá trị này |
| `submittedAt` | `job_applications.received_at` | lúc nhận hồ sơ |
| `status`, `statusName` | `PublicApplicationStatus.of(stage)` (task 340) | `RECEIVED` "Đã nhận", `IN_REVIEW` "Đang xem xét", `INTERVIEWING` "Mời phỏng vấn", `COMPLETED` "Kết thúc" |
| `referralNote` | `application_referrals.note` | ghi chú của chính người giới thiệu (đã chuẩn hóa xuống dòng) |

**Không bao giờ** có: email, số điện thoại hay CV của ứng viên; giai đoạn nội bộ hay tên của nó (`SCREENING`, "Sàng lọc"…); nhãn nguồn; id yêu cầu tuyển dụng, tin hay hồ sơ ứng viên; recruiter, người phỏng vấn hay bất kỳ nhân viên nào (kể cả chính người giới thiệu); lý do rút hồ sơ; mã hay liên kết tra cứu. Hồ sơ ứng viên đã rút hiện "Kết thúc", như hồ sơ trúng tuyển hay không phù hợp (câu hỏi 24, ý 2). Sprint 4 chưa có API chuyển giai đoạn (S5-02), nên tới Sprint 5 hồ sơ hiện "Đã nhận", trừ hồ sơ ứng viên tự rút.

Câu truy vấn duy nhất (`ApplicationReferralStatusService`, một transaction chỉ đọc REPEATABLE READ cùng với phần kiểm người gọi):

```sql
SELECT a.id, a.full_name, a.posting_title, a.received_at, a.stage, r.note
FROM application_referrals r JOIN job_applications a ON a.id = r.application_id
WHERE r.referrer_id = :caller
ORDER BY a.received_at DESC, a.id DESC
```

Chỉ mục `application_referrals_referrer_idx` (V36) phục vụ điều kiện `referrer_id`. `stage` chỉ rời lớp này qua `PublicApplicationStatus.of`. Thêm một khóa vào danh sách là đổi hợp đồng: sửa `ApplicationReferralView`, test ghim (`ApplicationReferralStatusIntegrationTest`, `PublicResponseContract`) và mục này, rồi báo frontend.

### Lỗi

| Trường hợp | Trả lời |
|---|---|
| Không có token, token hỏng, phiên hoặc tài khoản không còn hoạt động | 401 `UNAUTHORIZED` (matcher) hoặc 401 `SESSION_INVALID` (service kiểm lại) |
| Không có `SELF_PROFILE_READ` | 403 `FORBIDDEN` |

`GET /api/v1/referrals` (danh sách mọi lần giới thiệu) không tồn tại: bị từ chối mặc định (403), kể cả với ADMIN.

### Ví dụ PowerShell

```powershell
$body = @{ email = "nhanvien@example.test"; password = "<mật khẩu>" } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/auth/login" -ContentType "application/json" -Body $body
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/v1/referrals/my-referrals" `
  -Headers @{ Authorization = "Bearer $($login.accessToken)" }
```

### Ghi chú cho frontend (task 349)

- Gọi `GET /api/v1/referrals/my-referrals` có `Authorization`; response là mảng trần, mới nhất trước. Server bỏ qua `search`, `status`, `page`, `limit`: lọc và phân trang (nếu cần) làm ở client.
- Hiển thị `status` và `statusName` của server; bốn mã đã khớp `ReferralStatus` (`types/referral.ts`), không cần tự ánh xạ bằng `mapToGeneralStatus`.
- Server không gửi email, số điện thoại, CV, phòng ban, địa điểm, loại hình làm việc, người giới thiệu: ẩn các ô đó thay vì hiện giá trị dự phòng của `sanitizeReferralData` ("Chưa phân phòng", "Hà Nội", "Toàn thời gian", "CV_UngVien.pdf", "Nhân sự nội bộ").
- Bước lọc bỏ nhận xét nội bộ, điểm đánh giá, người phỏng vấn ở client (`sanitizeReferralData`) không còn cần: server không bao giờ gửi các trường đó.
- Không dùng danh sách mẫu trong `localStorage` khi API lỗi: hiện lỗi (401 thì đăng nhập lại, 403 thì báo không có quyền).

## Kiểm thử (task 350)

- `ApplicationReferralStorageIntegrationTest` (PostgreSQL thật, không HTTP): một lần giới thiệu ghi hồ sơ (`NEW`, `source_id` trống, bản chụp tên tin), CV, liên kết hồ sơ ứng viên và dòng giới thiệu với ghi chú đã chuẩn hóa xuống dòng và `consent_declared_at` = `received_at` cắt tới micro giây; ghi chú đúng 2.000 ký tự được giữ, ghi chú 2.001 ký tự thô còn 2.000 sau khi `\r\n` thành `\n` cũng vậy; trùng theo cả hai chiều (tự nộp rồi giới thiệu, giới thiệu rồi tự nộp) trả 409 và không có dòng giới thiệu thừa; yêu cầu tạm dừng, tin đã đóng, quá hạn nộp, tin không tồn tại trả 404 và không lưu gì; ghi chú trống, chỉ có ký tự vô hình, 2.001 ký tự, có thẻ HTML, NUL hay ký tự đảo chiều trả 400 `referralNote` trước khi tới 404 của tin không tồn tại; chưa xác nhận đồng ý, thiếu người giới thiệu, thiếu bản khai, thiếu tin, có `sourceId` → `IllegalArgumentException`; không transaction → `IllegalTransactionStateException`; REPEATABLE READ, SERIALIZABLE, chỉ đọc → `IllegalStateException`; rollback của bên gọi và người giới thiệu không tồn tại (khóa ngoại) không để lại gì; schema: một dòng mỗi hồ sơ, CHECK ghi chú, độ dài, `NOT NULL`, khóa ngoại, RESTRICT tài khoản, CASCADE theo hồ sơ, chỉ mục; khóa: một kết nối giữ dòng tin, lần giới thiệu chờ trong `store()` (`pg_blocking_pids`), và `FOR SHARE NOWAIT` trên yêu cầu từ kết nối thứ ba bị từ chối (55P03), chứng tỏ yêu cầu bị khóa `FOR NO KEY UPDATE` chứ không chỉ `FOR SHARE`.
- `ApplicationSourceTest`: khối `source` của lần giới thiệu có đúng `type`, `label`, `referrerId`, `referrerName`, `note`; của trang tuyển dụng giữ ba khóa của task 361; dòng giới thiệu quyết định nguồn; `toString` ẩn họ tên và ghi chú.
- `CandidateProfileIntegrationTest`: HR_MANAGER, ADMIN và recruiter chính của yêu cầu thấy lần được giới thiệu với `INTERNAL_REFERRAL`, người giới thiệu (id, họ tên) và ghi chú, không có email người giới thiệu; recruiter hỗ trợ của yêu cầu khác không thấy gì của lần đó; người giới thiệu nhận 403 khi mở hồ sơ ứng viên.

## Kiểm thử (task 351)

- `ApplicationReferralContractTest` (không HTTP, bộ giải mã dựng như `ApplicationIntakeContractTest`): chỉ đúng `POST /api/v1/referrals` là route nộp; chỉ route này và route nộp công khai bỏ qua parser multipart của Servlet; form đúng bảy phần được nhận theo mọi thứ tự và chuẩn hóa; từng phần dư của frontend, phần lặp, phần thứ tám, CV thứ hai, tệp `cvFile`, thiếu CV, UTF-8 hỏng, thân chưa đóng, vượt giới hạn byte từng phần, `jobPostingId` không chuẩn hoặc thiếu, `candidateConsent` khác đúng `true`, ghi chú sai quy tắc 350, tên/email/số điện thoại sai đều bị từ chối, mọi lỗi theo trường trả cùng lúc, không lặp lại giá trị đã gửi; ánh xạ lỗi sang `outcome`; đoạn thư của ứng viên được giới thiệu; `toString` ẩn dữ liệu cá nhân. `ApplicationIntakeContractTest` (39 ca của TRAN DUC LUONG) giữ nguyên.
- `ApplicationReferralIntegrationTest` (HTTP, PostgreSQL, SMTP cục bộ thật): một lần giới thiệu ghi hồ sơ (`NEW`, `source_id` trống, bản chụp tên tin, mã băm), CV, liên kết ứng viên, dòng giới thiệu (người gọi, ghi chú, thời điểm đồng ý), một email xác nhận đã mã hóa (bản mã không chứa email hay tên) và một lượt `STORED`, trả đúng bảy khóa, không có mã tra cứu; worker gửi cho ứng viên thư có mã (SHA-256 khớp mã băm), liên kết tra cứu, liên kết rút và đoạn giới thiệu với `MAIL_FROM`, không có tên hay email người giới thiệu; thư của hồ sơ tự nộp không có đoạn này; sáu vai trò đều giới thiệu được bằng PDF và DOCX; không vai trò 403, ẩn danh và `Bearer not-a-jwt` 401; CV đúng 10 MiB được nhận, thêm 1 byte hoặc thân trên 11 MiB trả 413 `APPLICATION_CV_TOO_LARGE`; JSON trả 415; DOCX có mẫu từ xa trả 400 `APPLICATION_CV_ACTIVE_CONTENT` và không tính lượt; trùng theo cả hai chiều trả 409 (`DUPLICATE`); yêu cầu tạm dừng, tin đã đóng, quá hạn, tin không tồn tại trả 404 (`POSTING_UNAVAILABLE`); tự giới thiệu 400 (`REFUSED`); thiếu đồng ý, ghi chú vô hình hoặc 2.001 ký tự, gửi `referrerId`, thiếu CV trả 400 và không tính lượt; hai người giới thiệu vào cùng một yêu cầu trong lúc một kết nối giữ yêu cầu `FOR SHARE` (như `store()` của một lần nộp công khai): lần đầu chờ kết nối đó, lần sau chờ theo (`pg_blocking_pids`), nhả ra thì cả hai 201 và hai lượt `STORED` (giới hạn mặc định hai lần cùng lúc; writer khóa `FOR SHARE` thì deadlock và một lần trả 503).
- `ApplicationReferralQuotaIntegrationTest` (giới hạn 3): 201, 409 và 404 đều tính, lượt thứ tư 429 với `Retry-After` và không lưu gì; lượt của 409 còn sau khi lần ghi rollback; lỗi form và CV không tính; lượt cũ hơn 24 giờ không tính; năm lượt còn hiệu lực sau khi hạ giới hạn thì chờ lượt cũ thứ ba; mỗi người giới thiệu một hạn mức riêng; ba lần gửi đồng thời khi còn hai lượt: đúng hai được nhận; CHECK `outcome` và RESTRICT tài khoản của V37.
- `ApplicationReferralLockIntegrationTest` (không ngủ chờ, `pg_blocking_pids` và `NOWAIT`): lần giới thiệu chờ yêu cầu bị giữ rồi thấy yêu cầu đã `FILLED` (404); một kết nối giữ dòng tin, lần giới thiệu giữ yêu cầu `FOR NO KEY UPDATE` (`FOR SHARE NOWAIT` bị từ chối; lúc đó `ReferralApplicationStorage` cũng đã khóa yêu cầu, nên mức khóa riêng của writer được kiểm ở ca hai người giới thiệu của `ApplicationReferralIntegrationTest`) và chờ trong `store()`, lần đóng yêu cầu xếp hàng sau nó, rồi giới thiệu trả 201 và đóng yêu cầu trả 409 `REQUISITION_HAS_ACTIVE_CANDIDATES`; khóa tài khoản trong lúc chờ trả 401, gỡ `SELF_PROFILE_WRITE` trong lúc chờ trả 403 (đều `REFUSED`, không lưu gì); khi một lần đang ghi, lần thứ hai trả 503 `REFERRAL_BUSY` với `Retry-After: 1` và không tính lượt.
- `ApplicationReferralUnavailableIntegrationTest`: không có khóa mã hóa thì form hợp lệ trả 503 `REFERRAL_UNAVAILABLE` (dù cờ nộp công khai bật), JSON vẫn 415, thân trên 11 MiB vẫn 413, không ghi gì.
- `ApplicationConfirmationIntegrationTest` của TRAN DUC LUONG: worker dựng lại tay nhận thêm `ApplicationReferralLookup`.
- `ApiAuthorizationMatrixIntegrationTest`: dòng `POST /api/v1/referrals` (`SELF_PROFILE_WRITE`, mẫu JSON dừng ở 415), 1596 ca.

## Kiểm thử (task 352)

- `ApplicationReferralStatusIntegrationTest` (HTTP, PostgreSQL; lần giới thiệu được lưu qua `ReferralApplicationStorage` thật ở các thời điểm chọn trước, một lần qua API của task 351): hai người giới thiệu mỗi người chỉ thấy lần giới thiệu của mình, mới nhất trước, hai lần cùng thời điểm theo `id` giảm dần; query string (kể cả `referrerId` của người khác) không đổi kết quả; bảy giai đoạn (đặt bằng SQL) hiện đúng trạng thái công khai và tên của nó, không giá trị nào là mã hay tên giai đoạn nội bộ; hồ sơ ứng viên tự rút (API rút hồ sơ, có lý do) hiện `COMPLETED` "Kết thúc", không có lý do; record `ApplicationReferralView` có đúng bảy thành phần và chỉ `id`, `candidateName`, `referralNote` mang tên giống dữ liệu nội bộ (`PublicResponseContract`), JSON đúng bảy khóa, thân không chứa email, số điện thoại, tên tệp CV, giai đoạn, nhãn nguồn, id yêu cầu, tin, hồ sơ ứng viên, tên recruiter, HR, người giới thiệu, ứng viên khác của cùng yêu cầu hay mã tra cứu; response 201 của task 351 bằng đúng phần tử của danh sách sau đó; recruiter chính của yêu cầu, HR Manager và người giới thiệu khác nhận `[]`; không vai trò 403, không token, `Bearer not-a-jwt` và tài khoản bị khóa sau khi có token 401, đều `no-store`; gọi service trực tiếp: tài khoản không vai trò, mã `SELF_PROFILE_READ` bị gỡ khỏi vai trò (403), tài khoản bị vô hiệu hóa, bị quản trị viên khóa hay phiên bị thu hồi sau khi token đã được giải mã (401, khôi phục thì đọc lại được) và token hết hạn theo đồng hồ (401) đều bị từ chối trước khi đọc.
- `ApiAuthorizationMatrixIntegrationTest`: dòng `GET /api/v1/referrals/my-referrals` (`SELF_PROFILE_READ`, 200 với mảng rỗng) và `GET /api/v1/referrals` trong danh sách bị từ chối mặc định; 1611 ca.

Câu hỏi cho BA/PO: [ma trận vai trò và quyền](../architecture/role-permission-matrix.md), câu hỏi 27, ý (1)–(11).
