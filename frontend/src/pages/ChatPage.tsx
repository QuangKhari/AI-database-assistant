import { useCallback, useEffect, useRef, useState } from 'react'
import { chatApi } from '../api/chatApi'
import { ApiError } from '../api/client'
import { connectionApi } from '../api/connectionApi'
import type { ChatMessage, Conversation, DatabaseConnection } from '../api/types'
import { useToast } from '../context/ToastContext'
import styles from './ChatPage.module.css'

export function ChatPage() {
  const { showToast } = useToast()
  const [connections, setConnections] = useState<DatabaseConnection[]>([])
  const [connectionId, setConnectionId] = useState<number | null>(null)
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [conversationId, setConversationId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [question, setQuestion] = useState('')
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
    if (connectionId !== null) void loadConversations(connectionId)
  }, [connectionId, loadConversations])

  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === 'function') {
      endRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages])

  async function openConversation(id: number) {
    setConversationId(id)
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

  async function removeConversation(item: Conversation) {
    if (!window.confirm(`Xóa cuộc trò chuyện “${item.title}”?`)) return
    try {
      await chatApi.removeConversation(item.id)
      if (conversationId === item.id) { setConversationId(null); setMessages([]) }
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
        <div><p>Natural language to SQL</p><h1>Chat với database</h1><span>AI tạo SQL preview từ schema đã đồng bộ. Chưa thực thi truy vấn ở phần này.</span></div>
        <label>Database
          <select value={connectionId ?? ''} onChange={(event) => setConnectionId(Number(event.target.value))}>
            {connections.map((connection) => <option key={connection.id} value={connection.id}>{connection.name} — {connection.databaseName}</option>)}
          </select>
        </label>
      </header>

      {error && <div className={styles.error} role="alert">{error}</div>}
      <div className={styles.workspace}>
        <aside className={styles.sidebar}>
          <button type="button" onClick={() => { setConversationId(null); setMessages([]) }}>+ Cuộc trò chuyện mới</button>
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
            ) : messages.map((message) => (
              <article className={message.role === 'user' ? styles.userMessage : styles.assistantMessage} key={message.id}>
                <header>{message.role === 'user' ? 'Bạn' : 'AI QueryMate'}</header>
                <p>{message.content}</p>
                {message.generatedSql && <div className={styles.sql}><div><span>SQL preview</span><small>Chưa thực thi</small></div><pre><code>{message.generatedSql}</code></pre></div>}
              </article>
            ))}
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
