# Triển khai TTCS trên VPS và tự động cập nhật backend

VPS: Ubuntu 24.04, 4 GB RAM, IP `64.235.43.161`. Frontend dùng
`https://internal-hire.com`, API dùng `https://api.internal-hire.com/api/v1`.
Frontend và Backend vẫn là hai repo riêng. Docker Compose chỉ ghép các bản build
để chạy trên cùng VPS; frontend gọi backend qua HTTP API.

## Lịch sử cài đặt

VPS đã được cài ngày 2026-10-09 từ một gói ZIP gồm bản build frontend (`frontend/`),
backend `incoming/initial.jar` và `SHA256SUMS`. **Các file này không nằm trong repo.**
JAR đầu tiên được build từ một nhánh tích hợp Sprint 2 chưa merge, nên database trên
VPS đã chạy 14 migration: V1–V6 (đã có trong `develop`) và V7, V7_1, V8–V13 (chưa có).
Dữ liệu và tài khoản trên máy local không tự xuất hiện trên VPS.

Thành phần:

- `frontend/`: bản build frontend (chỉ có trên VPS), không chứa mật khẩu hay source `.env`.
- `incoming/initial.jar`: JAR backend cho lần cài đầu (chỉ có trên VPS); Java 21 chạy trong container.
- `compose.yaml`: PostgreSQL, backend và Caddy. Chỉ Caddy mở cổng 80/443 ra ngoài.
- `configure.py`: tạo `.env` và thông tin quản trị ngay trên VPS.
- `deploy.sh` / `deploy.py`: kiểm tra, sao lưu, khởi động và khôi phục khi lỗi.
- `.env`, `LOGIN.txt`, `runtime/`, `backups/`: dữ liệu riêng trên VPS, không commit.

## 1. Cài lại trên máy mới (chỉ khi cần)

Chuẩn bị gói trên máy của bạn: chép thư mục `devops/vps`, đặt JAR backend vào
`incoming/initial.jar`, bản build frontend vào `frontend/`, rồi trong thư mục gói chạy
`find incoming frontend -type f -exec sha256sum {} + > SHA256SUMS` và nén thành ZIP.
Chạy trong **PowerShell trên Windows**, không phải cửa sổ `root@...`:

```powershell
scp <goi-cai-dat>.zip root@<IP-VPS>:/root/
```

Trong cửa sổ SSH của VPS, giải nén lần đầu:

```bash
apt-get update
apt-get install -y unzip python3
test ! -e /opt/ttcs && mkdir -p /opt/ttcs && unzip /root/<goi-cai-dat>.zip -d /opt/ttcs
```

Nếu `/opt/ttcs` đã tồn tại, dừng bước giải nén và kiểm tra nội dung trước;
không ghi đè `.env`, runtime hay backup của lần cài trước.

Sau đó chạy riêng lệnh sau để có thể nhập thông tin khi script hỏi:

```bash
cd /opt/ttcs
bash first-start.sh
```

Script hỏi email quản trị (mặc định `support@internal-hire.com`) và mật khẩu
hộp thư `support@internal-hire.com`. Mật khẩu nhập trực tiếp trên VPS, không
hiện trên màn hình và không gửi qua chat. Script tự tạo mật khẩu DB, JWT secret
và mật khẩu admin ngẫu nhiên. SMTP dùng ServerPoint với STARTTLS cổng 587.
Backend **không** kiểm tra SMTP lúc khởi động, để sự cố máy chủ mail không chặn việc
khởi động hay rollback. Sau khi cài, gửi thử email kích hoạt/đặt lại mật khẩu; nếu
không tới, sửa `MAIL_PASSWORD` trong `.env` rồi chạy lại backend.

Trước khi chạy JAR đầu tiên, script tạo một database thử có tên ngẫu nhiên, kiểm
tra dump → sửa dữ liệu/schema → restore → so sánh, rồi xóa database thử đó.
Phải có `RESTORE_CHECK_OK` mới tiếp tục. Toàn bộ dùng pg_dump/pg_restore 16 bên
trong container, tránh dùng nhầm công cụ PostgreSQL phiên bản khác trên Windows.

Đọc thông tin đăng nhập bằng `cat /opt/ttcs/LOGIN.txt` chỉ trên VPS.
Đăng nhập xong nên đổi mật khẩu admin. Bootstrap chỉ tạo admin khi database
chưa có tài khoản; thay BOOTSTRAP_ADMIN_PASSWORD không đổi mật khẩu tài khoản cũ.

## 2. DNS, HTTPS và kiểm tra

