# PostgreSQL 16 cho repo Backend

Môi trường thống nhất của nhóm: hai repo Frontend và Backend riêng; container PostgreSQL 16 tên `recruitment-postgres`, database/user `recruitment`, cổng host `5432`. Thực hiện các lệnh dưới đây từ thư mục gốc repo Backend, nơi có `pom.xml` và `.env.example`.

## 1. Chuẩn bị

Cài và mở [Docker Desktop cho Windows](https://docs.docker.com/desktop/setup/install/windows-install/). Kiểm tra Docker Engine đã chạy:

```powershell
docker version
```

Cần cả phần Client và Server phản hồi. Backend dùng JDK 21 trở lên; không cần cài Maven riêng.

Tạo `.env` nếu chưa có, giữ nguyên cấu hình đang sử dụng:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Đặt các giá trị kết nối:

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/recruitment
DB_USERNAME=recruitment
DB_PASSWORD=<mat-khau-container-local-nhom-da-gui>
```

Thay giá trị trong dấu `<...>` bằng mật khẩu local do nhóm cung cấp. `POSTGRES_USER`/`POSTGRES_PASSWORD` là biến khởi tạo container; ứng dụng Java đọc `DB_USERNAME`/`DB_PASSWORD`. Hai bộ giá trị phải khớp. Điền thêm `AUTH_JWT_SECRET` và thông tin bootstrap admin theo `.env.example`; tài khoản database và tài khoản đăng nhập ứng dụng là hai tài khoản khác nhau. `.env` thật được Git bỏ qua.

## 2. Chọn một cách tạo container

**Nếu đã chạy lệnh `docker run` của nhóm**, giữ container đó. Khi cần bật lại:

```powershell
docker start recruitment-postgres
docker exec recruitment-postgres pg_isready -U recruitment -d recruitment
```

Không chạy Compose để tạo thêm container cùng tên/cổng. Dấu `\` cuối dòng trong thông báo nhóm là cú pháp shell Linux/macOS; trên PowerShell hãy ghép lệnh `docker run` thành một dòng, hoặc dùng Compose dưới đây.

**Nếu chưa có container**, dùng Compose với các giá trị đã điền trong `.env`:

```powershell
docker compose --env-file .env -f devops/docker/compose.yaml up -d postgres
docker compose --env-file .env -f devops/docker/compose.yaml ps
docker exec recruitment-postgres psql -U recruitment -d recruitment -c "SELECT version(), current_database(), current_user;"
```

Kết quả cần có PostgreSQL 16, database và user `recruitment`. Compose chỉ bind database lên loopback và giữ dữ liệu trong volume `postgres16_data` của project Compose. Nếu cổng 5432 đang bị dịch vụ khác sử dụng, xử lý đúng dịch vụ đó trước; không tự xóa container/database đang có.

Nếu trước đây dùng Compose PostgreSQL 17, volume 17 cũ được giữ nguyên. Bản này dùng volume 16 mới để tránh mở dữ liệu 17 bằng PostgreSQL 16. Không trỏ volume cũ vào image 16 hoặc dùng `down -v` để xử lý lỗi. Nếu cần giữ dữ liệu 17, phải chuyển dữ liệu có kiểm tra tương thích riêng; việc đổi version trong file Compose không chuyển đổi dữ liệu.

Theo [tài liệu image PostgreSQL chính thức](https://hub.docker.com/_/postgres), các biến khởi tạo chỉ có hiệu lực khi thư mục dữ liệu còn trống. Sửa mật khẩu trong `.env` không đổi mật khẩu của database đã khởi tạo.

## 3. Chạy Backend và Frontend

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Duser.timezone=UTC'
```

Flyway đọc `database/migrations/` đã được Maven đóng gói, tự tạo/nâng schema. Giữ nguyên V1–V6 và các migration đã chạy; không sửa SQL lịch sử chỉ để đổi repo hoặc đổi cấu hình kết nối. Kiểm tra `http://localhost:8080/api/health` hoặc `http://localhost:8080/api/v1/health`.

Frontend chạy trong repo Frontend, đặt `VITE_API_BASE_URL=http://localhost:8080/api/v1`. Backend đặt `CORS_ALLOWED_ORIGINS` đúng origin Frontend, ví dụ `http://localhost:5173`; URL reset/activation email cũng trỏ đến Frontend.

Để thử email, chạy Mailpit riêng khi các cổng 1025/8025 còn trống:

```powershell
docker compose -f devops/docker/compose.mail.yaml up -d
```

## 4. Kiểm thử và dừng

```powershell
.\mvnw.cmd clean verify
```

Bộ test dùng PostgreSQL 16 tạm qua [embedded-postgres](https://github.com/zonkyio/embedded-postgres), không đọc database `.env` và không yêu cầu Docker. Version binary được pin trong BOM ở `pom.xml`; major version khớp container nhóm. Lần đầu Maven cần tải dependency.

Dừng container tạo bởi Compose, giữ dữ liệu:

```powershell
docker compose --env-file .env -f devops/docker/compose.yaml down
```

Nếu container được tạo bằng `docker run`, dùng `docker stop recruitment-postgres`. Khi bật lại, dùng đúng cách đã tạo container ban đầu. Không dùng `docker rm` hoặc xóa volume để dừng hằng ngày.
