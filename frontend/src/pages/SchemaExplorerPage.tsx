import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError } from '../api/client'
import { connectionApi } from '../api/connectionApi'
import { schemaApi } from '../api/schemaApi'
import type { DatabaseConnection, DatabaseSchema } from '../api/types'
import { useToast } from '../context/ToastContext'
import styles from './SchemaExplorerPage.module.css'

export function SchemaExplorerPage() {
  const { showToast } = useToast()
  const [connections, setConnections] = useState<DatabaseConnection[]>([])
  const [connectionId, setConnectionId] = useState<number | null>(null)
  const [schema, setSchema] = useState<DatabaseSchema | null>(null)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter((item) => item.active)
        setConnections(available)
        setConnectionId(available[0]?.id ?? null)
      } catch (reason) {
        setError(reason instanceof ApiError ? reason.message : 'Không tải được connections.')
      } finally {
        setLoading(false)
      }
    }
    void loadConnections()
  }, [])

  const loadSchema = useCallback(async (selectedId: number) => {
    setLoading(true)
    try {
      setSchema(await schemaApi.get(selectedId))
      setError('')
    } catch (reason) {
      setSchema(null)
      setError(reason instanceof ApiError ? reason.message : 'Không tải được schema.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (connectionId !== null) void loadSchema(connectionId)
  }, [connectionId, loadSchema])

  async function syncSchema() {
    if (connectionId === null) return
    setSyncing(true)
    try {
      const result = await schemaApi.sync(connectionId)
      setSchema(result)
      setError('')
      showToast(`Đã đồng bộ ${result.tables.length} bảng.`, 'success')
    } catch (reason) {
      const message = reason instanceof ApiError ? reason.message : 'Không thể đồng bộ schema.'
      setError(message)
      showToast(message, 'error')
    } finally {
      setSyncing(false)
    }
  }

  const visibleTables = useMemo(() => {
    const keyword = search.trim().toLowerCase()
    if (!keyword) return schema?.tables ?? []
    return (schema?.tables ?? []).filter((table) =>
      table.name.toLowerCase().includes(keyword)
      || table.columns.some((column) => column.name.toLowerCase().includes(keyword)))
  }, [schema, search])

  if (!loading && connections.length === 0) {
    return <section className={styles.empty}><h1>Chưa có connection hoạt động</h1><p>Hãy thêm và kiểm tra một Target MySQL trước khi đồng bộ schema.</p></section>
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div><p>Database metadata</p><h1>Schema Explorer</h1><span>Xem cấu trúc bảng, cột, khóa chính và khóa ngoại.</span></div>
        <button type="button" onClick={syncSchema} disabled={syncing || connectionId === null}>
          {syncing ? 'Đang đồng bộ…' : 'Đồng bộ schema'}
        </button>
      </header>

      <section className={styles.toolbar}>
        <label>Connection
          <select value={connectionId ?? ''} onChange={(event) => setConnectionId(Number(event.target.value))}>
            {connections.map((connection) => <option key={connection.id} value={connection.id}>{connection.name} — {connection.databaseName}</option>)}
          </select>
        </label>
        <label>Tìm bảng hoặc cột
          <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Ví dụ: orders, customer_id" />
        </label>
        <div className={styles.syncInfo}><span>Lần đồng bộ gần nhất</span><strong>{schema?.lastSyncedAt ? new Date(schema.lastSyncedAt).toLocaleString('vi-VN') : 'Chưa có'}</strong></div>
      </section>

      {error && <div className={styles.error} role="alert">{error}</div>}
      {loading ? <p className={styles.loading}>Đang đọc schema…</p> : !schema ? (
        <section className={styles.empty}><h2>Schema chưa được đồng bộ</h2><p>Nhấn “Đồng bộ schema” để đọc metadata từ database. Dữ liệu cũ chỉ bị thay thế khi quá trình thành công.</p></section>
      ) : (
        <div className={styles.content}>
          <aside><strong>{visibleTables.length}</strong><span>/ {schema.tables.length} bảng</span><small>{schema.databaseName}</small></aside>
          <div className={styles.tableList}>
            {visibleTables.length === 0 ? <p className={styles.noResult}>Không tìm thấy bảng hoặc cột phù hợp.</p> : visibleTables.map((table) => (
              <details className={styles.tableCard} key={table.id} open={visibleTables.length <= 4}>
                <summary><div><strong>{table.name}</strong><span>{table.columns.length} cột</span></div><small>{table.description || 'Chưa có mô tả'}</small></summary>
                <div className={styles.columns}>
                  <div className={styles.columnHeader}><span>Tên cột</span><span>Kiểu dữ liệu</span><span>Ràng buộc</span></div>
                  {table.columns.map((column) => (
                    <div className={styles.column} key={column.id}>
                      <span>{column.name}<small>{column.description}</small></span>
                      <code>{column.dataType}</code>
                      <span className={styles.badges}>
                        {column.primaryKey && <i>PK</i>}{column.foreignKey && <i>FK</i>}
                        {column.nullable && <i className={styles.muted}>NULL</i>}
                        {column.foreignKey && <small>→ {column.referencedTable}.{column.referencedColumn}</small>}
                      </span>
                    </div>
                  ))}
                </div>
              </details>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
