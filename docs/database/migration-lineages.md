# Một backend, hai lịch sử database được giữ nguyên

Bản thống nhất chạy cùng Java/API trên cả hai database cũ. Biến `APP_MIGRATION_LINEAGE` chỉ chọn lịch sử Flyway phù hợp,
không bật/tắt tính năng. Hai lịch sử cùng đi tới schema có tuyển dụng, ứng viên, Kanban, screening, gỡ/hết hạn tin,
phone unique và giới hạn quên mật khẩu theo trình duyệt.

| Database trước nâng cấp | Giá trị `.env` | SQL được nạp |
| --- | --- | --- |
| Database mới hoàn toàn | `APP_MIGRATION_LINEAGE=s5` (mặc định) | `database/migrations` + `database/migrations-shared` |
| Candidate/Sprint 5: V26 tạo application/CV, V27 tạo IP quota | `APP_MIGRATION_LINEAGE=s5` | Như trên |
| Feedback: V26 gỡ tin, V27 hết hạn tin, có thể đã tới V38/V39 | `APP_MIGRATION_LINEAGE=feedback` | `database/migrations-feedback` + `database/migrations-shared` |

Không đổi giá trị này sau khi database đã chạy V26. Database chỉ tới V25 có lịch sử chung nên có thể bắt đầu bằng S5.
Database mới cũng có thể chọn feedback nếu cần kiểm chứng đường đó; tính năng cuối cùng giống nhau.

## Vì sao không đổi tên V26/V27 cũ

Flyway ghi tên, phiên bản và checksum của SQL đã chạy vào `flyway_schema_history`. Đổi nội dung hoặc số của file đã áp
dụng làm file mới không còn khớp lịch sử. Chỉ đổi hai file cũng chưa đủ: database S5 tới V41 thiếu phone/reset, còn
feedback tới V39 thiếu nhiều migration ứng viên có số nhỏ hơn. Cả hai cần bước bổ sung có số lớn hơn lịch sử hiện hữu.

V1–V25, V7_1 và V17_1 giống nhau giữa hai bản. Bản local giữ nguyên toàn bộ 40 file S5 cũ và 31 file feedback cũ,
kể cả tên và bytes. Các bản sao SQL thêm chức năng còn thiếu được đánh số mới (bridge) theo bảng dưới; không đổi dòng
lịch sử cũ. Test `MigrationHistoryIntegrityTest` ghim SHA256 từ hai commit nguồn `da519e1` và `d88af34`.

## Bảng đối chiếu

| Chức năng | Version trong lineage S5 | Version trong lineage feedback |
| --- | --- | --- |
| Application và CV | V26 | **V42** |
| Quota IP nộp hồ sơ | V27 | **V43** |
| Email xác nhận/outbox | V28 | **V44** |
| Quota tra cứu hồ sơ | V31 | **V45** |
| Hồ sơ ứng viên và liên kết | V32 | **V46** |
| Nhật ký truy cập ứng viên | V33 | **V47** |
| Phạm vi đọc ứng viên của approver | V33_1 | **V48** |
| Lịch sử giai đoạn hồ sơ | V34 | **V49** |
| Email khi rút hồ sơ | V35 | **V50** |
| Giới thiệu ứng viên | V36 | **V51** |
| Quota giới thiệu | V37 | **V52** |
| Lý do loại và ngoại lệ bỏ qua phỏng vấn | V40 | **V53** |
| Nhãn, điểm, ghi chú screening | V41 | **V54** |
| Gỡ tin tuyển dụng | **V42** | V26 |
| Tự hết hạn tin | **V43** | V27 |
| Số điện thoại tài khoản unique | **V44** | V38 |
| Quota quên mật khẩu theo trình duyệt | **V45** | V39 |

Số in đậm là bridge mới của bản thống nhất. SQL bridge được sao chép nguyên bytes từ chức năng tương ứng, gồm cả
comment và thông báo lịch sử. Vì vậy lỗi trùng phone vẫn có tiền tố `V38` kể cả khi file bridge S5 là V44; kiểm tên
file trong lỗi Flyway để biết version đang áp dụng.

**Migration mới tiếp theo phải là V55 trở lên**, đặt duy nhất trong `database/migrations-shared`. Không thêm file
vào khoảng trống số của một lineage. Các thư mục runtime là ba sibling `db/migration`, `db/migration-feedback`,
`db/migration-shared`; Flyway chỉ nạp một legacy cùng shared, tránh quét cả hai V26/V27.

## Cách chuẩn bị nâng cấp database có dữ liệu

Các bước này là hướng dẫn cho lần triển khai sau. Công việc local hiện tại không tự kết nối hoặc sửa database làm
việc/VPS của bạn.

