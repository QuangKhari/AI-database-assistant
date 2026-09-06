import { Outlet } from 'react-router-dom'
import styles from './AuthLayout.module.css'

export function AuthLayout() {
  return (
    <main className={styles.page}>
      <section className={styles.brandPanel}>
        <div className={styles.brand}>
          <div className={styles.logo}>AI</div>
          <span>QueryMate</span>
        </div>
        <div className={styles.pitch}>
          <p className={styles.eyebrow}>AI Database Assistant</p>
          <h1>Hiểu dữ liệu của bạn bằng ngôn ngữ tự nhiên.</h1>
          <p>Kết nối MySQL an toàn, đặt câu hỏi và nhận câu lệnh SQL có thể kiểm tra trước khi chạy.</p>
        </div>
        <p className={styles.note}>Thông tin kết nối thuộc riêng từng tài khoản và được mã hóa.</p>
      </section>
      <section className={styles.formPanel}>
        <div className={styles.mobileBrand}><span>AI</span> QueryMate</div>
        <Outlet />
      </section>
    </main>
  )
}
