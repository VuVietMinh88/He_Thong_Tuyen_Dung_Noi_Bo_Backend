# Worker gợi ý CV độc lập

Worker là Java CLI riêng, không phải Spring bean. Nó không mở HTTP, chạy Flyway, đọc `.env`,
khởi động mail/scheduler hoặc tự tạo job. Web chỉ ghi/đọc DB, không được mount Docker socket.
Worker chạy trên host có Java 21, Python 3, Docker CLI/daemon và script run.py đã deploy.
Không đưa credential DB vào image trích PDF.
Worker đặt timezone JVM riêng thành UTC trước khi mở JDBC để tránh alias múi giờ của OS không có
trong PostgreSQL. Không thay múi giờ nghiệp vụ Việt Nam của web.

## Chuẩn bị

1. Deploy backend/migration V29; giữ `CV_EXTRACTION_ENABLED=false` trong lúc chuẩn bị.
2. Build JAR và image theo README. Ghi lại ID/digest bất biến của image; worker không nhận tag mutable.
3. Tạo DB user riêng cho worker (mật khẩu cấp ngoài Git), chỉ quyền CONNECT DB/USAGE schema,
   SELECT/UPDATE trên cv_extraction_jobs và cv_extraction_attempts, SELECT trên application_cvs.
   Worker không cần quyền sửa job_applications, tạo bảng, xóa CV hoặc gửi mail.
4. Tạo thư mục tạm riêng với quyền 0700 trên Linux hoặc ACL chỉ tài khoản worker trên Windows.
   Thư mục nên ở ổ mã hóa hoặc tmpfs có quota. Mỗi lần chạy tạo thư mục random và file cv.pdf;
   adapter dọn file khi thành công/lỗi/timeout, lúc khởi động/poll dọn file sót cũ hơn 10 phút.
5. Cấp biến môi trường cho service worker từ secret store/service manager; không đặt mật khẩu trong command line:

| Biến | Nội dung |
|---|---|
| CV_WORKER_DB_URL | JDBC PostgreSQL URL, TLS phù hợp môi trường; chỉ DB worker được phép truy cập |
| CV_WORKER_DB_USER / CV_WORKER_DB_PASSWORD | User worker riêng |
| CV_WORKER_PYTHON | Đường dẫn executable Python tin cậy |
| CV_WORKER_RUNNER | Đường dẫn tuyệt đối script devops/cv-extraction/run.py |
| CV_WORKER_IMAGE | sha256:64hex hoặc repository@sha256:64hex của image đã kiểm chứng |
| CV_WORKER_TEMP | Thư mục tạm riêng của worker |
| CV_WORKER_MAX_RUNNING | Mặc định 1, 1..8; giống nhau giữa mọi worker |
| CV_WORKER_ONCE | Tùy chọn true để xử lý tối đa một job rồi thoát, dùng smoke test |

Chạy với JAR và logback.xml được deploy (thay đường dẫn theo host):

```powershell
java -Xms32m -Xmx256m '-Dlogback.configurationFile=C:/deploy/cv/logback.xml' '-Dloader.main=vn.ttcs.recruitment.cvsuggestion.CvSuggestionWorkerCommand' -cp C:/deploy/backend.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Không chạy worker như web service. Dùng supervisor/systemd/Windows service có restart policy,
giới hạn tài nguyên và môi trường bí mật riêng. Không khởi chạy từ request HTTP.
Child Python chỉ nhận allowlist môi trường PATH, thư mục OS và cấu hình Docker; không nhận DB/JWT/SMTP.
Adapter đặt PYTHONIOENCODING=utf-8/PYTHONUTF8=1 cho hợp đồng JSON, kể cả Windows dùng code page cũ.
Container trích không có mạng, không có secret, non-root/read-only và giới hạn cứng như run.py.

## Vận hành

- Worker poll mỗi 2 giây khi rảnh, xử lý một job/lần mỗi process.
- Chỉ tăng số process/giới hạn đồng thời sau khi đo CPU/RAM; mỗi sandbox có thể dùng 768 MiB.
- Theo dõi queue depth, tuổi job, count FAILED/error_code và lease; không xuất result/raw CV vào log.
- Worker mất kết nối DB không thể ghi completion bằng lease đã hết hạn. Worker cũ không ghi đè attempt mới.
- Worker crash: container vẫn có deadline riêng; job chuyển FAILED khi lease hết hạn, không tự requeue.
  Recruiter được thử lại theo giới hạn. GET cũng đánh dấu hết hạn nếu worker đang dừng.
- Dữ liệu tạm của container nằm trong tmpfs; file mount host cần dọn bằng adapter/scavenger.
  Nếu host mất điện, cleanup chạy lại khi service trở lại; không xóa file lạ trong thư mục.
- Nâng phiên bản: deploy image + JAR tương ứng, giữ version bộ trích đồng bộ. Job/version cũ không được nhận
  bởi worker mới; API sẽ từ chối đọc kết quả cũ và cho yêu cầu phiên bản mới.
- Tắt CV_EXTRACTION_ENABLED ngăn tạo mới, vẫn đọc job đã có. Dừng service worker để ngừng nhận việc;
  không có cơ chế hủy job qua API ở phạm vi này.

Sau smoke test quyền, POST/GET và một PDF chữ/scan, mới bật flag tạo job bằng cấu hình triển khai.
Không đổi flag API nộp hồ sơ khi bật chức năng gợi ý.
