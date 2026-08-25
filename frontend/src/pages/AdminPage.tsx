import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { adminApi } from '../api/adminApi'
import { ApiError } from '../api/client'
import type { AdminStats, AdminUserPage } from '../api/types'
import { useToast } from '../context/ToastContext'
import styles from './AdminPage.module.css'

export function AdminPage() {
  const { showToast } = useToast()
  const [stats, setStats] = useState<AdminStats | null>(null)
  const [users, setUsers] = useState<AdminUserPage | null>(null)
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [workingId, setWorkingId] = useState<number | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextStats, nextUsers] = await Promise.all([adminApi.stats(), adminApi.users(search, page)])
      setStats(nextStats)
      setUsers(nextUsers)
      setError('')
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không tải được dữ liệu quản trị.')
    } finally { setLoading(false) }
  }, [page, search])

  useEffect(() => { void load() }, [load])

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setPage(0)
    setSearch(searchInput.trim())
  }

  async function toggleLock(userId: number, username: string, locked: boolean) {
    const verb = locked ? 'mở khóa' : 'khóa'
    if (!window.confirm(`Bạn chắc chắn muốn ${verb} tài khoản “${username}”?`)) return
    setWorkingId(userId)
    try {
      await (locked ? adminApi.unlock(userId) : adminApi.lock(userId))
      showToast(`Đã ${verb} tài khoản ${username}.`, 'success')
      await load()
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : `Không thể ${verb} tài khoản.`, 'error')
    } finally { setWorkingId(null) }
  }

  return (
    <div className={styles.page}>
      <header><div><p>Administration</p><h1>Quản trị hệ thống</h1><span>Theo dõi tổng quan và khóa/mở khóa tài khoản người dùng.</span></div><i>ADMIN</i></header>

      <div className={styles.stats}>
        <article><span>Tổng người dùng</span><strong>{stats?.totalUsers ?? '—'}</strong></article>
        <article><span>Đang hoạt động</span><strong>{stats?.activeUsers ?? '—'}</strong></article>
        <article><span>Đã khóa</span><strong>{stats?.lockedUsers ?? '—'}</strong></article>
        <article><span>Connections hoạt động</span><strong>{stats?.activeConnections ?? '—'}</strong></article>
      </div>

      <section className={styles.users}>
        <div className={styles.toolbar}><div><h2>Tài khoản người dùng</h2><p>Admin không thể xem mật khẩu database hoặc dữ liệu truy vấn.</p></div>
          <form onSubmit={submitSearch}><input aria-label="Tìm user" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} placeholder="Username, email hoặc tên…" /><button type="submit">Tìm</button></form>
        </div>
        {error && <div className={styles.error} role="alert">{error}</div>}
        {loading ? <p className={styles.loading}>Đang tải danh sách…</p> : users?.content.length === 0 ? <p className={styles.empty}>Không tìm thấy tài khoản phù hợp.</p> : (
          <div className={styles.tableWrap}><table><thead><tr><th>Người dùng</th><th>Trạng thái</th><th>Connections</th><th>Ngày đăng ký</th><th aria-label="Thao tác" /></tr></thead>
            <tbody>{users?.content.map((user) => <tr key={user.id}>
              <td><strong>{user.displayName || user.username}</strong><span>@{user.username} · {user.email}</span></td>
              <td><b className={user.locked ? styles.locked : styles.active}>{user.locked ? 'Đã khóa' : user.enabled ? 'Hoạt động' : 'Vô hiệu hóa'}</b></td>
              <td>{user.connectionCount}</td>
              <td>{new Date(user.createdAt).toLocaleDateString('vi-VN')}</td>
              <td><button type="button" disabled={workingId === user.id} className={user.locked ? styles.unlock : styles.lock} onClick={() => toggleLock(user.id, user.username, user.locked)}>{workingId === user.id ? 'Đang xử lý…' : user.locked ? 'Mở khóa' : 'Khóa'}</button></td>
            </tr>)}</tbody></table></div>
        )}
        {users && users.totalPages > 1 && <div className={styles.pagination}><span>Trang {users.page + 1}/{users.totalPages} · {users.totalElements} users</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>Trước</button><button disabled={page + 1 >= users.totalPages || loading} onClick={() => setPage((value) => value + 1)}>Sau</button></div></div>}
      </section>
    </div>
  )
}