1. Dừng các phiên bản ứng dụng đang ghi dữ liệu; sao lưu database, file cấu hình và các khóa mã hóa cần thiết. Kiểm
   tra khôi phục bản sao lưu vào database thử nghiệm độc lập trước.
2. Trên bản sao, đọc lịch sử để chọn lineage; câu lệnh sau chỉ đọc metadata, không đọc dữ liệu tài khoản:

   ```sql
   SELECT installed_rank, version, description, script, checksum, success
   FROM flyway_schema_history
   ORDER BY installed_rank;
   ```

   V26 là `V26__create_job_applications_and_cvs.sql` thì chọn `s5`; V26 là
   `V26__unpublish_job_postings.sql` thì chọn `feedback`. Nếu history thiếu bất thường, đã bị sửa, chứa file khác hoặc
   một phiên bản không thuộc bản này, dừng để đối chiếu; không đoán theo tên bảng.
3. Nếu chưa có unique phone, kiểm số nhóm trùng trước khi chạy bridge:

   ```sql
   SELECT count(*) AS duplicated_phone_groups
   FROM (
       SELECT phone FROM user_accounts
       WHERE phone IS NOT NULL GROUP BY phone HAVING count(*) > 1
   ) duplicate_groups;
   ```

   Nếu lớn hơn 0, người quản trị quyết định tài khoản nào giữ số và sửa dữ liệu theo
   [hướng dẫn hồ sơ](../api/profile.md#dữ-liệu-và-phạm-vi). Migration không tự chọn hoặc xóa tài khoản.
4. Đặt `APP_MIGRATION_LINEAGE` trong cấu hình của bản sao thử nghiệm, chạy cùng JAR mới và kiểm log Flyway, health,
   đăng nhập, dữ liệu CV, giai đoạn/screening, gỡ/hết hạn tin và quota quên mật khẩu. Kiểm history cũ giữ nguyên và
   chỉ có các bridge dự kiến được thêm. Lần khởi động tiếp theo không chạy lại SQL đã thành công.
5. Chỉ áp dụng lên database đích khi bản sao đã đạt và có kế hoạch khôi phục được kiểm chứng. Việc này cần phạm vi
   triển khai riêng; kết quả test local không khẳng định một database VPS chưa kiểm tra đã tương thích.

`MigrationLineageConfiguration` chỉ nhận đúng `s5` hoặc `feedback`, buộc validate trước migrate, giữ out-of-order,
baseline-on-migrate tắt và clean bị vô hiệu hóa. File legacy không khớp checksum hoặc chọn nhầm lineage sẽ dừng
trước DDL bridge. Không dùng `repair`, xóa/sửa history, bật outOfOrder hoặc tạo lại database đang có dữ liệu để qua lỗi.

Mỗi SQL migration chạy trong transaction riêng. Ví dụ S5 V42/V43 thành công rồi V44 phát hiện trùng phone: hai bước
đầu có thể đã commit; V44 rollback, account không bị sửa, backend chưa sẵn sàng. Sau khi xử lý dữ liệu hợp lệ,
khởi động lại sẽ tiếp tục V44/V45. Không chạy tay lại các SQL cũ. Nếu cần quay về cả schema/dữ liệu, dùng bản sao lưu;
quay lại JAR cũ không tự hoàn tác schema và chưa được đảm bảo tương thích.

## Kiểm chứng local

`MigrationLineageUpgradeTest` tạo PostgreSQL tạm và các database ngẫu nhiên riêng, không đọc `.env` để chọn DB test:

- Tạo mới hai lineage, so sánh cột/constraint/index/trigger/function và seed quyền; migrate lại không có bước mới.
- Nâng từ S5 V41 giữ account, application, CV bytes/hash, candidate link, stage history, tag/điểm/ghi chú.
- Nâng từ feedback V39 giữ tin đã gỡ/hết hạn, lịch sử chỉ thêm và bộ đếm trình duyệt.
- Nâng từ V25/V26/V27 từng lineage; backfill liên kết application cũ; giữ toàn bộ dòng history cũ.
- Sai lineage/checksum dừng trước DDL, trùng phone dừng rồi tiếp tục được sau chỉnh dữ liệu có chủ ý.

Bốn test lịch sử feedback giữ nguyên target V26/V27/V38/V39 và dùng location feedback. Các test lịch sử S5 vẫn dùng
location mặc định và target cũ. `MigrationLineageConfigurationTest` kiểm cấu hình Spring và giá trị không hợp lệ.
Kết quả chạy thực tế được ghi trong tài liệu bàn giao của lượt kiểm chứng; danh sách trên mô tả phạm vi test.
