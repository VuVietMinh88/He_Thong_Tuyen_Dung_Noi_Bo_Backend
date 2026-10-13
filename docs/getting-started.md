# Chạy backend bằng terminal và IDE

JDK chạy và biên dịch Java; Maven tải thư viện và build; PostgreSQL lưu tài khoản. Bạn không cần cài Maven riêng vì dự án có Maven Wrapper. Đọc lần lượt các bước dưới đây trong lần chạy đầu.

## 1. Chọn Java

Dùng **JDK 21**. JDK 25 cũng build được mức mã nguồn Java 21 trong `pom.xml`. Java 8 không chạy được backend. [Yêu cầu chính thức của Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html) xác nhận Spring Boot 4.1.1 hỗ trợ Java 21.

Trong PowerShell, thay đường dẫn ví dụ bằng JDK trên máy bạn:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
# Chay cac lenh sau tu thu muc goc repository Backend
```

`JAVA_HOME` trỏ tới thư mục JDK, không phải `bin` hay file `java.exe`. Nếu vẫn hiện `1.8`, terminal đang dùng Java 8. Mở terminal mới sau khi sửa biến môi trường hệ thống. Trên macOS/Linux, chọn JDK 21 rồi vào thư mục gốc repo Backend; dùng `sh ./mvnw` thay cho `./mvnw.cmd`.

## 2. Chạy test trước

```powershell
.\mvnw.cmd clean verify
```

Lệnh biên dịch, chạy unit test và API với PostgreSQL tạm thời, tạo file JAR, kiểm tra coverage service tối thiểu 60% cho dòng và nhánh. Database test dùng cổng ngẫu nhiên và dừng sau test. Test tự tạo tài khoản và khóa riêng; không đọc `.env` hoặc dùng database của bạn.

Kết quả ở `target/surefire-reports/`; coverage mở bằng `target/site/jacoco/index.html`. Lần đầu cần Internet tải Maven, thư viện và PostgreSQL phục vụ test; sau đó có thể chạy offline với `-o` nếu cache đầy đủ.

## 3. Tạo cấu hình API

Từ thư mục gốc repo Backend, chỉ sao chép khi chưa có `.env`:

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
```

Mở `.env` và điền:

| Biến | Ý nghĩa |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/recruitment` nếu database tên `recruitment` |
| `DB_USERNAME`, `DB_PASSWORD` | Tài khoản PostgreSQL sở hữu database |
| `AUTH_JWT_SECRET` | Chuỗi Base64 của ít nhất 32 byte ngẫu nhiên để ký token |
| `BOOTSTRAP_ADMIN_ENABLED` | `true` trong lần đầu với database chưa có tài khoản |
| `BOOTSTRAP_ADMIN_EMAIL` | Email Admin đầu tiên |
| `BOOTSTRAP_ADMIN_PASSWORD` | Mật khẩu ít nhất 8 ký tự, có chữ/số, tối đa 72 byte UTF-8 |
| `CORS_ALLOWED_ORIGINS` | URL frontend được trình duyệt phép gọi API; mặc định localhost 5173/3000 |

Sinh khóa JWT và tự dán kết quả vào `AUTH_JWT_SECRET`:

```powershell
$jwtKeyBytes = New-Object byte[] 32
$jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRandom.GetBytes($jwtKeyBytes)
$jwtRandom.Dispose()
[Convert]::ToBase64String($jwtKeyBytes)
```

`.env` được đọc như Java properties: `TEN_BIEN=gia_tri`, không bọc giá trị trong dấu nháy. Nếu mật khẩu có dấu `\`, viết `\\` để giữ một dấu `\`. Có thể dùng biến môi trường thay file. `.gitignore` đã loại `.env` khỏi Git.

Tài khoản PostgreSQL để ứng dụng kết nối database; tài khoản Admin để con người đăng nhập API. Hai tài khoản này khác nhau.

## 4. Chuẩn bị PostgreSQL

Nhóm thống nhất PostgreSQL 16, database/user `recruitment`, container `recruitment-postgres`, cổng 5432. Xem [hướng dẫn Docker của nhóm](../devops/docker/README.md) để cài Docker Desktop và cấu hình `.env`. Nếu dùng PostgreSQL cài trực tiếp, chọn phiên bản 16 và tạo database rỗng `recruitment`, owner là `DB_USERNAME`.

Nếu đã tạo container bằng lệnh `docker run` của nhóm, dùng luôn container đó và đặt `DB_USERNAME`/`DB_PASSWORD` khớp với `POSTGRES_USER`/`POSTGRES_PASSWORD`; không tạo thêm container cùng tên hoặc cổng. Nếu chưa có container, từ thư mục gốc repo Backend sau khi điền `.env` và mở Docker:

```powershell
docker compose --env-file .env -f devops/docker/compose.yaml up -d postgres
```

Compose chạy PostgreSQL 16 ở localhost cổng 5432 và lưu dữ liệu vào volume `postgres16_data`. Volume PostgreSQL 17 cũ được giữ riêng; không gắn dữ liệu 17 vào image 16. Nếu cổng đã dùng, kiểm tra dịch vụ đang chiếm cổng trước. Username/password trong Compose chỉ tạo khi volume rỗng; sửa `.env` không đổi mật khẩu database đã tạo.

Dừng container, giữ dữ liệu:

```powershell
docker compose --env-file .env -f devops/docker/compose.yaml down
```

Flyway tự tạo bảng từ `database/migrations/` khi chạy lần đầu; không chạy SQL thủ công trước Flyway. Dùng database dành riêng cho dự án mới. Nhiệm vụ này không tự chạy migration trên database làm việc của bạn.

## 5. Chạy API

Luồng đặt lại mật khẩu và [tạo tài khoản/kích hoạt](api/accounts.md) dùng SMTP: xem [cấu hình mail](api/password-reset.md). Mặc định gửi tới mail catcher local127.0.0.1:1025. File `devops/docker/compose.mail.yaml` chạy riêng Mailpit, không khởi động/thay đổi PostgreSQL. Khi nâng cấp, Flyway bổ sung các migration còn thiếu tới V13 (reset token, quyền, activation token, phòng ban/hồ sơ, khóa hành chính, chức danh/dải lương, quyền xem dải lương, khung năng lực, ngân hàng câu hỏi phỏng vấn, yêu cầu tuyển dụng nháp); giữ `.env` hiện có và sao lưu DB trước khi nâng cấp. Lưu ý: V10–V12 chưa có trong nhánh này, nên chưa nâng cấp database làm việc hoặc database dùng chung bằng nhánh này cho tới khi V10–V12 được gộp, vì database đã chạy V13 trước đó sẽ không khởi động được sau khi gộp (xem phần thứ tự phiên bản trong [tài liệu database](database/README.md)).

[Khóa/mở khóa tài khoản](api/account-locking.md) dùng Bearer Admin và không cần SMTP. Sau khi mở khóa, người dùng phải đăng nhập lại vì phiên cũ đã thu hồi. Không hạ về backend cũ bỏ qua trạng thái khóaV6 khi còn tài khoản bị khóa; cần xử lý kế hoạch tương thích trước rollback.

[Tìm kiếm/sửa tài khoản](api/accounts.md) và [xem/sửa hồ sơ cá nhân](api/profile.md) dùng Bearer token từ đăng nhập, không cần SMTP. Các API này đọc dữ liệu người dùng hiện tại; V5 không tự tạo phòng ban mẫu. CORS đã cho phépPUT từ các origin được cấu hình.

Từ thư mục gốc repo Backend:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC'
```

