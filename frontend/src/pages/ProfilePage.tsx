import { useEffect, useState } from "react";
import { authApi } from "../api/authApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type { UserProfile } from "../api/types";
import { useToast } from "../context/ToastContext";
import styles from "./ProfilePage.module.css";
export function ProfilePage() {
  const { showToast } = useToast();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [email, setEmail] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [changingPassword, setChangingPassword] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    async function loadProfile() {
      setLoading(true);
      setError("");
      try {
        const result = await authApi.getProfile();
        setProfile(result);
        setEmail(result.email);
      } catch (reason) {
        setError(
          formatErrorWithSupportCode(
            parseApiError(reason, "Không thể tải thông tin hồ sơ."),
          ),
        );
      } finally {
        setLoading(false);
      }
    }
    void loadProfile();
  }, []);
  async function saveProfile(event: React.FormEvent) {
    event.preventDefault();
    const cleanEmail = email.trim();
    if (!cleanEmail) {
      setError("Email không được để trống.");
      return;
    }
    setSavingProfile(true);
    setError("");
    try {
      const result = await authApi.updateProfile({ email: cleanEmail });
      setProfile(result);
      setEmail(result.email);
      showToast("Đã cập nhật thông tin hồ sơ.", "success");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể cập nhật hồ sơ."),
        ),
      );
    } finally {
      setSavingProfile(false);
    }
  }
  async function changePassword(event: React.FormEvent) {
    event.preventDefault();
    if (!currentPassword) {
      setError("Vui lòng nhập mật khẩu hiện tại.");
      return;
    }
    if (!newPassword) {
      setError("Vui lòng nhập mật khẩu mới.");
      return;
    }
    if (newPassword.length < 8) {
      setError("Mật khẩu mới phải có ít nhất 8 ký tự.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setError("Mật khẩu xác nhận không khớp.");
      return;
    }
    setChangingPassword(true);
    setError("");
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      showToast("Đổi mật khẩu thành công.", "success");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể đổi mật khẩu."),
        ),
      );
    } finally {
      setChangingPassword(false);
    }
  }
  if (loading) {
    return (
      <div className={styles.page}>
        {" "}
        <div className={styles.heading}>
          {" "}
          <div>
            {" "}
            <p>Tài khoản</p> <h1>Hồ sơ</h1>{" "}
            <span>Đang tải thông tin tài khoản…</span>{" "}
          </div>{" "}
        </div>{" "}
        <section className={styles.panel}>
          {" "}
          <p className={styles.loading}>Đang tải…</p>{" "}
        </section>{" "}
      </div>
    );
  }
  return (
    <div className={styles.page}>
      {" "}
      <div className={styles.heading}>
        {" "}
        <div>
          {" "}
          <p>Tài khoản</p> <h1>Hồ sơ</h1>{" "}
          <span>Quản lý thông tin tài khoản và mật khẩu.</span>{" "}
        </div>{" "}
      </div>{" "}
      {error && (
        <div className={styles.error} role="alert">
          {" "}
          {error}{" "}
        </div>
      )}{" "}
      <div className={styles.columns}>
        {" "}
        <section className={styles.panel}>
          {" "}
          <div className={styles.panelTitle}>
            {" "}
            <span>01</span>{" "}
            <div>
              {" "}
              <h2>Thông tin tài khoản</h2>{" "}
              <p>Cập nhật email của tài khoản hiện tại.</p>{" "}
            </div>{" "}
          </div>{" "}
          <form onSubmit={saveProfile}>
            {" "}
            <label>
              {" "}
              Tên đăng nhập{" "}
              <input value={profile?.username ?? ""} disabled readOnly />{" "}
            </label>{" "}
            <label>
              {" "}
              Vai trò{" "}
              <input value={profile?.role ?? ""} disabled readOnly />{" "}
            </label>{" "}
            <label>
              {" "}
              Email{" "}
              <input
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                disabled={savingProfile}
              />{" "}
            </label>{" "}
            <button type="submit" disabled={savingProfile}>
              {" "}
              {savingProfile ? "Đang lưu…" : "Lưu thay đổi"}{" "}
            </button>{" "}
          </form>{" "}
        </section>{" "}
        <section className={styles.panel}>
          {" "}
          <div className={styles.panelTitle}>
            {" "}
            <span>02</span>{" "}
            <div>
              {" "}
              <h2>Đổi mật khẩu</h2>{" "}
              <p>Nhập mật khẩu hiện tại trước khi đặt mật khẩu mới.</p>{" "}
            </div>{" "}
          </div>{" "}
          <form onSubmit={changePassword}>
            {" "}
            <label>
              {" "}
              Mật khẩu hiện tại{" "}
              <input
                type="password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
                autoComplete="current-password"
                disabled={changingPassword}
              />{" "}
            </label>{" "}
            <label>
              {" "}
              Mật khẩu mới{" "}
              <input
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                autoComplete="new-password"
                minLength={8}
                disabled={changingPassword}
              />{" "}
            </label>{" "}
            <label>
              {" "}
              Xác nhận mật khẩu mới{" "}
              <input
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                autoComplete="new-password"
                minLength={8}
                disabled={changingPassword}
              />{" "}
            </label>{" "}
            <button type="submit" disabled={changingPassword}>
              {" "}
              {changingPassword ? "Đang cập nhật…" : "Đổi mật khẩu"}{" "}
            </button>{" "}
          </form>{" "}
        </section>{" "}
      </div>{" "}
    </div>
  );
}
