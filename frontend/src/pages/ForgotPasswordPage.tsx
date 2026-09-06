import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { authApi } from "../api/authApi";
import { ApiError } from "../api/client";
import styles from "../styles/Form.module.css";
import { validateEmail } from "../utils/validation";

export function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const validationError = validateEmail(email);
    if (validationError) {
      setError(validationError);
      return;
    }
    setError("");
    setMessage("");
    setSubmitting(true);
    try {
      const response = await authApi.forgotPassword(email.trim());
      setMessage(response.message);
    } catch (reason) {
      setError(
        reason instanceof ApiError
          ? reason.message
          : "Không thể kết nối máy chủ. Vui lòng thử lại.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.card}>
      <div className={styles.heading}>
        <h1>Khôi phục mật khẩu</h1>
        <p>
          Nhập email tài khoản. Nếu tồn tại, chúng tôi sẽ gửi liên kết đặt lại
          mật khẩu.
        </p>
      </div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        <div className={styles.field}>
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={Boolean(error)}
            placeholder="you@example.com"
          />
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
        </div>
        {message && (
          <div className={styles.success} role="status">
            {message}
          </div>
        )}
        <button className={styles.primary} type="submit" disabled={submitting}>
          {submitting ? "Đang gửi…" : "Gửi liên kết khôi phục"}
        </button>
      </form>
      <p className={styles.footer}>
        <Link className={styles.back} to="/login">
          <span className={styles.arrow}>←</span> Quay lại đăng nhập
        </Link>
      </p>
    </div>
  );
}
