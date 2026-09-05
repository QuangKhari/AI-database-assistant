# AI Database Assistant

Ứng dụng web công khai giúp người dùng tạo kết nối MySQL của riêng mình và sử dụng AI để hỗ trợ sinh, kiểm tra, thực thi SQL chỉ đọc. Hiện tại **phần 1–6 và 10** đã hoàn thành.

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
- User đồng bộ schema của connection do mình sở hữu; hệ thống đọc table, column, datatype, nullable, PK và FK bằng JDBC metadata.
- Schema Explorer cho phép chọn connection, tìm bảng/cột và đồng bộ lại; metadata cũ chỉ bị thay thế sau khi đọc thành công.
- Chat tiếng Việt/Anh dùng OpenAI Responses API để sinh SQL preview, kiểm tra read-only/schema và nhớ 3 lượt gần nhất.
- Mỗi conversation thuộc đúng một user và một connection; đổi connection sẽ tách ngữ cảnh.
- User chủ động chạy SQL preview đã lưu; frontend không gửi raw SQL và backend kiểm tra ownership/read-only/schema lại trước khi chạy.
- Query timeout mặc định 20 giây, tối đa 30 giây; chỉ một query/user tại một thời điểm và kết quả tối đa 500 dòng.
- Giao diện Chat hiển thị trạng thái, thời gian chạy, cảnh báo cắt bớt dữ liệu và bảng HTML động theo các cột kết quả.
- MySQL System DB, Target MySQL mẫu chỉ đọc và Mailpit chạy bằng Docker Compose.
- Có sẵn cấu hình chuẩn bị deployment cho một Ubuntu server: Docker Compose production, Nginx, backup, deploy và rollback script. Chưa tác động lên server/domain thật.

Các phần chạy SQL, tự sửa SQL, AI Summary, lịch sử đầy đủ và hoàn thiện hệ thống vẫn là phần sau.

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

## Checklist kiểm tra giao diện phần 1–6 và Admin

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
13. Vào `/schema`, chọn connection, nhấn đồng bộ và kiểm tra table/column/PK/FK; dùng ô tìm kiếm để lọc.
14. Vào `/chat`, chọn connection đã sync, gửi câu hỏi và kiểm tra SQL preview; hỏi tiếp để kiểm tra context.
15. Đổi connection và xác nhận conversation hiện tại được đóng, không dùng chéo schema/context.
16. Với SQL preview hợp lệ, chọn timeout 20/25/30 giây, nhấn `Run query` và kiểm tra trạng thái, thời gian cùng bảng kết quả động.
17. Chạy query trả hơn 500 dòng và xác nhận UI chỉ hiển thị 500 dòng kèm cảnh báo; thử mở hai lần chạy đồng thời và xác nhận lần thứ hai bị từ chối.
18. Thử SQL ghi/nhiều statement/khác database bằng API và xác nhận backend chặn trước khi mở JDBC query.

Xem hướng dẫn chuẩn bị server tại [DEPLOYMENT.md](DEPLOYMENT.md).
