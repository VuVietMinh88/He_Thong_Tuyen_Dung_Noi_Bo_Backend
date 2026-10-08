# Giới hạn của cách triển khai

- Health/liveness thành công không phát hiện được mọi sai lệch nghiệp vụ; sau mỗi lần
  deploy vẫn cần thử đăng nhập và các luồng chính.
- Mỗi lần deploy, website tạm ngừng vài phút để sao lưu DB và kiểm tra bản mới.
- Email đã gửi ra ngoài không thể thu hồi bằng rollback DB.
- Backup nằm cùng VPS, không thay cho bản sao ngoài VPS khi mất cả máy.
- Cấu hình SMTP, chứng chỉ HTTPS, firewall và Docker thực tế cần kiểm tra trên VPS.