Khi khởi động xong:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/health'
```

Sau khi Admin được tạo thành công, đặt `BOOTSTRAP_ADMIN_ENABLED=false`, xóa mật khẩu bootstrap trong `.env`. Bootstrap chỉ tạo Admin khi bảng tài khoản rỗng; chạy lại không đặt lại mật khẩu hoặc ghi đè tài khoản sẵn có.

API nghe trên máy local. Nhấn `Ctrl+C` để dừng. Có thể chạy JAR bằng `java -jar target/ttcs-backend-0.0.1-SNAPSHOT.jar`, vẫn từ thư mục gốc repo Backend để đọc đúng `.env`. Xem [cách gọi API](api/auth.md) để thử đăng nhập.

## 6. Chọn IDE

### IntelliJ IDEA

1. Mở thư mục gốc, import `pom.xml` dưới dạng Maven project.
2. Chọn Project SDK, Maven Runner JDK là JDK 21, Maven dùng Wrapper.
3. Chạy `RecruitmentApplication.main()` với **Working directory** là thư mục gốc repo Backend.
4. Terminal tích hợp chạy các lệnh giống hướng dẫn ở trên.

### VS Code

Cài Extension Pack for Java, mở repo Backend, chọn JDK21 và import pom.xml. Chạy RecruitmentApplication với working directory là thư mục gốc. Dùng Maven Wrapper ở terminal cho compile/test/verify; không phụ thuộc cấu hình IDE cá nhân.

### Eclipse và IDE khác

Import **Existing Maven Projects**, chọn `pom.xml`, compiler/JRE là JDK 21, working directory thư mục gốc repo Backend. Nếu IDE chưa xử lý Maven resources, chạy `mvnw compile` trước. Build bằng terminal thống nhất cho cả nhóm.

## Lỗi hay gặp

| Hiện tượng | Kiểm tra |
|---|---|
| Java 8 / `release version 21 not supported` | `JAVA_HOME`, `java -version`, `javac -version`, JDK của Maven |
| Không tải được thư viện | Internet, quyền đọc/ghi Maven cache của người chạy |
| PostgreSQL `Connection refused` | Database đang chạy, port, `DB_URL` |
| JWT secret không hợp lệ | Base64 của ít nhất 32 byte ngẫu nhiên |
| Không đọc `.env` | Working directory thư mục gốc repo Backend |
| Đúng password vẫn 401 | Có thể đang khóa 15 phút, bị Admin khóa, hoặc tài khoản chưa kích hoạt/bị vô hiệu hóa |
| Browser chặn CORS | Frontend URL, gồm scheme và port, phải khớp cấu hình |

Mở/debug trực tiếp trong từng IDE và chạy Docker cần xác nhận trên môi trường của bạn. Test HTTP + PostgreSQL kiểm chứng backend; không thay thế kiểm tra giao diện IDE.
