import { useEffect, useState, type FormEvent } from 'react'
import { authApi } from '../api/authApi'
import { ApiError } from '../api/client'
import type { UserProfile } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { validateEmail, validatePassword } from '../utils/validation'
import styles from './ProfilePage.module.css'

export function ProfilePage() {
  const { refreshUser } = useAuth()
  const { showToast } = useToast()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [info, setInfo] = useState({ displayName: '', email: '' })
  const [password, setPassword] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const [loading, setLoading] = useState(true)
  const [infoError, setInfoError] = useState('')
  const [passwordError, setPasswordError] = useState('')
  const [savingInfo, setSavingInfo] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)

  useEffect(() => {
    authApi.getProfile().then((data) => {
      setProfile(data)
      setInfo({ displayName: data.displayName ?? '', email: data.email })
    }).catch((reason) => setInfoError(reason instanceof ApiError ? reason.message : 'Không tải được hồ sơ.')).finally(() => setLoading(false))
  }, [])

  async function handleInfo(event: FormEvent) {
    event.preventDefault()
    const emailError = validateEmail(info.email)
    if (emailError) { setInfoError(emailError); return }
    setInfoError(''); setSavingInfo(true)
    try {
      const updated = await authApi.updateProfile({ displayName: info.displayName.trim(), email: info.email.trim() })
      setProfile(updated)
      await refreshUser()
      showToast('Đã cập nhật thông tin tài khoản.', 'success')
    } catch (reason) { setInfoError(reason instanceof ApiError ? reason.message : 'Không thể cập nhật tài khoản.') }
    finally { setSavingInfo(false) }
  }

  async function handlePassword(event: FormEvent) {
    event.preventDefault()
    const validationError = validatePassword(password.newPassword)
    if (!password.currentPassword) { setPasswordError('Vui lòng nhập mật khẩu hiện tại.'); return }
    if (validationError) { setPasswordError(validationError); return }
    if (password.newPassword !== password.confirmPassword) { setPasswordError('Mật khẩu xác nhận không khớp.'); return }
    setPasswordError(''); setSavingPassword(true)
    try {
      const response = await authApi.changePassword(password)
      setPassword({ currentPassword: '', newPassword: '', confirmPassword: '' })
      showToast(response.message, 'success')
    } catch (reason) { setPasswordError(reason instanceof ApiError ? reason.message : 'Không thể đổi mật khẩu.') }
    finally { setSavingPassword(false) }
  }

  if (loading) return <p className={styles.loading}>Đang tải thông tin tài khoản…</p>

  return (
    <div className={styles.page}>
      <div className={styles.heading}><div><p>Tài khoản</p><h1>Hồ sơ và bảo mật</h1><span>Quản lý thông tin nhận diện và mật khẩu đăng nhập.</span></div>{profile && <div className={styles.member}>Thành viên từ<br /><strong>{new Date(profile.createdAt).toLocaleDateString('vi-VN')}</strong></div>}</div>
      <div className={styles.columns}>
        <section className={styles.panel}>
          <div className={styles.panelTitle}><span>01</span><div><h2>Thông tin cá nhân</h2><p>Tên đăng nhập không thể thay đổi.</p></div></div>
          <form onSubmit={handleInfo}>
            <label>Tên đăng nhập<input value={profile?.username ?? ''} disabled /></label>
            <label>Tên hiển thị<input value={info.displayName} onChange={(e) => setInfo({ ...info, displayName: e.target.value })} maxLength={100} placeholder="Tên bạn muốn hiển thị" /></label>
            <label>Email<input type="email" value={info.email} onChange={(e) => setInfo({ ...info, email: e.target.value })} /></label>
            {infoError && <p className={styles.error} role="alert">{infoError}</p>}
            <button type="submit" disabled={savingInfo}>{savingInfo ? 'Đang lưu…' : 'Lưu thay đổi'}</button>
          </form>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelTitle}><span>02</span><div><h2>Đổi mật khẩu</h2><p>Sử dụng mật khẩu mạnh và không dùng lại.</p></div></div>
          <form onSubmit={handlePassword}>
            <label>Mật khẩu hiện tại<input type="password" autoComplete="current-password" value={password.currentPassword} onChange={(e) => setPassword({ ...password, currentPassword: e.target.value })} /></label>
            <label>Mật khẩu mới<input type="password" autoComplete="new-password" value={password.newPassword} onChange={(e) => setPassword({ ...password, newPassword: e.target.value })} /></label>
            <label>Xác nhận mật khẩu mới<input type="password" autoComplete="new-password" value={password.confirmPassword} onChange={(e) => setPassword({ ...password, confirmPassword: e.target.value })} /></label>
            {passwordError && <p className={styles.error} role="alert">{passwordError}</p>}
            <button type="submit" disabled={savingPassword}>{savingPassword ? 'Đang cập nhật…' : 'Cập nhật mật khẩu'}</button>
          </form>
        </section>
      </div>
    </div>
  )
}
