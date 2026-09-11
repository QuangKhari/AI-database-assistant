import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { authApi } from "../api/authApi";
import type { UserInfo } from "../api/types";

const USER_KEY = "aidb_user";

interface AuthContextValue {
  user: UserInfo | null;
  loading: boolean;
  login: (identifier: string, password: string) => Promise<UserInfo>;
  register: (payload: {
    username: string;
    email: string;
    password: string;
  }) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);

  // LICH SU: truoc day ham nay chi kiem tra co token trong localStorage hay
  // khong (dong bo, khong goi mang) - nay JWT nam trong cookie httpOnly ma
  // JS khong doc duoc nua, nen CACH DUY NHAT de biet "co dang dang nhap hop
  // le hay khong" la thuc su hoi BE (GET /users/me): neu cookie con hop le,
  // BE tra ve 200 kem thong tin user; neu khong, BE tra 401.
  const refreshUser = useCallback(async () => {
    try {
      const profile = await authApi.getProfile();

      const nextUser: UserInfo = {
        id: profile.id,
        username: profile.username,
        displayName: profile.displayName,
        email: profile.email,
        role: profile.role,
        // UserProfile (GET /users/me) không có trường createdAt (chỉ
        // AuthResponse lúc login/register có sẵn từ trước, và cũng không
        // trả field này) - giữ rỗng như hành vi cũ, không có UI nào hiện
        // đang hiển thị ngày tạo tài khoản.
        createdAt: "",
      };

      localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
      setUser(nextUser);
    } catch {
      // Khong dang nhap (chua co cookie/cookie het han) - day la truong hop
      // BINH THUONG (VD: lan dau vao app), khong phai loi can bao cho user.
      localStorage.removeItem(USER_KEY);
      setUser(null);
    }
  }, []);

  useEffect(() => {
    refreshUser().finally(() => setLoading(false));

    const handleUnauthorized = () => {
      localStorage.removeItem(USER_KEY);
      setUser(null);
    };
    window.addEventListener("auth:unauthorized", handleUnauthorized);
    return () =>
      window.removeEventListener("auth:unauthorized", handleUnauthorized);
  }, [refreshUser]);

  const login = useCallback(async (identifier: string, password: string) => {
    const response = await authApi.login({ identifier, password });

    // KHONG con storeToken(response.token) - BE da tu dat cookie httpOnly
    // access_token qua header Set-Cookie cua chinh response nay roi, FE
    // khong can (va khong nen) tu tay giu lai token o dau ca.

    // AuthResponse (/auth/login) không trả email, chỉ có token/username/role.
    // Gọi thêm /users/me để lấy email thật - trước đây hard-code email: ""
    // khiến ô email ở header (AppShell) luôn trống sau mọi lần đăng nhập
    // (chỉ đúng ngay sau khi đăng ký vì lúc đó có sẵn email từ form).
    // Nếu gọi lỗi (BE tạm thời chậm...), vẫn đăng nhập thành công với email
    // rỗng thay vì làm hỏng cả luồng đăng nhập.
    let email = "";
    let id = 0;
    try {
      const profile = await authApi.getProfile();
      email = profile.email;
      id = profile.id;
    } catch {}

    const user: UserInfo = {
      id,
      username: response.username,
      displayName: null,
      email,
      role: response.role,
      createdAt: "",
    };

    localStorage.setItem(USER_KEY, JSON.stringify(user));
    setUser(user);

    return user;
  }, []);

  const register = useCallback(
    async (payload: { username: string; email: string; password: string }) => {
      const response = await authApi.register(payload);

      // KHONG con storeToken(response.token) - ly do giong login() o tren.

      const user: UserInfo = {
        id: 0,
        username: response.username,
        displayName: null,
        // AuthResponse.java (BE) không trả email, nhưng ta đã có từ payload
        // đăng ký -> dùng luôn để không hiển thị rỗng ở AppShell/Profile.
        email: payload.email,
        role: response.role,
        createdAt: "",
      };

      // BUG CŨ: register() thiếu dòng lưu localStorage (login() có, register()
      // không) -> nếu người dùng F5 ngay sau khi đăng ký, mất phiên dù token
      // vẫn còn hợp lệ (refreshUser() không tìm thấy USER_KEY -> setUser(null)).
      localStorage.setItem(USER_KEY, JSON.stringify(user));
      setUser(user);
    },
    [],
  );

  const logout = useCallback(async () => {
    // Cookie httpOnly access_token khong the xoa boi JS -> phai goi BE de
    // BE tu ghi de bang 1 cookie da het han (xem AuthController.logout()).
    // Van xoa user o client ngay ca khi goi mang that bai (VD: mat mang),
    // de nguoi dung khong bi "ket" o trang thai tuong nhu van dang nhap.
    try {
      await authApi.logout();
    } catch {
      // Bo qua - du sao cung se xoa trang thai client ben duoi.
    }
    localStorage.removeItem(USER_KEY);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, register, logout, refreshUser }),
    [user, loading, login, register, logout, refreshUser],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used within AuthProvider");
  return value;
}
