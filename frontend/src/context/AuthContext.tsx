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
import { getStoredToken, removeToken, storeToken } from "../api/client";
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

  const refreshUser = useCallback(async () => {
    const token = getStoredToken();

    if (!token) {
      setUser(null);
      return;
    }

    const storedUser = localStorage.getItem(USER_KEY);

    if (!storedUser) {
      setUser(null);
      return;
    }

    try {
      setUser(JSON.parse(storedUser) as UserInfo);
    } catch {
      localStorage.removeItem(USER_KEY);
      setUser(null);
    }
  }, []);

  useEffect(() => {
    refreshUser()
      .catch(() => {
        removeToken();
        setUser(null);
      })
      .finally(() => setLoading(false));

    const handleUnauthorized = () => {
      removeToken();
      localStorage.removeItem(USER_KEY);
      setUser(null);
    };
    window.addEventListener("auth:unauthorized", handleUnauthorized);
    return () =>
      window.removeEventListener("auth:unauthorized", handleUnauthorized);
  }, [refreshUser]);

  const login = useCallback(async (identifier: string, password: string) => {
    const response = await authApi.login({ identifier, password });

    storeToken(response.token);

    // AuthResponse (/auth/login) không trả email, chỉ có token/username/role.
    // Gọi thêm /users/me để lấy email thật - trước đây hard-code email: ""
    // khiến ô email ở header (AppShell) luôn trống sau mọi lần đăng nhập
    // (chỉ đúng ngay sau khi đăng ký vì lúc đó có sẵn email từ form).
    // Nếu gọi lỗi (BE tạm thời chậm...), vẫn đăng nhập thành công với email
    // rỗng thay vì làm hỏng cả luồng đăng nhập.
    let email = "";
    try {
      const profile = await authApi.getProfile();
      email = profile.email;
    } catch {
      // bỏ qua - không được để lỗi lấy profile làm hỏng đăng nhập
    }

    const user: UserInfo = {
      id: 0,
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

      storeToken(response.token);

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
    // JWT stateless: BE không có endpoint /api/auth/logout, nên chỉ cần xóa
    // token + user phía client (đúng theo kế hoạch đồng bộ FE/BE).
    removeToken();
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
