# Xác nhận và chỉnh sửa liên hệ của hồ sơ ứng tuyển

Phạm vi backend: bản liên hệ được recruiter xác nhận theo từng application, không phải danh tính ứng viên hợp nhất.
Bản nộp gốc, CV, email chuẩn hóa chống trùng và kết quả trích được giữ nguyên. Không tự đồng bộ sang hồ sơ khác.
Chỉ thao tác lưu rõ ràng mới thay bản xác nhận; OCR chạy lại không sửa liên hệ.

## Hợp đồng API

`GET /api/v1/applications/{id}/confirmed-contact` cần CANDIDATES_READ và phạm vi hiện tại như API gợi ý CV.
Response gồm applicationId, submitted, confirmed (null nếu chưa có), effective, revision,
currentCvHash và needsReview. effective dùng submitted trước xác nhận, sau đó dùng confirmed.contact.
Không có bản xác nhận là revision0. Các giá trị liên hệ là fullName/email/phone.

`PUT /api/v1/applications/{id}/confirmed-contact` cần cả CANDIDATES_READ và WRITE.
Nếu một quyền là SCOPED, hồ sơ phải thuộc requisition đang giao chính/hỗ trợ cho người gọi.
Quyền REQUISITIONS không thay thế CANDIDATES. Kiểm lại quyền và phân công sau khi chờ khóa.

```json
{
  "fullName": "Nguyễn Minh Anh",
  "email": "contact@example.test",
  "phone": "+84912345678",
  "expectedRevision": 0,
  "expectedCvHash": "<64 ký tự hex chữ thường từ GET>",
  "nameSource": "MANUAL",
  "emailSource": "MANUAL",
  "phoneSource": "MANUAL",
  "extractionId": null
}
```

Lưu đủ cả ba trường trong cùng transaction với lịch sử. Trả200 và view mới.
- Tên trim, bắt buộc, tối đa120ký tự, plain text một dòng.
- Email trim, bắt buộc, hợp lệ, tối đa254ký tự; không chấp nhận HTML/control characters.
- Phone chuẩn hóa separator như hợp đồng nộp hồ sơ, cho dấu+ và8..15chữ số, có thể là số quốc tế.
- expectedRevision phải bằng revision hiện tại; expectedCvHash phải bằng hash CV hiện tại.
- Lần xác nhận đầu dù giữ nguyên submitted vẫn tạo revision1 và history.
- Gửi đúng revision hiện tại với cùng giá trị, hash và provenance là no-op. Gửi lại revision cũ vẫn409.
- CV đổi sau xác nhận: needsReview=true, bản xác nhận vẫn có hiệu lực. Tải hash/revision mới để xác nhận lại.
- Không yêu cầu OCR thành công; có thể nhập tay khi không có job hoặc FAILED/NO_DATA/UNSUPPORTED.

Nguồn từng trường: SUBMITTED (giá trị tương ứng bản nộp), EXTRACTED (candidate của job), MANUAL (nhập/sửa tay).
Có bất kỳ trường EXTRACTED thì phải có extractionId; không có EXTRACTED thì extractionId phải null.
Server kiểm job thuộc đúng application/hash CV/phiên bản, đã SUCCEEDED, giá trị thuộc candidate đúng trường.
Phone có thể giữ số gốc của candidate (sau bỏ separator) hoặc số normalizedValue quốc tế.
Chọn candidate không preferred vẫn được nếu người dùng chủ động chọn và dữ liệu hợp lệ.
Sửa giá trị khác candidate phải chuyển nguồn sang MANUAL; client không được tự giả mạo provenance.

`GET /api/v1/applications/{id}/confirmed-contact/history?page=0&size=20`:
- Cần cùng quyền READ và scope của hồ sơ, không mở rộng sang lần ứng tuyển khác.
- items gồm revision, beforeContact, afterContact, actorId, actorName, confirmedAt; mới nhất trước.
- page>=0, size1..100, page*size<=1000000; response thêm page/size/totalElements.
- History không sửa/xóa trực tiếp. Lưu lần sau tạo revision mới. Xóa application sẽ cascade current/history.
- Đây không phải API timeline đầy đủ tương tác/email/phỏng vấn.

