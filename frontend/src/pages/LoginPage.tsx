import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../context/AuthContext'
import styles from '../styles/Form.module.css'

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  if (user) return <Navigate to={user.role === 'ADMIN' ? '/admin' : '/'} replace />

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError('')
    if (!identifier.trim() || !password) {
      setError('Vui lòng nhập đầy đủ thông tin đăng nhập.')
      return
    }
    setSubmitting(true)
    try {
      const loggedInUser = await login(identifier.trim(), password)
      const destination = (location.state as { from?: string } | null)?.from ?? (loggedInUser.role === 'ADMIN' ? '/admin' : '/')
      navigate(destination, { replace: true })
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không thể kết nối máy chủ. Vui lòng thử lại.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.card}>
      <div className={styles.heading}><h1>Chào mừng trở lại</h1><p>Đăng nhập để tiếp tục làm việc với dữ liệu của bạn.</p></div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        <div className={styles.field}>
          <label htmlFor="identifier">Email hoặc tên đăng nhập</label>
          <input id="identifier" autoComplete="username" value={identifier} onChange={(e) => setIdentifier(e.target.value)} placeholder="you@example.com" />
        </div>
        <div className={styles.field}>
          <div className={styles.labelRow}><label htmlFor="password">Mật khẩu</label><Link to="/forgot-password">Quên mật khẩu?</Link></div>
          <input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Nhập mật khẩu" />
        </div>
        {error && <div className={styles.serverError} role="alert">{error}</div>}
        <button className={styles.primary} type="submit" disabled={submitting}>{submitting ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
      </form>
      <p className={styles.footer}>Chưa có tài khoản? <Link to="/register">Đăng ký miễn phí</Link></p>
    </div>
  )
}