Các bản ghi A cho `internal-hire.com` và `api.internal-hire.com` trỏ về
`64.235.43.161`; `www` CNAME về tên miền chính. Giữ bản ghi email của ServerPoint.
Cho phép TCP 80/443 ở firewall nhà cung cấp; giữ đường SSH hiện tại.
Không mở PostgreSQL 5432 hoặc backend 8080 ra Internet.
Caddy tự cấp và gia hạn HTTPS khi DNS/cổng mạng đúng. Lần đầu có thể cần chờ cấp
chứng chỉ; nếu có bản ghi AAAA cũ trỏ máy khác, phải sửa DNS đó trước.

```bash
cd /opt/ttcs
docker compose ps
curl --fail https://api.internal-hire.com/api/v1/health
curl --fail -I https://internal-hire.com
```

Mở trang bằng trình duyệt: kiểm tra đăng nhập, F5 ở trang bên trong, quyền truy cập,
gửi email kích hoạt/đặt lại mật khẩu bằng một hộp thư do bạn kiểm soát.
Các lệnh health thành công chưa chứng minh mọi nghiệp vụ đã hoàn thiện.
Xem `docker compose logs --tail=80 backend` hoặc `web` trên VPS khi cần;
không gửi toàn bộ `.env`, JWT, LOGIN.txt hay backup vào chat.

## 3. Luồng backend GitHub Actions

Workflow nằm ở `.github/workflows/backend-cicd.yml`.

1. PR vào `develop`: kiểm tra script, các test rollback/email và Maven `clean verify`
   (toàn bộ test đang khai báo cùng cổng kiểm tra coverage). Không deploy ở PR.
   Đẩy commit mới vào PR thì lần build cũ của PR đó bị hủy.
2. Merge vào `develop` tạo sự kiện push: chạy lại các kiểm tra trên đúng commit.
3. Test/build lỗi: job deploy không chạy, JAR trên VPS giữ nguyên; job email báo lỗi.
4. Test/build đạt: tải đúng artifact JAR của job build, kiểm SHA256 trên VPS và deploy.
5. Thiếu `.env`/biến bắt buộc/JWT hoặc mật khẩu sai định dạng: dừng trước khi ngắt web.
   JAR mới thiếu hoặc sửa migration đã triển khai cũng bị chặn, tránh nhánh develop
   còn cũ vô tình thay thế bản Sprint 2 đã cài lần đầu.
6. Có khóa triển khai để hai lần deploy không ghi đè nhau (chờ tối đa 15 phút).
   Merge mới không hủy một deployment đang chạy. GitHub chỉ giữ bản chờ mới nhất nếu
   merge dồn dập. Nếu `develop` đã có commit mới hơn (kể cả khi bấm Re-run một lần chạy
   cũ), job deploy bỏ qua, không đưa bản cũ đè lên bản mới.
7. Thiếu dung lượng đĩa cho backup và rollback: dừng trước khi ngắt web.
   Lưu `.env` và JAR cũ, tạm dừng web/backend, dump database. Backup lỗi thì mở bản cũ lại.
8. Chạy JAR mới, chờ container healthy tối đa 240 giây và kiểm tra truy vấn DB.
9. Nếu backend lỗi: dừng backend, khôi phục **toàn bộ database recruitment** từ backup
   bằng `pg_restore --clean --create`, phục hồi `.env`/JAR cũ và kiểm tra bản cũ.
   Web chỉ mở lại khi bản cũ chạy được; CI vẫn báo FAILED để nhóm biết bản mới lỗi.
10. Nếu backend đạt: ghi nhận bản tốt mới rồi mở web. Nếu chỉ web khởi động lỗi sau
    thời điểm này, giữ backend/DB mới và báo FAILED để sửa web; không khôi phục DB cũ
    vì có thể làm mất các ghi mới được chấp nhận.

Mỗi lần deploy, website **tạm ngừng khoảng vài phút** để sao lưu DB và kiểm tra bản
mới; đổi lại nếu bản mới lỗi thì có thể khôi phục DB chính xác. Chỉ áp dụng khi
database này thuộc riêng TTCS và mọi lượt ghi đi qua backend này. Không cho dịch vụ khác/cron ghi chung database.
Hệ thống lưu ảnh/avatar trong DB nên chúng đi theo backup. Email đã gửi ra ngoài
không thể thu hồi bằng rollback DB. Migration không được có tác động ngoài DB.

Nếu restore hoặc JAR cũ cũng lỗi, script báo `ROLLBACK_FAILED` và giữ web tắt;
không báo giả rằng rollback đã xong. Mất điện/kill -9 cần xử lý thủ công.
Lần cài đầu chưa có bản cũ: lỗi thì khôi phục DB ban đầu và giữ web tắt.

## 4. Cấu hình quyền SSH và GitHub Secrets

Có hai vai trò: **người có root trên VPS** và **admin repo** (người duy nhất đặt được
secrets/variables). Không bao giờ gửi private key qua chat/email.