Response thành công/lỗi ứng dụng no-store. DTO toString che PII; không log body hoặc snapshot JSON.

| HTTP | Code | Hành vi |
|---|---|---|
| 400 | VALIDATION_ERROR | Thiếu/sai trường hoặc phân trang |
| 400 | CONTACT_SOURCE_INVALID | Giá trị không khớp nguồn đã chọn |
| 401/403 | Chuẩn auth | Chưa đăng nhập/hết phiên/thiếu quyền/ngoài scope |
| 404 | APPLICATION_CONTACT_NOT_FOUND | Hồ sơ hoặc CV không tồn tại |
| 409 | CONTACT_REVISION_CONFLICT | Có người đã lưu trước; tải lại |
| 409 | CV_CHANGED | Form dựa trên CV cũ |
| 409 | CV_EXTRACTION_STALE | Job nguồn khác hồ sơ/hash/version hoặc chưa hoàn tất |
| 503 | CONTACT_CONFIRMATION_UNAVAILABLE | Tắt PUT hoặc DB bận/lỗi; không lưu một phần |

## Email và chống trùng

Email liên hệ đã xác nhận dùng cho thư tuyển dụng tạo MỚI sau xác nhận. Không đổi khóa chống trùng
`job_applications.normalized_email`, không tự gộp ứng viên, không phát lại receipt.
Email xác nhận nộp hồ sơ và các thư đã queue/gửi giữ snapshot cũ, kể cả retry cùng thư.

`ApplicationContactResolver.snapshotForNewRecruitmentMail(applicationId)` là hợp đồng nội bộ cho consumer thư tương lai:
1. Consumer phải xác thực và kiểm quyền/phân công trước đó.
2. Phải có transaction READ_COMMITTED; resolver từ chối isolation khác để không lấy snapshot cũ.
   Resolver khóa requisition → application → CV như thao tác xác nhận.
3. Snapshot có contact, revision và capturedAt. Consumer lưu snapshot vào outbox trong CÙNG transaction.
4. SMTP chạy sau commit, không đọc lại email hiện tại lúc gửi/retry. Không giữ transaction khi gọi SMTP.

Thứ tự commit được tuần tự hóa qua khóa chung: xác nhận trước thì thư mới dùng email mới;
snapshot trước thì thư đó giữ email cũ. Email có sẵn trong receipt outbox vẫn nguyên trạng.
Hiện chưa có consumer thư phỏng vấn/offer/giai đoạn để nối resolver; không tự xây các module đó ở đây.
Các consumer mới phải dùng hợp đồng này khi triển khai. Không dùng resolver để tạo lại email xác nhận nộp hồ sơ.

## Cấu hình, schema và ranh giới

- Migration dùng chung V68_1 tạo application_confirmed_contacts và application_contact_history; không sửa migration hồ sơ ứng tuyển và V68.
- `CV_CONTACT_CONFIRMATION_ENABLED=false` mặc định: PUT503, GET/history vẫn đọc dữ liệu sẵn có.
  Flag độc lập CV_EXTRACTION_ENABLED và APPLICATION_INTAKE_ENABLED.
- Intake không gọi trích CV, confirmed-contact hoặc resolver. Worker/queue/contact table lỗi không làm hỏng nộp.
- Các API danh sách/hồ sơ hợp nhất của module khác chưa tự chuyển sang effective; cần tích hợp có chủ đích.
- Công việc xác nhận được gộp nhánh cùng335 theo yêu cầu người dùng; không đổi tên/tiêu chí subtask Jira nhóm.

## Kiểm chứng

ConfirmedContactIntegrationTest: HTTP, source verification, revision, audit rollback/immutability,
scope/handover, CV change, mail snapshot ordering và receipt ciphertext không đổi.
CvFailureIsolationIntegrationTest: 12 trạng thái lỗi/ngừng trích, queue/contact table không khả dụng,
DOCX, hết retry; intake vẫn201, lưu CV/outbox, từ chối duplicate và áp dụng quota.
Không coi test double lỗi OCR ở đây là benchmark OCR; timeout/process thật đã được kiểm ở lớp sandbox.
