# Bộ trích thông tin CV PDF

Phạm vi: bộ trích độc lập và runtime kiểm thử/vận hành. Chưa có HTTP API, database job,
worker scheduler hoặc chức năng xác nhận/cập nhật hồ sơ. Không khởi động Spring, đọc `.env`,
gửi SMTP hay chạy migration. API nộp hồ sơ giữ nguyên.

## Chạy

Build backend bằng Java 21 trước, rồi build image từ **gốc repository**:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
docker build -f devops/cv-extraction/Dockerfile -t recruitment-cv-extractor:local .
python devops/cv-extraction/run.py C:\path\to\cv.pdf
```

Script nhận một file local, mount chỉ đọc vào đường dẫn cố định. stdout là một JSON có PII:
chỉ cung cấp cho người có quyền, không đưa stdout vào log chung. stderr của parser/OCR bị tắt.
Không dùng tên CV làm đường dẫn tạm, câu lệnh shell hoặc tên container.

Runtime dùng Java21/PDFBox3.0.8 + Tesseract5, ngôn ngữ `vie+eng`, một thread OCR.
Image nền và gói hệ điều hành phải được pin digest/version theo môi trường phát hành sau benchmark;
tag `local` này chưa phải artifact production đã nghiệm thu.

## Giới hạn

- File 1..10.485.760 byte; PDF không mã hóa, 1..1000 trang như nền kiểm tra CV.
- Chỉ xử lý 5 trang đầu; `FIRST_FIVE_PAGES_ONLY` khi còn trang chưa xử lý.
- Tổng văn bản 100.000 ký tự, dòng 1.000 ký tự, tối đa 10 lựa chọn/trường.
- PDFBox đọc theo trang; trang ít chữ hoặc có ảnh mà còn thiếu thông tin liên hệ sẽ được
  render grayscale 300DPI để OCR, kể cả phần body có nhiều chữ.
- Ảnh tối đa 12 triệu pixel, mỗi chiều tối đa 5.000 pixel; kiểm trước cấp phát.
- Tesseract tối đa 12 giây/trang. Container có deadline 90 giây + tối đa 2 giây cưỡng bức dừng.
- Runner tối đa 100 giây chờ cả Docker; 768MiB RAM, không swap, Java heap256MiB,
  CPU1, PID64, tmpfs128MiB, filesystem chỉ đọc, user10002, bỏ capabilities, không network.
- `timeout` xử lý toàn bộ process group; runner hủy container khi quá hạn/gián đoạn.
- Dọn file ảnh sau mỗi trang; container `--rm` loại tmpfs kể cả OOM hoặc bị kill.

Ngân sách chờ 100 giây chưa tính các lệnh dọn container có timeout riêng (10 giây/lệnh).

Đây là cấu hình khởi điểm. Không tuyên bố đủ cho mọi CV hoặc SLA production trước khi đo.
Không gọi trực tiếp engine trong JVM HTTP: giới hạn heap/thời gian cứng thuộc sandbox.
Ứng dụng web **không được mount Docker socket**. Adapter/orchestration, quota/hàng đợi và
lưu kết quả theo hash/phiên bản sẽ thuộc API gợi ý ở bước tiếp theo.

## Kết quả

Phiên bản bộ trích hiện tại: `pdf-contact-v2`. Runner chỉ nhận đúng phiên bản này;
phải build lại image sau khi cập nhật mã. Kết quả baseline v1 được giữ riêng để đối chiếu.

PDFBox và OCR giữ vị trí dòng chuẩn hóa theo trang. Tesseract xuất TSV giới hạn 4MiB;
parser kiểm tọa độ, số dòng (2.000), độ dài dòng/văn bản và sắp xếp theo vị trí trang.
TSV/raw OCR chỉ ở bộ nhớ trong sandbox, không đưa vào log hoặc kết quả API.
Tên nổi bật trong 30% đầu trang được nhận diện theo vị trí và chiều cao chữ,
kể cả OCR đọc sidebar trước; heading/chức danh không được coi là tên.
Hai tên ngang bằng vẫn cần xác nhận, không tăng cửa sổ xét tên ra toàn bộ body.

`outcome`: COMPLETE / PARTIAL / NO_DATA / UNSUPPORTED / FAILED.
Mỗi trường có danh sách value, normalizedValue, reason, page, source(TEXT/OCR), preferred.
`preferred` là lựa chọn được quy tắc xếp đầu duy nhất, không phải xác nhận của recruiter.
Các lựa chọn ngang nhau, chỉ có người tham chiếu hoặc phone quốc tế không được tự ưu tiên.
Email giữ dấu chấm và +tag; tên giữ dấu; điện thoại Việt Nam có bản chuẩn hóa +84.
Không gọi dịch vụ xác minh email/điện thoại. Không trả điểm xác suất chưa hiệu chuẩn.

PDF scan rỗng/không đọc được không ảnh hưởng khả năng nộp CV. DOCX vẫn nhận ở luồng nộp,
nhưng bộ trích trả UNSUPPORTED/PDF_REQUIRED. Lỗi sandbox có thể không tạo JSON bên trong;
runner chuyển thành mã lỗi an toàn. CV gốc không bị thay đổi.

Nguồn kỹ thuật:
- https://tesseract-ocr.github.io/tessdoc/InputFormats.html
- https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html
- https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html
- https://pdfbox.apache.org/3.0/faq.html

## Kiểm thử sandbox thật

Unit Java chạy bằng Java21 cùng các test dự án. Unit runner không cần Docker:

```powershell
python -m unittest discover -s devops/cv-extraction -p "test_*.py" -v
```

`CvExtractionFixtureGenerator` trong `target/test-classes` tạo14PDF giả lập với font
DejaVu có trong image. Để tái hiện, mount test-classes chỉ đọc vào `/tests` và một thư mục
output vào `/fixtures`, chạy PropertiesLauncher với `loader.path=/tests` và
`loader.main=vn.ttcs.recruitment.cvextraction.CvExtractionFixtureGenerator`, đối số `/fixtures`.
Các class generator/benchmark không có trong JAR ứng dụng.

```powershell
python devops/cv-extraction/verify.py <thu-muc-fixture>
python devops/cv-extraction/verify_sandbox.py <thu-muc-fixture> target/test-classes
```

Verifier thứhai mount binary giả lập vào container test để thử timeout, lỗi stderr và output
quá lớn; không sửa binary trong image. Chỉ chạy trên fixture giả lập. Báo cáo chỉ chứa mã ca,
kết quả và số đo tài nguyên, không chứa thông tin trích được.
