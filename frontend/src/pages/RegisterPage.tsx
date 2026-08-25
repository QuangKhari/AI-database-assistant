import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../context/AuthContext'
import styles from '../styles/Form.module.css'
import { USERNAME_PATTERN, validateEmail, validatePassword } from '../utils/validation'

type Errors = Record<string, string>

export function RegisterPage() {
  const { user, register } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', displayName: '', email: '', password: '', confirmPassword: '' })
  const [errors, setErrors] = useState<Errors>({})
  const [serverError, setServerError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  if (user) return <Navigate to="/" replace />

  function setField(field: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: '' }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const nextErrors: Errors = {}
    if (!USERNAME_PATTERN.test(form.username)) nextErrors.username = 'Dùng 3–30 ký tự: chữ, số, dấu chấm, gạch ngang hoặc gạch dưới.'
    const emailError = validateEmail(form.email)
    const passwordError = validatePassword(form.password)
    if (emailError) nextErrors.email = emailError
    if (passwordError) nextErrors.password = passwordError
    if (form.password !== form.confirmPassword) nextErrors.confirmPassword = 'Mật khẩu xác nhận không khớp.'
    setErrors(nextErrors)
    setServerError('')
    if (Object.keys(nextErrors).length) return

    setSubmitting(true)
    try {
      await register({ username: form.username.trim(), displayName: form.displayName.trim() || undefined, email: form.email.trim(), password: form.password })
      navigate('/', { replace: true })
    } catch (reason) {
      if (reason instanceof ApiError) {
        setServerError(reason.message)
        setErrors(reason.fieldErrors ?? {})
      } else setServerError('Không thể kết nối máy chủ. Vui lòng thử lại.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.card}>
      <div className={styles.heading}><h1>Tạo tài khoản</h1><p>Bắt đầu với một tài khoản riêng cho dữ liệu của bạn.</p></div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        <div className={styles.field}><label htmlFor="username">Tên đăng nhập</label><input id="username" autoComplete="username" value={form.username} onChange={(e) => setField('username', e.target.value)} aria-invalid={Boolean(errors.username)} placeholder="nguyenvana" />{errors.username && <p className={styles.error}>{errors.username}</p>}</div>
        <div className={styles.field}><label htmlFor="displayName">Tên hiển thị <span className={styles.helper}>(không bắt buộc)</span></label><input id="displayName" autoComplete="name" value={form.displayName} onChange={(e) => setField('displayName', e.target.value)} placeholder="Nguyễn Văn A" /></div>
        <div className={styles.field}><label htmlFor="email">Email</label><input id="email" type="email" autoComplete="email" value={form.email} onChange={(e) => setField('email', e.target.value)} aria-invalid={Boolean(errors.email)} placeholder="you@example.com" />{errors.email && <p className={styles.error}>{errors.email}</p>}</div>
        <div className={styles.field}><label htmlFor="password">Mật khẩu</label><input id="password" type="password" autoComplete="new-password" value={form.password} onChange={(e) => setField('password', e.target.value)} aria-invalid={Boolean(errors.password)} placeholder="Tạo mật khẩu" />{errors.password && <p className={styles.error}>{errors.password}</p>}<ul className={styles.passwordRules}><li>Từ 8 đến 72 ký tự</li><li>Có chữ hoa, chữ thường và ít nhất một số</li></ul></div>
        <div className={styles.field}><label htmlFor="confirmPassword">Xác nhận mật khẩu</label><input id="confirmPassword" type="password" autoComplete="new-password" value={form.confirmPassword} onChange={(e) => setField('confirmPassword', e.target.value)} aria-invalid={Boolean(errors.confirmPassword)} placeholder="Nhập lại mật khẩu" />{errors.confirmPassword && <p className={styles.error}>{errors.confirmPassword}</p>}</div>
        {serverError && <div className={styles.serverError} role="alert">{serverError}</div>}
        <button className={styles.primary} type="submit" disabled={submitting}>{submitting ? 'Đang tạo tài khoản…' : 'Tạo tài khoản'}</button>
      </form>
      <p className={styles.footer}>Đã có tài khoản? <Link to="/login">Đăng nhập</Link></p>
    </div>
  )
}
