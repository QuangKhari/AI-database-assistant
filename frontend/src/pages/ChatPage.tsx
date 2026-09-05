import { useCallback, useEffect, useRef, useState } from 'react'
import { chatApi } from '../api/chatApi'
import { ApiError } from '../api/client'
import { connectionApi } from '../api/connectionApi'
import { queryApi } from '../api/queryApi'
import type { ChatMessage, Conversation, DatabaseConnection, QueryExecutionResponse } from '../api/types'
import { useToast } from '../context/ToastContext'
import styles from './ChatPage.module.css'

function displayCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function ExecutionResult({ execution }: { execution: QueryExecutionResponse }) {
  if (execution.status !== 'SUCCESS') {
    return (
      <div className={styles.executionError} role="alert">
        <strong>{execution.status === 'TIMEOUT' ? 'Query đã hết thời gian' : 'Không thể chạy query'}</strong>
        <span>{execution.result.error}</span>
      </div>
    )
  }

  return (
    <section className={styles.result} aria-label="Kết quả query">
      <header>
        <strong>{execution.result.rowCount} dòng</strong>
        <span>{execution.result.executionTimeMs} ms</span>
        {execution.result.truncated && <em>Chỉ hiển thị 500 dòng đầu</em>}
      </header>
      {execution.result.columns.length === 0 ? (
        <p>Query không trả về cột dữ liệu.</p>
      ) : (
        <div className={styles.resultTableWrap}>
          <table>
            <thead><tr>{execution.result.columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
            <tbody>
              {execution.result.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {execution.result.columns.map((column) => (
                    <td className={row[column] == null ? styles.nullCell : ''} key={column}>
                      {displayCell(row[column])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

export function ChatPage() {
  const { showToast } = useToast()
  const [connections, setConnections] = useState<DatabaseConnection[]>([])
  const [connectionId, setConnectionId] = useState<number | null>(null)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [question, setQuestion] = useState('')
  const [timeoutSeconds, setTimeoutSeconds] = useState(20)
  const [executions, setExecutions] = useState<Record<number, QueryExecutionResponse>>({})
  const [runningMessageId, setRunningMessageId] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter((item) => item.active)
        setConnections(available)
        setConnectionId(available[0]?.id ?? null)
      } catch (reason) {
        setError(reason instanceof ApiError ? reason.message : 'Không tải được connections.')
      } finally { setLoading(false) }
    }
    void loadConnections()
  }, [])

  const loadConversations = useCallback(async (selectedConnectionId: number) => {
    try {
      setConversations(await chatApi.conversations(selectedConnectionId))
      setError('')
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không tải được conversations.')
    }
  }, [])

  useEffect(() => {
    setConversationId(null)
    setMessages([])
    setExecutions({})
    if (connectionId !== null) void loadConversations(connectionId)
  }, [connectionId, loadConversations])

  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === 'function') {
      endRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  async function openConversation(id: number) {
    setConversationId(id)
    setExecutions({})
    setLoading(true)
    try {
      setMessages(await chatApi.messages(id))
      setError('')
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Không tải được nội dung hội thoại.')
    } finally { setLoading(false) }
  }

  async function sendQuestion(event: React.FormEvent) {
    event.preventDefault()
    const cleanQuestion = question.trim()
    if (!cleanQuestion || connectionId === null) return
    setSending(true)
    try {
      const result = await chatApi.preview({
        connectionId,
        ...(conversationId ? { conversationId } : {}),
        question: cleanQuestion,
      })
      setQuestion('')
      setConversationId(result.conversationId)
      setMessages(await chatApi.messages(result.conversationId))
      await loadConversations(connectionId)
      if (!result.valid) showToast(result.validationError || 'SQL chưa vượt qua kiểm tra an toàn.', 'error')
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : 'Không thể tạo SQL preview.', 'error')
    } finally { setSending(false) }
  }

  async function runQuery(message: ChatMessage) {
    setRunningMessageId(message.id)
    try {
      const execution = await queryApi.execute({
        assistantMessageId: message.id,
        timeoutSeconds,
      })
      setExecutions((current) => ({ ...current, [message.id]: execution }))
      if (execution.status === 'SUCCESS') {
        showToast(`Query hoàn tất với ${execution.result.rowCount} dòng.`, 'success')
      } else {
        showToast(execution.result.error || 'Không thể chạy query.', 'error')
      }
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : 'Không thể chạy query.', 'error')
    } finally { setRunningMessageId(null) }
  }

  async function removeConversation(item: Conversation) {
    if (!window.confirm(`Xóa cuộc trò chuyện “${item.title}”?`)) return
    try {
      await chatApi.removeConversation(item.id)
      if (conversationId === item.id) { setConversationId(null); setMessages([]); setExecutions({}) }
      if (connectionId !== null) await loadConversations(connectionId)
      showToast('Đã xóa cuộc trò chuyện.', 'success')
    } catch (reason) {
      showToast(reason instanceof ApiError ? reason.message : 'Không thể xóa cuộc trò chuyện.', 'error')
    }
  }

  if (!loading && connections.length === 0) {
    return <section className={styles.emptyPage}><h1>Chưa có connection hoạt động</h1><p>Hãy tạo connection và đồng bộ schema trước khi Chat với AI.</p></section>
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div><p>Natural language to SQL</p><h1>Chat với database</h1><span>AI tạo SQL an toàn để bạn kiểm tra trước, sau đó bạn quyết định có chạy hay không.</span></div>
        <label>Database
          <select value={connectionId ?? ''} onChange={(event) => setConnectionId(Number(event.target.value))}>
            {connections.map((connection) => <option key={connection.id} value={connection.id}>{connection.name} — {connection.databaseName}</option>)}
          </select>
        </label>
      </header>

      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.workspace}>
        <aside className={styles.sidebar}>
          <button type="button" onClick={() => { setConversationId(null); setMessages([]); setExecutions({}) }}>+ Cuộc trò chuyện mới</button>
          <h2>Gần đây</h2>
          <div className={styles.conversationList}>
            {conversations.length === 0 && <small>Chưa có cuộc trò chuyện.</small>}
            {conversations.map((item) => (
              <div className={item.id === conversationId ? styles.selected : ''} key={item.id}>
                <button type="button" onClick={() => void openConversation(item.id)}><strong>{item.title}</strong><span>{new Date(item.updatedAt).toLocaleString('vi-VN')}</span></button>
                <button type="button" aria-label={`Xóa ${item.title}`} onClick={() => void removeConversation(item)}>×</button>
              </div>
            ))}
          </div>
        </aside>

        <section className={styles.chatPanel}>
          <div className={styles.messages} aria-live="polite">
            {loading ? <p className={styles.center}>Đang tải…</p> : messages.length === 0 ? (
              <div className={styles.welcome}><span>AI</span><h2>Bạn muốn tìm dữ liệu gì?</h2><p>Ví dụ: “Liệt kê 10 khách hàng có tổng đơn hàng cao nhất.”</p><small>AI chỉ dùng schema của connection đang chọn và nhớ tối đa 3 lượt gần nhất.</small></div>
            ) : messages.map((message) => {
              const execution = executions[message.id]
              const latestLog = message.queryLogs.at(-1)
              const canRun = Boolean(message.generatedSql) && message.generatedSqlValid !== false
              return (
                <article className={message.role === 'user' ? styles.userMessage : styles.assistantMessage} key={message.id}>
                  <header>{message.role === 'user' ? 'Bạn' : 'AI QueryMate'}</header>
                  <p>{message.content}</p>
                  {message.generatedSql && <div className={styles.sql}>
                    <div className={styles.sqlHeader}>
                      <span>SQL preview</span>
                      <small>{execution ? (execution.status === 'SUCCESS' ? 'Đã chạy' : execution.status) : latestLog ? `Lần gần nhất: ${latestLog.status}` : 'Chưa thực thi'}</small>
                    </div>
                    <pre><code>{message.generatedSql}</code></pre>
                    <div className={styles.sqlActions}>
                      <label>Timeout
                        <select aria-label="Query timeout" value={timeoutSeconds} onChange={(event) => setTimeoutSeconds(Number(event.target.value))} disabled={runningMessageId !== null}>
                          <option value={20}>20 giây</option>
                          <option value={25}>25 giây</option>
                          <option value={30}>30 giây</option>
                        </select>
                      </label>
                      <button type="button" onClick={() => void runQuery(message)} disabled={!canRun || runningMessageId !== null}>
                        {runningMessageId === message.id ? 'Đang chạy…' : canRun ? 'Run query' : 'SQL không an toàn'}
                      </button>
                    </div>
                    {execution && <ExecutionResult execution={execution} />}
                  </div>}
                </article>
              )
            })}
            <div ref={endRef} />
          </div>
          <form className={styles.composer} onSubmit={sendQuestion}>
            <textarea value={question} onChange={(event) => setQuestion(event.target.value)} maxLength={2000} placeholder="Hỏi bằng tiếng Việt hoặc tiếng Anh…" rows={3} disabled={sending || connectionId === null} />
            <div><small>{question.length}/2000 · Enter xuống dòng</small><button type="submit" disabled={sending || !question.trim()}>{sending ? 'AI đang tạo SQL…' : 'Tạo SQL preview'}</button></div>
          </form>
        </section>
      </div>
    </div>
  )
}
