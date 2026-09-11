import { lazy, Suspense } from "react";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { AdminRoute } from "./components/AdminRoute";
import { AuthProvider } from "./context/AuthContext";
import { ToastProvider } from "./context/ToastContext";
import { AppShell } from "./layouts/AppShell";
import { AuthLayout } from "./layouts/AuthLayout";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";

// Các trang sau khi đăng nhập được code-split (React.lazy) thay vì import
// thẳng, vì trước đây toàn bộ App (kể cả ChatPage - kéo theo recharts, và
// BenchmarkPage/SchemaExplorerPage) bị gộp chung vào 1 chunk JS ~756KB.
// Giờ mỗi trang chỉ tải khi thực sự được điều hướng tới -> giảm tải ban đầu,
// đặc biệt là trang Login (chunk auth nhỏ, không kéo theo recharts).
const RegisterPage = lazy(() =>
  import("./pages/RegisterPage").then((m) => ({ default: m.RegisterPage })),
);
const ForgotPasswordPage = lazy(() =>
  import("./pages/ForgotPasswordPage").then((m) => ({
    default: m.ForgotPasswordPage,
  })),
);
const ResetPasswordPage = lazy(() =>
  import("./pages/ResetPasswordPage").then((m) => ({
    default: m.ResetPasswordPage,
  })),
);
const DashboardPage = lazy(() =>
  import("./pages/DashboardPage").then((m) => ({ default: m.DashboardPage })),
);
const AdminPage = lazy(() =>
  import("./pages/AdminPage").then((m) => ({ default: m.AdminPage })),
);
const ConnectionFormPage = lazy(() =>
  import("./pages/ConnectionFormPage").then((m) => ({
    default: m.ConnectionFormPage,
  })),
);
const ConnectionsPage = lazy(() =>
  import("./pages/ConnectionsPage").then((m) => ({
    default: m.ConnectionsPage,
  })),
);
const SchemaExplorerPage = lazy(() =>
  import("./pages/SchemaExplorerPage").then((m) => ({
    default: m.SchemaExplorerPage,
  })),
);
const ChatPage = lazy(() =>
  import("./pages/ChatPage").then((m) => ({ default: m.ChatPage })),
);
const HistoryPage = lazy(() =>
  import("./pages/HistoryPage").then((m) => ({ default: m.HistoryPage })),
);
const BenchmarkPage = lazy(() =>
  import("./pages/BenchmarkPage").then((m) => ({ default: m.BenchmarkPage })),
);
const ProfilePage = lazy(() =>
  import("./pages/ProfilePage").then((m) => ({ default: m.ProfilePage })),
);

// Fallback tối giản trong lúc chunk của route đang tải (thường chỉ vài trăm
// ms trên mạng bình thường vì mỗi chunk đã nhỏ hơn nhiều so với bundle gộp).
function RouteLoadingFallback() {
  return <div style={{ padding: "2rem" }}>Đang tải...</div>;
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Suspense fallback={<RouteLoadingFallback />}>
            <Routes>
              <Route element={<AuthLayout />}>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/register" element={<RegisterPage />} />
                <Route
                  path="/forgot-password"
                  element={<ForgotPasswordPage />}
                />
                <Route path="/reset-password" element={<ResetPasswordPage />} />
              </Route>
              <Route element={<ProtectedRoute />}>
                <Route element={<AppShell />}>
                  <Route index element={<DashboardPage />} />
                  <Route path="/connections" element={<ConnectionsPage />} />
                  <Route
                    path="/connections/new"
                    element={<ConnectionFormPage />}
                  />
                  <Route
                    path="/connections/:id/edit"
                    element={<ConnectionFormPage />}
                  />
                  <Route path="/schema" element={<SchemaExplorerPage />} />
                  <Route path="/chat" element={<ChatPage />} />
                  <Route path="/history" element={<HistoryPage />} />
                  <Route path="/benchmark" element={<BenchmarkPage />} />
                  <Route path="/profile" element={<ProfilePage />} />
                  <Route element={<AdminRoute />}>
                    <Route path="/admin" element={<AdminPage />} />
                  </Route>
                </Route>
              </Route>
              <Route path="*" element={<NotFoundPage />} />
            </Routes>
          </Suspense>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}
