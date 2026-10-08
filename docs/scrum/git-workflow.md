# Quy trình Git của nhóm

Nhóm gọi quy trình là **GitHub Flow đơn giản**, dùng `develop` tích hợp, `main` ổn định. Cập nhật của người dùng được ưu tiên hơn tài liệu cũ; Sprint 1 và Sprint 2 đã gộp.

```text
main
└── develop
    ├── feature/TKNHTTDNB1-90-login-api
    ├── bugfix/<JIRA-ID>-mo-ta
    ├── refactor/<JIRA-ID>-mo-ta
    └── chore/<JIRA-ID>-mo-ta
```

Một subtask có một branch, Subtask ID thật ngay sau dấu /. Commit và tiêu đề PR dùng type: mô tả ngắn, không thêm Jira ID; Jira links nằm trong mô tả PR. `SCRUM-101` là ID mẫu của nhóm; subtask này dùng `TKNHTTDNB1-90`.

1. Nhận issue và đọc tiêu chí chấp nhận.
2. `git checkout develop`, `git pull origin develop`.
3. `git checkout -b feature/TKNHTTDNB1-90-login-api`.
4. Code, test/build; lỗi thì sửa và chạy lại. Backend chạy `mvnw.cmd clean verify` từ thư mục gốc repo Backend.
5. Xem status/diff, commit theo convention, ví dụ `feat: add login API`. Không commit `.env`, secret hoặc file build.
6. Khi thành viên chủ động đưa code lên remote, push branch cá nhân với upstream tương ứng.
7. Trước PR: `git fetch origin`, `git merge origin/develop` ngay trên branch cá nhân. Xử lý conflict tại đó, test/build, commit và cập nhật branch.
8. PR vào `develop`, ít nhất 1 thành viên review. Sửa review, test/build, cập nhật chính branch đó.
9. CI/test phải đạt trước merge `develop`.
10. `develop` ổn định thì PR sang `main`, review/kiểm tra trước merge.
11. Công việc đã vào `main` thì xóa branch cá nhân hoàn tất; giữ main/develop.

Không push trực tiếp main/develop; không đưa conflict hoặc code đang lỗi lên hai branch này. Reviewer/CI cần team cấu hình trên repository khi team chủ động thực hiện; tài liệu local không tự thiết lập branch protection.

| Commit type | Ý nghĩa |
|---|---|
| `feat` | Chức năng |
| `fix` | Sửa lỗi |
| `refactor` | Cấu trúc, giữ hành vi |
| `test` | Kiểm thử |
| `docs` | Tài liệu |
| `chore` | Cấu hình/bảo trì |

Chỉ push nhánh cá nhân khi được yêu cầu; PR vào develop để chờ review và CI/Test. Không tự merge hoặc push trực tiếp main/develop. Không commit file agent/ChatGPT/memory, .env thật, cache hoặc build output.
