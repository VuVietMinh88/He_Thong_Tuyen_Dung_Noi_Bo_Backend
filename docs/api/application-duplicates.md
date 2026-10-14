# 327 — Chặn hồ sơ trùng email trong cùng vị trí

Backend S4-03, [ATSNB-96](https://luoplayer.atlassian.net/browse/ATSNB-96).
Áp dụng cho [API nộp hồ sơ](public-applications.md) và lời gọi nội bộ tới storage.
API vẫn tắt mặc định cho tới khi có quota và receipt/outbox production của 328/329.

## Quy tắc nghiệp vụ

- Một email chuẩn hóa chỉ có một hồ sơ cho mỗi **requisition**.
- Email trim whitespace/space/format Unicode ở hai đầu, lowercase `Locale.ROOT`.
  Không bỏ dấu chấm hoặc phần `+tag`; giữ email sau trim trong snapshot gốc.
- Áp dụng ở mọi stage: NEW, SCREENING, INTERVIEW, OFFER, HIRED, REJECTED, WITHDRAWN.
  Thay CV, tên, phone hoặc đổi tiêu đề tin không giải phóng quyền nộp lại.
- Cùng chức danh/tên tin nhưng requisition khác được nộp bằng cùng email.
- V21 hiện giới hạn một tin mỗi requisition. Không mở rộng mô hình hoặc sửa migration
  để tạo nhiều tin; khóa chống trùng vẫn là requisition thay vì chức danh hoặc tên tin.

Tin/requisition/hạn và dữ liệu/CV phải hợp lệ trước khi kiểm trùng tại INSERT.
Tin không nhận hồ sơ trả 404; input sai trả lỗi validation. Không tra email trên tin bị ẩn
để công khai việc một người đã nộp hồ sơ hay chưa.

## Cách thực hiện

V26 đã có `UNIQUE (requisition_id, normalized_email)` tên
`job_applications_requisition_email_key`. Giữ nguyên schema và unique tracking hash.
`ApplicationRepository.insert` dùng:

```sql
INSERT INTO job_applications (...) VALUES (...)
ON CONFLICT ON CONSTRAINT job_applications_requisition_email_key DO NOTHING;
```

Một row được thêm thì storage tiếp tục ghi CV. Không row được thêm vì xung đột email
thì ném `DuplicateApplicationException` trước ghi CV. Không dùng SELECT kiểm trùng trước
INSERT, không cập nhật hồ sơ/CV đã có, không bắt mọi lỗi unique thành trùng email.
Ngữ nghĩa arbiter và row count theo [PostgreSQL INSERT](https://www.postgresql.org/docs/16/sql-insert.html).

Hai lượt cùng email/requisition được unique index phân xử. Lượt sau chờ transaction trước:
nếu trước commit thì sau trả trùng; nếu trước rollback thì sau có thể nộp thành công.
Khóa/trạng thái/hạn của storage vẫn được giữ và kiểm lại như nền 325.

Exception là RuntimeException/ApiException nên thoát transaction storage/writer sẽ rollback.
Writer cần để exception thoát; không bắt rồi tiếp tục commit outbox hoặc trả receipt.
Mã/hash mới được writer chuẩn bị không trở thành một hồ sơ/CV hay xác nhận thứ hai.
Nếu transaction thất bại vì CV/outbox, email không bị giữ bởi hồ sơ đã rollback.

## Kết quả API

HTTP **409**, `Cache-Control: no-store`:

```json
{
  "code": "APPLICATION_ALREADY_SUBMITTED",
  "message": "Email này đã nộp hồ sơ cho vị trí tuyển dụng này.",
  "fieldErrors": {
    "email": "Email này đã nộp hồ sơ cho vị trí tuyển dụng này."
  }
}
```

Dùng handler ApiException hiện có. Không trả email thực, ID hồ sơ cũ, timestamp, mã/link,
hash, tên constraint hoặc SQL. Hồ sơ gốc giữ id/hash/receivedAt/stage/contact/CV.
Xung đột hash của email khác và lỗi CHECK/FK/SQL khác vẫn là lỗi ghi dữ liệu, API trả
503 `APPLICATION_SUBMISSION_UNAVAILABLE`, không trả 409 email.

## Kiểm thử

`ApplicationStorageIntegrationTest`: duplicate domain, mỗi stage, khác requisition cùng
chức danh, hash collision, chờ transaction commit/rollback, CV rollback và nộp lại.
`ApplicationIntakeIntegrationTest`: HTTP409/fieldErrors/no-store/privacy, Unicode trim,
case normalization độc lập locale Thổ Nhĩ Kỳ, giữ dots/+tag, mỗi stage, cùng chức danh
khác requisition, đổi tên tin, giữ snapshot/CV, race201+409, hash collision503 và lỗi CV503
không giữ chỗ email. Providers vẫn chỉ là adapter test, không triển khai 328/329.

Docker opt-in và timezone như [storage](application-storage.md). Thử nghiệm dùng schema
riêng; không kiểm chứng độ bền sau crash khi PostgreSQL test chạy fsync off.
