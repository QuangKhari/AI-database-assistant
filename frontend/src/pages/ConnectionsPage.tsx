import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { connectionApi } from '../api/connectionApi'
import { ApiError } from '../api/client'
import type { DatabaseConnection } from '../api/types'
import { useToast } from '../context/ToastContext'
import styles from './ConnectionsPage.module.css'

export function ConnectionsPage() {
  const { showToast } = useToast()
  const [connections, setConnections] = useState<DatabaseConnection[]>([])
  const [loading, setLoading] = useState(true)
  const [workingId, setWorkingId] = useState<number | null>(null)
  const [error, setError] = useState('')

  const loadConnections = useCallback(async () => {
    try {
      setConnections(await connectionApi.list())
      setError('')
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không tải được danh sách connection.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadConnections() }, [loadConnections])

  async function reconnect(connection: DatabaseConnection) {
    setWorkingId(connection.id)
    try {
      const result = await connectionApi.reconnect(connection.id)
      showToast(result.message, result.successful ? 'success' : 'error')
      await loadConnections()
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : 'Không thể kiểm tra connection.', 'error')
    } finally { setWorkingId(null) }
  }

  async function disconnect(connection: DatabaseConnection) {
    if (!window.confirm(`Ngắt connection “${connection.name}”? Cấu hình và lịch sử vẫn được giữ lại.`)) return
    setWorkingId(connection.id)
    try {
      await connectionApi.disconnect(connection.id)
      showToast('Đã ngắt connection.', 'success')
      await loadConnections()
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : 'Không thể ngắt connection.', 'error')
    } finally { setWorkingId(null) }
  }

  const activeCount = connections.filter((item) => item.active).length

  return (
    <div className={styles.page}>
      <div className={styles.heading}>
        <div><p>Target database</p><h1>Kết nối MySQL</h1><span>Mỗi tài khoản có tối đa 5 connection đang hoạt động.</span></div>
        <Link className={styles.addButton} to="/connections/new">+ Thêm connection</Link>
      </div>

      <div className={styles.summary}>
        <div><strong>{connections.length}</strong><span>Tổng cấu hình</span></div>
        <div><strong>{activeCount}</strong><span>Đang hoạt động</span></div>
        <div><strong>{5 - activeCount}</strong><span>Còn có thể thêm</span></div>
      </div>

      {error && <div className={styles.error} role="alert">{error}</div>}
      {loading ? <p className={styles.loading}>Đang tải connections…</p> : connections.length === 0 ? (
        <section className={styles.empty}>
          <div>DB</div><h2>Chưa có database nào</h2>
          <p>Thêm Target MySQL bằng tài khoản chỉ có quyền đọc để bắt đầu.</p>
          <Link to="/connections/new">Tạo connection đầu tiên</Link>
        </section>
      ) : (
        <div className={styles.grid}>
          {connections.map((connection) => (
            <article className={styles.card} key={connection.id}>
              <div className={styles.cardTop}>
                <div className={styles.dbIcon}>MY</div>
                <span className={connection.active ? styles.active : styles.inactive}>
                  <i />{connection.active ? 'Đang hoạt động' : 'Đã ngắt'}
                </span>
              </div>
              <h2>{connection.name}</h2>
              <p className={styles.endpoint}>{connection.host}:{connection.port}</p>
              <dl>
                <div><dt>Database</dt><dd>{connection.databaseName}</dd></div>
                <div><dt>MySQL user</dt><dd>{connection.username}</dd></div>
                <div><dt>Kiểm tra gần nhất</dt><dd>{connection.lastTestedAt ? new Date(connection.lastTestedAt).toLocaleString('vi-VN') : 'Chưa có'}</dd></div>
              </dl>
              <div className={styles.actions}>
                <button type="button" disabled={workingId === connection.id} onClick={() => reconnect(connection)}>
                  {workingId === connection.id ? 'Đang kiểm tra…' : connection.active ? 'Kiểm tra lại' : 'Kết nối lại'}
                </button>
                <Link to={`/connections/${connection.id}/edit`}>Chỉnh sửa</Link>
                {connection.active && <button className={styles.disconnect} type="button" onClick={() => disconnect(connection)}>Ngắt</button>}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}
