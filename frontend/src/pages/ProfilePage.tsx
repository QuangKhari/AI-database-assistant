import { useEffect, useState } from "react";
import { authApi } from "../api/authApi";
import { useApiError } from "../hook/useApiError";
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

  const [showCurrentPassword, setShowCurrentPassword] = useState(false);
  const [showNewPassword, setShowNewPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const { error, handleError, setError } = useApiError();

  useEffect(() => {
    async function loadProfile() {
      setLoading(true);
      setError("");

      try {
        const result = await authApi.getProfile();

        setProfile(result);
        setEmail(result.email);
      } catch (reason) {
        handleError(reason, "Không thể tải thông tin hồ sơ.");
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
      const result = await authApi.updateProfile({
        email: cleanEmail,
      });

      setProfile(result);
      setEmail(result.email);

      showToast("Đã cập nhật thông tin hồ sơ.", "success");
    } catch (reason) {
      handleError(reason, "Không thể cập nhật hồ sơ.");
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
      await authApi.changePassword({
        currentPassword,
        newPassword,
      });

      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");

      setShowCurrentPassword(false);
      setShowNewPassword(false);
      setShowConfirmPassword(false);

      showToast("Đổi mật khẩu thành công.", "success");
    } catch (reason) {
      handleError(reason, "Không thể đổi mật khẩu.");
    } finally {
      setChangingPassword(false);
    }
  }

  const displayName =
    profile?.displayName?.trim() || profile?.username || "Người dùng";

  const avatarLetter = displayName.slice(0, 1).toUpperCase();

  const roleLabel = profile?.role === "ADMIN" ? "Quản trị viên" : "Người dùng";

  if (loading) {
    return (
      <div className={styles.page}>
        <div className={styles.heading}>
          <div>
            <p>Tài khoản</p>
            <h1>Hồ sơ</h1>
            <span>Quản lý thông tin tài khoản và bảo mật.</span>
          </div>
        </div>

        <section className={styles.loadingCard}>
          <div className={styles.loadingAvatar}>AI</div>

          <div className={styles.loadingContent}>
            <div className={styles.loadingLineLarge} />
            <div className={styles.loadingLine} />
            <div className={styles.loadingLineShort} />
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      {/* =====================================================
          HEADER
          ===================================================== */}

      <div className={styles.heading}>
        <div>
          <p>Tài khoản</p>

          <h1>Hồ sơ</h1>

          <span>Quản lý thông tin tài khoản và bảo mật của bạn.</span>
        </div>
      </div>

      {/* =====================================================
          PROFILE HERO
          ===================================================== */}

      <section className={styles.profileHero}>
        <div className={styles.profileIdentity}>
          <div className={styles.avatar}>{avatarLetter}</div>

          <div className={styles.identityContent}>
            <div className={styles.identityTop}>
              <h2>{displayName}</h2>

              <span className={styles.statusBadge}>
                <span className={styles.statusDot} />
                Đang hoạt động
              </span>
            </div>

            <p>@{profile?.username}</p>

            <span className={styles.email}>{profile?.email}</span>
          </div>
        </div>

        <div className={styles.profileMeta}>
          <div className={styles.metaItem}>
            <span>Vai trò</span>
            <strong>{roleLabel}</strong>
          </div>

          <div className={styles.metaDivider} />

          <div className={styles.metaItem}>
            <span>Trạng thái</span>
            <strong className={styles.activeText}>Hoạt động</strong>
          </div>
        </div>
      </section>

      {/* =====================================================
          ERROR
          ===================================================== */}

      {error && (
        <div className={styles.error} role="alert">
          <span className={styles.errorIcon}>!</span>

          <div>
            <strong>Có vấn đề xảy ra</strong>
            <p>{error}</p>
          </div>
        </div>
      )}

      {/* =====================================================
          MAIN CARDS
          ===================================================== */}

      <div className={styles.columns}>
        {/* =================================================
            ACCOUNT INFORMATION
            ================================================= */}

        <section className={styles.panel}>
          <div className={styles.panelTitle}>
            <div className={styles.sectionIcon}>
              <span>01</span>
            </div>

            <div>
              <h2>Thông tin tài khoản</h2>

              <p>Cập nhật thông tin liên hệ của tài khoản.</p>
            </div>
          </div>

          <form onSubmit={saveProfile}>
            <label>
              <span className={styles.labelText}>Tên đăng nhập</span>

              <div className={styles.inputWrapper}>
                <span className={styles.inputIcon}>@</span>

                <input value={profile?.username ?? ""} disabled readOnly />

                <span className={styles.lockBadge}>Khóa</span>
              </div>

              <small className={styles.helper}>
                Tên đăng nhập không thể thay đổi.
              </small>
            </label>

            <label>
              <span className={styles.labelText}>Vai trò</span>

              <div className={styles.inputWrapper}>
                <span className={styles.inputIcon}>◈</span>

                <input value={roleLabel} disabled readOnly />

                <span className={styles.roleBadge}>{profile?.role}</span>
              </div>
            </label>

            <label>
              <span className={styles.labelText}>Email</span>

              <div className={styles.inputWrapper}>
                <span className={styles.inputIcon}>@</span>

                <input
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  autoComplete="email"
                  disabled={savingProfile}
                />
              </div>

              <small className={styles.helper}>
                Email được sử dụng cho các thông báo và khôi phục tài khoản.
              </small>
            </label>

            <div className={styles.formFooter}>
              <span className={styles.formHint}>
                Thay đổi sẽ được lưu ngay lập tức.
              </span>

              <button type="submit" disabled={savingProfile}>
                {savingProfile ? "Đang lưu…" : "Lưu thay đổi"}
              </button>
            </div>
          </form>
        </section>

        {/* =================================================
            SECURITY
            ================================================= */}

        <section className={styles.panel}>
          <div className={styles.panelTitle}>
            <div className={`${styles.sectionIcon} ${styles.securityIcon}`}>
              <span>02</span>
            </div>

            <div>
              <h2>Bảo mật tài khoản</h2>

              <p>Thay đổi mật khẩu để bảo vệ tài khoản.</p>
            </div>
          </div>

          <div className={styles.securityNotice}>
            <span className={styles.securityNoticeIcon}>✓</span>

            <div>
              <strong>Bảo mật mật khẩu</strong>

              <p>
                Sử dụng mật khẩu có ít nhất 8 ký tự và không dùng lại mật khẩu
                cũ.
              </p>
            </div>
          </div>

          <form onSubmit={changePassword}>
            <label>
              <span className={styles.labelText}>Mật khẩu hiện tại</span>

              <div className={styles.passwordWrapper}>
                <input
                  type={showCurrentPassword ? "text" : "password"}
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                  autoComplete="current-password"
                  disabled={changingPassword}
                  placeholder="Nhập mật khẩu hiện tại"
                />

                <button
                  type="button"
                  className={styles.passwordToggle}
                  onClick={() => setShowCurrentPassword((value) => !value)}
                  aria-label={
                    showCurrentPassword ? "Ẩn mật khẩu" : "Hiển thị mật khẩu"
                  }
                >
                  {showCurrentPassword ? "Ẩn" : "Hiện"}
                </button>
              </div>
            </label>

            <label>
              <span className={styles.labelText}>Mật khẩu mới</span>

              <div className={styles.passwordWrapper}>
                <input
                  type={showNewPassword ? "text" : "password"}
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  disabled={changingPassword}
                  placeholder="Nhập mật khẩu mới"
                />

                <button
                  type="button"
                  className={styles.passwordToggle}
                  onClick={() => setShowNewPassword((value) => !value)}
                  aria-label={
                    showNewPassword ? "Ẩn mật khẩu" : "Hiển thị mật khẩu"
                  }
                >
                  {showNewPassword ? "Ẩn" : "Hiện"}
                </button>
              </div>

              <small className={styles.helper}>Tối thiểu 8 ký tự.</small>
            </label>

            <label>
              <span className={styles.labelText}>Xác nhận mật khẩu mới</span>

              <div className={styles.passwordWrapper}>
                <input
                  type={showConfirmPassword ? "text" : "password"}
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  disabled={changingPassword}
                  placeholder="Nhập lại mật khẩu mới"
                />

                <button
                  type="button"
                  className={styles.passwordToggle}
                  onClick={() => setShowConfirmPassword((value) => !value)}
                  aria-label={
                    showConfirmPassword ? "Ẩn mật khẩu" : "Hiển thị mật khẩu"
                  }
                >
                  {showConfirmPassword ? "Ẩn" : "Hiện"}
                </button>
              </div>
            </label>

            <div className={styles.formFooter}>
              <span className={styles.formHint}>
                Sau khi đổi mật khẩu, hãy sử dụng mật khẩu mới cho lần đăng nhập
                tiếp theo.
              </span>

              <button type="submit" disabled={changingPassword}>
                {changingPassword ? "Đang cập nhật…" : "Đổi mật khẩu"}
              </button>
            </div>
          </form>
        </section>
      </div>

      {/* =====================================================
          SECURITY FOOTER
          ===================================================== */}

      <section className={styles.securityFooter}>
        <div className={styles.securityFooterIcon}>✓</div>

        <div>
          <strong>Tài khoản của bạn được bảo vệ</strong>

          <p>
            Thông tin đăng nhập và mật khẩu được xử lý thông qua cơ chế xác thực
            của hệ thống.
          </p>
        </div>
      </section>
    </div>
  );
}
