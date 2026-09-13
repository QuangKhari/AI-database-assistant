import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import styles from "./AppShell.module.css";

export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const displayName =
    user?.displayName?.trim() || user?.username || "Người dùng";

  async function handleLogout() {
    await logout();
    navigate("/login", { replace: true });
  }

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <NavLink to="/dashboard" className={styles.brand}>
          <span>AI</span> QueryMate
        </NavLink>
        <nav aria-label="Điều hướng chính">
          <NavLink
            to="/dashboard"
            end
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Tổng quan
          </NavLink>
          <NavLink
            to="/chat"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Chat
          </NavLink>
          <NavLink
            to="/history"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Lịch sử
          </NavLink>
          <NavLink
            to="/schema"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Schema
          </NavLink>
          <NavLink
            to="/connections"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Connections
          </NavLink>
          <NavLink
            to="/benchmark"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Benchmark
          </NavLink>
          {user?.role === "ADMIN" && (
            <NavLink
              to="/admin"
              className={({ isActive }) =>
                isActive ? styles.active : undefined
              }
            >
              Admin
            </NavLink>
          )}
          <NavLink
            to="/profile"
            className={({ isActive }) => (isActive ? styles.active : undefined)}
          >
            Tài khoản
          </NavLink>
        </nav>
        <div className={styles.account}>
          <div className={styles.avatar}>
            {displayName.slice(0, 1).toUpperCase()}
          </div>
          <div>
            <strong>{displayName}</strong>
            <span>{user?.email}</span>
          </div>
          <button type="button" onClick={handleLogout}>
            Đăng xuất
          </button>
        </div>
      </header>
      <main className={styles.content}>
        <Outlet />
      </main>
    </div>
  );
}
