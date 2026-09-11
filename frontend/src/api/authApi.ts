import { apiRequest } from "./client";
import type { AuthResponse, OperationResponse, UserProfile } from "./types";

export const authApi = {
  // Khớp RegisterRequest.java thật: chỉ có username/email/password. FE trước
  // đây gửi thêm "displayName" (BE không có field này -> Jackson mặc định bỏ
  // qua nên không lỗi cứng, nhưng dễ gây hiểu lầm là tính năng có hoạt động).
  register: (payload: { username: string; email: string; password: string }) =>
    apiRequest<AuthResponse>("/auth/register", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  login: (payload: { identifier: string; password: string }) =>
    apiRequest<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: payload.identifier,
        password: payload.password,
      }),
    }),

  // BE gio co POST /api/auth/logout (xem AuthController.java) de xoa cookie
  // httpOnly access_token phia server (ghi de bang cookie da het han).
  // JWT van la stateless (khong co "session" nao khac de huy), nhung can
  // goi endpoint nay vi FE khong con doc/xoa duoc cookie truc tiep nua.
  logout: () =>
    apiRequest<OperationResponse>("/auth/logout", {
      method: "POST",
    }),

  forgotPassword: (email: string) =>
    apiRequest<OperationResponse>("/auth/forgot-password", {
      method: "POST",
      body: JSON.stringify({ email }),
    }),

  resetPassword: (payload: {
    token: string;
    newPassword: string;
    confirmPassword: string;
  }) =>
    apiRequest<OperationResponse>("/auth/reset-password", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  // Khớp UserController.java (/api/users/me) - dùng cho ProfilePage và cho
  // AuthContext.login() để lấy email thật (AuthResponse của /auth/login
  // không có trường email, chỉ có token/username/role).
  getProfile: () => apiRequest<UserProfile>("/users/me"),
  updateProfile: (payload: { email: string }) =>
    apiRequest<UserProfile>("/users/me", {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  changePassword: (payload: { currentPassword: string; newPassword: string }) =>
    apiRequest<OperationResponse>("/users/me/password", {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
};
