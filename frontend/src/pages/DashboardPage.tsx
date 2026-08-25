import { Link } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import styles from './DashboardPage.module.css'

export function DashboardPage() {
  const { user } = useAuth()
  const name = user?.displayName?.trim() || user?.username
  return (
    <div className={styles.page}>
      <div className={styles.hero}>
        <div><p>Không gian làm việc</p><h1>Xin chào, {name}!</h1><span>Kết nối MySQL của bạn được tách biệt, xác minh quyền chỉ đọc và mã hóa trước khi lưu.</span></div>
        <div className={styles.status}><span /> Hệ thống hoạt động</div>
      </div>
      <div className={styles.grid}>
        <article className={`${styles.card} ${styles.accountCard}`}><div className={styles.icon}>DB</div><h2>Kết nối cơ sở dữ liệu</h2><p>Tạo, kiểm tra và quản lý Target MySQL chỉ đọc của riêng bạn.</p><Link to="/connections">Quản lý connections →</Link></article>
        <article className={styles.card}><div className={styles.icon}>AI</div><h2>Hỏi dữ liệu bằng AI</h2><p>Chuyển câu hỏi tự nhiên thành SQL và kiểm tra trước khi thực thi.</p><button type="button" disabled>Sắp có trong phần 4</button></article>
        <article className={styles.card}><div className={styles.icon}>ME</div><h2>Thông tin tài khoản</h2><p>Cập nhật tên hiển thị, email hoặc thay đổi mật khẩu bảo mật.</p><Link to="/profile">Quản lý tài khoản →</Link></article>
      </div>
      <section className={styles.progress}>
        <div><span>1</span><strong>Nền tảng dự án</strong><small>Đã sẵn sàng</small></div>
        <i />
        <div><span>2</span><strong>Tài khoản & bảo mật</strong><small>Đã sẵn sàng</small></div>
        <i />
        <div><span>3</span><strong>Kết nối MySQL</strong><small>Đã sẵn sàng</small></div>
      </section>
    </div>
  )
}
