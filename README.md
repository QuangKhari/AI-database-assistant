# AI Database Assistant

Ứng dụng web công khai giúp người dùng tạo kết nối MySQL của riêng mình và sử dụng AI để hỗ trợ sinh, kiểm tra, thực thi SQL chỉ đọc. Hiện tại **phần 1, 2, 3 và 10** đã hoàn thành: nền tảng local, tài khoản, quản lý Target MySQL và Admin.

## Những gì đã hoạt động

- Đăng ký tài khoản và tự đăng nhập sau khi đăng ký.
- Đăng nhập bằng email hoặc tên đăng nhập, JWT stateless và BCrypt.
- Quên mật khẩu qua email local; token hết hạn sau 30 phút, chỉ dùng một lần và chỉ lưu bản hash.
- Giới hạn yêu cầu quên mật khẩu: 3 lần/giờ theo email và IP.
- Xem/cập nhật tên hiển thị, email; đổi mật khẩu sau khi xác nhận mật khẩu hiện tại.
- Route frontend được bảo vệ, tự đăng xuất khi token không còn hợp lệ.
- User tự tạo/sửa/kiểm tra/ngắt/kết nối lại tối đa 5 Target MySQL thuộc sở hữu của mình.
- Chỉ nhận từng trường host/port/database, không nhận raw JDBC URL; chỉ lưu sau khi kiểm tra thành công.
- Từ chối MySQL account có quyền ghi; connection timeout 20 giây, trần 30 giây; tối đa 10 lần test/phút/user.
- Mật khẩu Target MySQL được mã hóa AES-GCM và không xuất hiện trong API response.
- Admin xem thống kê, tìm kiếm/phân trang user và khóa/mở khóa user; không xem credentials hoặc dữ liệu của user.
- MySQL System DB, Target MySQL mẫu chỉ đọc và Mailpit chạy bằng Docker Compose.

Các module metadata, sinh/thực thi SQL và lịch sử vẫn là phần sau. Endpoint schema cũ chưa được mở vì chưa áp dụng đủ giới hạn tài nguyên.

## Chạy local

Yêu cầu: Docker Desktop, Java 17, Node.js 20+.

1. Sao chép `.env.example` thành `.env` và thay các secret mẫu. File `.env` không được commit. Để tạo Admin đầu tiên, bật `ADMIN_BOOTSTRAP_ENABLED=true` và đặt username/email/password trong `.env`.
2. Tại thư mục gốc, chạy `docker compose up -d` để mở MySQL và Mailpit.
3. Tại `backend`, chạy `mvnw.cmd spring-boot:run`.
4. Tại `frontend`, chạy `npm install`, sau đó `npm run dev`.
5. Mở `http://localhost:5173`. Hộp thư local ở `http://localhost:8025`.

Backend tự đọc `.env` ở thư mục gốc. Nếu máy chưa nhận Java trong terminal mới, hãy đóng và mở lại terminal sau khi cài JDK.

## Kiểm thử

- Backend: tại `backend`, chạy `mvnw.cmd test`.
- Frontend: tại `frontend`, chạy `npm test`.
- Build frontend: tại `frontend`, chạy `npm run build`.
- Test API thủ công: mở [requests/auth.http](requests/auth.http), thực hiện theo thứ tự và điền access token.

## Địa chỉ local

| Thành phần | Địa chỉ |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend API | `http://localhost:8080/api` |
| MySQL System DB | `localhost:3308` |
| Target MySQL mẫu | `localhost:3309` |
| Mailpit UI | `http://localhost:8025` |
| Mailpit SMTP | `localhost:1025` |

Chi tiết request/response nằm trong [API_CONTRACT.md](API_CONTRACT.md).

## Checklist kiểm tra giao diện phần 1–3 và Admin

1. Vào `/register`, thử mật khẩu yếu/email sai, sau đó đăng ký hợp lệ.
2. Đăng xuất; thử đăng nhập lần lượt bằng username và email.
3. Vào `/profile`, đổi tên hiển thị/email; tải lại trang để kiểm tra dữ liệu đã lưu.
4. Thử đổi mật khẩu sai mật khẩu hiện tại, sau đó đổi hợp lệ và đăng nhập lại.
5. Vào `/forgot-password`, gửi email; mở Mailpit, bấm liên kết và đặt lại mật khẩu.
6. Dùng lại liên kết cũ để xác nhận hệ thống từ chối token đã dùng.
7. Mở `/profile` khi đã xóa token trong Local Storage để xác nhận bị chuyển về `/login`.
8. Vào `/connections`, thêm Target mẫu bằng các biến `TARGET_MYSQL_READONLY_*` trong `.env`; kiểm tra trạng thái read-only rồi lưu.
9. Thử tạo connection với user `root` của Target DB để xác nhận ứng dụng từ chối quyền ghi.
10. Sửa connection và để trống password để giữ mật khẩu cũ; ngắt rồi kết nối lại.
11. Đăng nhập Admin, vào `/admin`, tìm user và khóa user; xác nhận phiên user đang mở bị từ chối ở request tiếp theo.
12. Mở khóa user và xác nhận user đăng nhập lại được.