**Người có root trên VPS** tạo tài khoản triển khai riêng:

```bash
adduser --disabled-password --gecos "" ttcs-deploy
usermod -aG docker ttcs-deploy
chown -R ttcs-deploy:ttcs-deploy /opt/ttcs
install -d -m 700 -o ttcs-deploy -g ttcs-deploy /home/ttcs-deploy/.ssh
```

Nhóm `docker` tương đương quyền root. Ai có quyền write vào `develop` đều gián tiếp có
quyền root trên VPS, vì workflow trên `develop` dùng được khóa SSH này.
Sau lệnh `chown`, thao tác thủ công trong `/opt/ttcs` nên chạy bằng
`sudo -u ttcs-deploy ...`; **không chạy `deploy.sh` bằng root**, vì file `.env` và
`runtime/last-success.*` mới tạo sẽ thuộc root và lần deploy CI sau không đọc được.

**Admin repo** tạo khóa riêng cho GitHub Actions trên máy của mình (PowerShell, Enter
để trống passphrase vì workflow dùng khóa không tương tác), rồi chỉ gửi nội dung file `.pub`:

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\ttcs_actions" -C "ttcs-actions"
Get-Content "$env:USERPROFILE\.ssh\ttcs_actions.pub"
```

**Người có root** thêm public key đó với tiền tố `restrict` (chặn port/agent forwarding
và terminal tương tác, vẫn cho `ssh <lệnh>` và `scp`). Không dùng `from=` vì IP của
runner GitHub thay đổi, không dùng `command=` vì workflow cần cả `scp` và `ssh`:

```bash
printf 'restrict %s\n' '<noi-dung-dong-ttcs_actions.pub>' >> /home/ttcs-deploy/.ssh/authorized_keys
chown ttcs-deploy:ttcs-deploy /home/ttcs-deploy/.ssh/authorized_keys
chmod 600 /home/ttcs-deploy/.ssh/authorized_keys
# Dòng host key cho VPS_KNOWN_HOSTS (giá trị công khai, gửi cho admin repo được):
printf '64.235.43.161 %s\n' "$(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
```

Admin repo kiểm tra: `ssh -i "$env:USERPROFILE\.ssh\ttcs_actions" ttcs-deploy@64.235.43.161 "docker version"`.
Workflow chỉ kết nối tới máy có host key trùng với `VPS_KNOWN_HOSTS`; không dùng
`ssh-keyscan` để tự lấy khóa.

**Admin repo** cấu hình trong repo Backend:

1. **Settings → Environments → New environment** tên `production`. Không cần
   required reviewers. Ở *Deployment branches and tags* chọn *Selected branches and
   tags* và thêm đúng `develop`. Như vậy chỉ code đã vào `develop` mới đọc được
   secrets; workflow sửa trong PR hay nhánh khác thì không.
2. Thêm vào **Environment `production`**:

   | Loại | Tên | Giá trị |
   |---|---|---|
   | Secret | `VPS_SSH_KEY` | Nội dung private key `ttcs_actions`; chỉ dán vào GitHub |
   | Secret | `VPS_KNOWN_HOSTS` | Dòng host key do người có root gửi ở trên |
   | Secret | `CI_SMTP_PASSWORD` | Mật khẩu hộp thư `support@internal-hire.com` |
   | Variable | `VPS_HOST` | `64.235.43.161` |
   | Variable | `VPS_USER` | `ttcs-deploy` |
   | Variable | `VPS_SSH_PORT` | `22` |

3. Thêm vào **Settings → Secrets and variables → Actions → Variables** (cấp repo, vì
   điều kiện `if:` của job không đọc được biến của Environment):

   | Tên | Giá trị |
   |---|---|
   | `ALERT_TO` | Email nhận cảnh báo, phân cách bằng dấu phẩy. Để trống thì không gửi email |
   | `DEPLOY_ENABLED` | `true` để bật tự deploy sau khi merge vào `develop`; không có hoặc khác `true` thì chỉ build và test |

4. Nếu ba secret trên đã từng được tạo ở cấp repo, **xóa bản cấp repo** để PR và nhánh
   khác không đọc được.

Người nhận email cảnh báo đặt trong biến `ALERT_TO`, không ghi trong file workflow vì
repo công khai. Email gồm trạng thái và link lần chạy Actions, không kèm secrets/log ứng
dụng. Nếu SMTP/GitHub gặp sự cố thì email có thể không tới; trạng thái FAILED trong
GitHub Actions vẫn là nơi kiểm tra. `CI_SMTP_PASSWORD` đang là mật khẩu hộp thư thật mà
backend cũng dùng; nếu muốn tách quyền, tạo hộp thư riêng cho cảnh báo CI rồi đổi
`SMTP_USERNAME` trong workflow.

**Khi nào bật `DEPLOY_ENABLED`:** `deploy.py` so sánh từng byte của mọi migration
trong JAR đang chạy với JAR mới; thiếu hoặc khác một file thì từ chối ("Candidate
removes or changes a deployed migration"), báo FAILED và không đụng tới website.
Vì vậy chỉ đặt `DEPLOY_ENABLED=true` khi:

- Cả 14 file migration đang chạy trên VPS đã có trong `develop` với nội dung **giữ
  nguyên**. Không sửa V7–V13 khi review Sprint 2; sửa lỗi bằng migration mới V14 trở đi
  (sửa file cũ cũng làm Flyway báo lỗi checksum).
- PR #24 (PostgreSQL 16 trong test) đã merge, để test chạy cùng phiên bản DB với VPS.

Kiểm tra trước khi bật: so danh sách SHA256 hai bên, phải trùng hoàn toàn.

```bash
# Trên VPS:
python3 -c "import zipfile,hashlib;z=zipfile.ZipFile('/opt/ttcs/runtime/last-success.jar');[print(hashlib.sha256(z.read(n)).hexdigest(),n.rsplit('/',1)[1]) for n in sorted(z.namelist()) if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')]"
# Trong repo Backend (Git Bash):
git fetch origin && for f in $(git ls-tree --name-only origin/develop database/migrations/); do echo "$(git show "origin/develop:$f" | sha256sum | cut -d' ' -f1) ${f##*/}"; done
```

Trước khi bật, workflow vẫn build và chạy toàn bộ test cho mọi PR và mọi lần merge vào
`develop`.

Các script đang chạy trên VPS được cài từ ZIP. CI chỉ cập nhật JAR, không âm thầm
ghi đè `.env`, frontend hoặc deploy.py. Khi sửa `deploy.sh`, `deploy.py`, Compose,
Caddyfile hay `backend/` cần chép các file đó lên `/opt/ttcs` riêng sau khi review.
Frontend được cập nhật bằng lần build riêng; workflow backend không tự lấy frontend
từ repo khác.

## 5. Sao lưu và khôi phục thủ công

Mỗi lần deploy giữ backup ở `/opt/ttcs/backups/<thoi-gian>-<release>/`, gồm DB và
cấu hình (có secrets). Thư mục chỉ tài khoản vận hành đọc được. Chuyển bản sao ra
ngoài VPS bằng kênh bảo mật; backup cùng VPS không đủ cho trường hợp mất cả VPS.
Sau mỗi lần deploy thành công, `deploy.py` chỉ giữ 10 thư mục backup mới nhất
(`BACKUP_KEEP`). CI xóa JAR đã tải lên trong `incoming/` và log quá 30 ngày trong `logs/`.
Deploy bị từ chối trước khi ngắt web nếu đĩa không đủ chỗ cho backup và rollback.
Không bao giờ tự xóa data volume.

Khi `ROLLBACK_FAILED`, giữ web tắt, kiểm tra log và dung lượng trước. Chọn đúng
thư mục backup, xác nhận không có ứng dụng khác ghi DB. Lệnh khôi phục sau **xóa và
tạo lại database recruitment bằng dữ liệu snapshot**; chỉ dùng để khôi phục lần
deploy bị lỗi, không áp dụng cho một bản backup cũ khi website đã nhận dữ liệu mới.
Sau khi đã tạo `ttcs-deploy` (mục 4), chạy các lệnh trong `sudo -u ttcs-deploy bash`
để file mới không thuộc root:

```bash
cd /opt/ttcs
docker compose stop web backend
# Thay THU_MUC_BACKUP bằng đúng thư mục của lần deploy bị lỗi.
docker compose exec -T database sh -c 'pg_restore -U "$POSTGRES_USER" --dbname=postgres --clean --if-exists --create --exit-on-error --no-owner --no-privileges' < THU_MUC_BACKUP/database.dump
cp THU_MUC_BACKUP/previous.env .env
cp THU_MUC_BACKUP/previous.jar runtime/backend.jar
chmod 600 .env
chmod 644 runtime/backend.jar
docker compose up -d --no-build --force-recreate --wait --wait-timeout 240 backend
# Chỉ chạy khi bước trên thành công:
cp runtime/backend.jar runtime/last-success.jar
cp .env runtime/last-success.env
chmod 600 runtime/last-success.jar runtime/last-success.env
docker compose up -d --no-deps web
```

Tài liệu tham khảo: [GitHub Actions + Maven](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven),
[Caddy SPA/HTTPS](https://caddyserver.com/docs/caddyfile/patterns),
[PostgreSQL pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html).
