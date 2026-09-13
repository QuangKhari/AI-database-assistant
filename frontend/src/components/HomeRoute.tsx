import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { LandingPage } from "../pages/LandingPage";
import styles from "./Loading.module.css";

export function HomeRoute() {
  const { user, loading } = useAuth();

  if (loading) {
    return (
      <div className={styles.screen}>
        <div className={styles.spinner} />
        <p>Đang kiểm tra phiên đăng nhập…</p>
      </div>
    );
  }

  if (user) return <Navigate to="/dashboard" replace />;

  return <LandingPage />;
}
