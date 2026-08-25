import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { authApi } from '../api/authApi'
import { ApiError } from '../api/client'
import styles from '../styles/Form.module.css'
import { validatePassword } from '../utils/validation'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [form, setForm] = useState({ password: '', confirmPassword: '' })
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const passwordError = validatePassword(form.password)
    if (!token) { setError('Liên kết đặt lại mật khẩu không hợp lệ.'); return }
    if (passwordError) { setError(passwordError); return }
    if (form.password !== form.confirmPassword) { setError('Mật khẩu xác nhận không khớp.'); return }
    setError(''); setSubmitting(true)
    try {
      const response = await authApi.resetPassword({ token, newPassword: form.password, confirmPassword: form.confirmPassword })
      setMessage(response.message)
      setForm({ password: '', confirmPassword: '' })
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không thể kết nối máy chủ. Vui lòng thử lại.')
    } finally { setSubmitting(false) }
  }

  return (
    <div className={styles.card}>
      <div className={styles.heading}><h1>Đặt mật khẩu mới</h1><p>Mật khẩu mới sẽ thay thế mật khẩu hiện tại của tài khoản.</p></div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        {!token && <div className={styles.serverError}>Liên kết thiếu mã xác nhận. Hãy yêu cầu một liên kết mới.</div>}
        <div className={styles.field}><label htmlFor="password">Mật khẩu mới</label><input id="password" type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} placeholder="Nhập mật khẩu mới" /></div>
        <div className={styles.field}><label htmlFor="confirmPassword">Xác nhận mật khẩu mới</label><input id="confirmPassword" type="password" autoComplete="new-password" value={form.confirmPassword} onChange={(e) => setForm({ ...form, confirmPassword: e.target.value })} placeholder="Nhập lại mật khẩu" /></div>
        {error && <div className={styles.serverError} role="alert">{error}</div>}
        {message && <div className={styles.success} role="status">{message} <Link to="/login">Đăng nhập ngay</Link>.</div>}
        <button className={styles.primary} type="submit" disabled={submitting || !token || Boolean(message)}>{submitting ? 'Đang cập nhật…' : 'Cập nhật mật khẩu'}</button>
      </form>
      <p className={styles.footer}><Link className={styles.back} to="/login">← Quay lại đăng nhập</Link></p>
    </div>
  )
}
