# API Contract — phần 1–6 và 10

Base URL local: `http://localhost:8080/api`. Dữ liệu gửi/nhận ở dạng JSON. Endpoint có biểu tượng 🔒 yêu cầu header `Authorization: Bearer <accessToken>`.

## Quy ước lỗi

```json
{
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Dữ liệu không hợp lệ",
  "fieldErrors": { "email": "must be a well-formed email address" }
}
```

Các mã chính: `VALIDATION_ERROR` (400), `BAD_REQUEST` (400), `UNAUTHORIZED` (401), `ACCOUNT_LOCKED` (403), `ACCESS_DENIED` (403), `QUERY_ALREADY_RUNNING` (409), `RATE_LIMIT_EXCEEDED` (429), `INTERNAL_ERROR` (500).

## Authentication

### `POST /auth/register`

```json
{
  "username": "student_01",
  "displayName": "Nguyễn Văn A",
  "email": "student@example.com",
  "password": "Secure123"
}
```

`username`: 3–30 ký tự chữ/số/`.`/`_`/`-`. `password`: 8–72 ký tự, có chữ hoa, chữ thường và số. Thành công trả `201`:

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresInSeconds": 86400,
  "user": {
    "id": 1,
    "username": "student_01",
    "displayName": "Nguyễn Văn A",
    "email": "student@example.com",
    "role": "USER",
    "createdAt": "2026-08-25T00:00:00"
  }
}
```

### `POST /auth/login`

`identifier` có thể là email hoặc username.

```json
{ "identifier": "student@example.com", "password": "Secure123" }
```

Thành công trả `200` với cấu trúc giống đăng ký. Sai thông tin trả `401` và không cho biết email/username nào sai.

### `POST /auth/forgot-password`

```json
{ "email": "student@example.com" }
```

Luôn trả cùng thông báo `200` dù email có tồn tại hay không để tránh dò tài khoản. Giới hạn 3 lần/giờ theo cả email và IP. Email local xuất hiện tại Mailpit.

### `POST /auth/reset-password`

```json
{
  "token": "token-lay-tu-email",
  "newPassword": "NewSecure123",
  "confirmPassword": "NewSecure123"
}
```

Token tồn tại 30 phút và chỉ dùng một lần. Thành công trả `200` cùng `message`.

### 🔒 `POST /auth/logout`

Trả `204`. Vì JWT stateless, frontend chịu trách nhiệm xóa token local; token cũng tự hết hạn theo cấu hình.

## User account

### 🔒 `GET /users/me`

Trả `200`:

```json
{
  "id": 1,
  "username": "student_01",
  "displayName": "Nguyễn Văn A",
  "email": "student@example.com",
  "role": "USER",
  "enabled": true,
  "locked": false,
  "createdAt": "2026-08-25T00:00:00",
  "updatedAt": "2026-08-25T00:00:00"
}
```

### 🔒 `PUT /users/me`

```json
{ "displayName": "Tên mới", "email": "new@example.com" }
```

Username không được phép đổi. Email phải duy nhất. Thành công trả hồ sơ đã cập nhật.

### 🔒 `PUT /users/me/password`

```json
{
  "currentPassword": "Secure123",
  "newPassword": "NewSecure123",
  "confirmPassword": "NewSecure123"
}
```

Mật khẩu hiện tại phải đúng; mật khẩu mới phải khác mật khẩu hiện tại. Thành công trả `message`.

## System

### `GET /health`

Endpoint public dùng để kiểm tra backend đã chạy. Trả `200` cùng trạng thái hệ thống.

## Target MySQL connections

Mọi endpoint trong nhóm này yêu cầu JWT. MVP chỉ nhận `dbType: "mysql"`; không nhận raw JDBC URL.

### `POST /connections/test`

Kiểm tra tạm thời, không lưu và luôn đóng JDBC connection sau khi xong. Giới hạn 10 lần/phút/user.

```json
{
  "name": "Database bán hàng",
  "dbType": "mysql",
  "host": "127.0.0.1",
  "port": 3309,
  "databaseName": "sample_store",
  "username": "aidb_reader",
  "password": "..."
}
```

Response cho biết `successful`, `readOnlyVerified`, `code`, `message`, `durationMs` và `serverVersion`. Tài khoản có quyền ghi trả `successful: false` và không được lưu.

### `POST /connections`

Test và xác minh read-only trước khi tạo. Mỗi user tối đa 5 connection đang hoạt động. Mật khẩu được mã hóa AES-GCM và không có trong response. Thành công trả `201`.

### `GET /connections` và `GET /connections/{id}`

Chỉ trả connection thuộc JWT hiện tại. User không thể đọc connection của user khác.

### `PUT /connections/{id}`

Test lại trước khi lưu. Khi `password` rỗng, giữ mật khẩu cũ; nếu connection đang ngắt thì cập nhật thành công sẽ kích hoạt lại.

### `POST /connections/{id}/reconnect`

Kiểm tra lại cấu hình đã lưu. Nếu thành công và read-only, đặt connection về hoạt động.

### `DELETE /connections/{id}`

Ngắt mềm (`active=false`), không xóa cấu hình hoặc lịch sử. Trả `204`.

## Schema metadata

Mọi endpoint chỉ thao tác trên connection thuộc user trong JWT và đang hoạt động.

### `POST /schema/connections/{connectionId}/sync`

Mở một JDBC connection chỉ đọc, đọc table, column, datatype, nullable, PK và FK rồi đóng connection. Chỉ thay metadata đang lưu khi toàn bộ bước đọc thành công. Trả `200`:

```json
{
  "id": 1,
  "connectionId": 4,
  "databaseName": "sample_store",
  "lastSyncedAt": "2026-08-28T00:00:00",
  "tables": [{
    "id": 10,
    "name": "orders",
    "description": null,
    "columns": [{
      "id": 20,
      "name": "customer_id",
      "dataType": "BIGINT",
      "nullable": false,
      "primaryKey": false,
      "foreignKey": true,
      "referencedTable": "customers",
      "referencedColumn": "id",
      "description": null
    }]
  }]
}
```

### `GET /schema/connections/{connectionId}`

Trả metadata đã đồng bộ. Nếu chưa sync, trả `400` và không tự kết nối Target DB.

### `PUT /schema/tables/{tableId}` và `PUT /schema/columns/{columnId}`

Body: `{ "description": "Mô tả nghiệp vụ" }`. Chỉ owner của connection chứa metadata mới được sửa.

## Chat và sinh SQL

### `POST /chat/preview`

```json
{
  "connectionId": 4,
  "conversationId": 8,
  "question": "Liệt kê 10 khách hàng có tổng đơn hàng cao nhất"
}
```

`conversationId` để trống khi tạo cuộc trò chuyện mới. Backend gửi schema cùng tối đa 3 lượt gần nhất tới OpenAI, sau đó kiểm tra SQL chỉ đọc và tên bảng trước khi lưu preview. Endpoint này **không thực thi SQL**.

```json
{
  "conversationId": 8,
  "userMessageId": 31,
  "assistantMessageId": 32,
  "generatedSql": "SELECT ...",
  "valid": true,
  "validationError": null
}
```

### `GET /conversations?connectionId={connectionId}`

Liệt kê conversation của user hiện tại theo connection, mới nhất trước.

### `GET /conversations/{id}/messages`

Trả message của conversation thuộc user hiện tại. User khác không thể đọc.

### `DELETE /conversations/{id}`

Xóa conversation thuộc user hiện tại và dữ liệu con. Trả `204`.

## Thực thi SQL read-only

### `POST /query/execute`

Frontend chỉ gửi ID của assistant message chứa SQL preview đã lưu, không gửi raw SQL. Backend kiểm tra lại ownership, connection đang hoạt động, schema và tính read-only trước mỗi lần chạy.

```json
{
  "assistantMessageId": 32,
  "timeoutSeconds": 20
}
```

`timeoutSeconds` có thể bỏ trống để dùng mặc định 20 giây và không được vượt quá 30 giây. Mỗi user chỉ có một query chạy tại một thời điểm; request thứ hai đồng thời trả `409 QUERY_ALREADY_RUNNING`.

Response thành công:

```json
{
  "conversationId": 8,
  "messageId": 32,
  "generatedSql": "SELECT id, total FROM orders",
  "status": "SUCCESS",
  "timeoutSeconds": 20,
  "result": {
    "columns": ["id", "total"],
    "rows": [{ "id": 1, "total": 125000 }],
    "executionTimeMs": 18,
    "rowCount": 1,
    "truncated": false,
    "errorCode": null,
    "error": null
  }
}
```

`status` là `SUCCESS`, `FAILED` hoặc `TIMEOUT`. API chỉ trả tối đa 500 dòng; khi còn dữ liệu phía sau, `truncated=true`. JDBC connection, statement và result set luôn được đóng sau khi hoàn tất hoặc có lỗi.

Hệ thống chặn câu lệnh ghi, nhiều statement, truy vấn database khác, `FOR UPDATE`, ghi file và các hàm gây giữ tài nguyên như `SLEEP`/`BENCHMARK`. Subquery không bị chặn riêng: nếu OpenAI sinh ra SQL hợp lệ, chỉ dùng bảng trong schema và vượt qua toàn bộ kiểm tra thì có thể chạy, nhưng không thuộc bộ test bắt buộc của MVP.

## Admin

Yêu cầu JWT có role `ADMIN`. Admin đầu tiên được tạo một lần từ biến môi trường khi hệ thống chưa có Admin.

### `GET /admin/stats`

Trả tổng USER, USER hoạt động, USER đã khóa và tổng connection hoạt động.

### `GET /admin/users?search=&page=0&size=20`

Tìm theo username/email/tên hiển thị và phân trang. Chỉ liệt kê role USER; không trả mật khẩu tài khoản, Target DB credentials hay dữ liệu truy vấn.

### `PATCH /admin/users/{id}/lock`

Khóa USER. JWT đã cấp cho user đó bị từ chối ở request tiếp theo. Admin không thể tự khóa hoặc khóa Admin khác.

### `PATCH /admin/users/{id}/unlock`

Mở khóa USER; user có thể đăng nhập lại.
