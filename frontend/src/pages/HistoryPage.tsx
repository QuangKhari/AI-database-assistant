import { type FormEvent, useCallback, useEffect, useState } from "react";
import { historyApi } from "../api/historyApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type {
  ChatMessage,
  Conversation,
  HistorySearchResult,
} from "../api/types";
import { useToast } from "../context/ToastContext";
import styles from "./HistoryPage.module.css";

type Tab = "all" | "pinned" | "search";

export function HistoryPage() {
  const { showToast } = useToast();
  const [tab, setTab] = useState<Tab>("all");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pinnedMessages, setPinnedMessages] = useState<ChatMessage[]>([]);
  const [searchInput, setSearchInput] = useState("");
  const [searchResults, setSearchResults] = useState<HistorySearchResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const loadConversations = useCallback(async () => {
    setLoading(true);
    try {
      setConversations(await historyApi.list());
      setError("");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không tải được lịch sử."),
        ),
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadConversations();
  }, [loadConversations]);

  async function openConversation(id: number) {
    setSelectedId(id);
    setLoading(true);
    try {
      setMessages(await historyApi.detail(id));
      setError("");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không tải được nội dung."),
        ),
      );
    } finally {
      setLoading(false);
    }
  }

  async function loadPinned() {
    setLoading(true);
    try {
      setPinnedMessages(await historyApi.pinned());
      setError("");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không tải được mục đã ghim."),
        ),
      );
    } finally {
      setLoading(false);
    }
  }

  async function submitSearch(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    try {
      const page = await historyApi.search({ keyword: searchInput.trim() });
      setSearchResults(page.content);
      setError("");
    } catch (reason) {
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể tìm kiếm."),
        ),
      );
    } finally {
      setLoading(false);
    }
  }

  function switchTab(next: Tab) {
    setTab(next);
    if (next === "pinned") void loadPinned();
  }

  async function togglePin(messageId: number) {
    try {
      const updated = await historyApi.togglePin(messageId);
      setMessages((current) =>
        current.map((m) => (m.id === messageId ? updated : m)),
      );
      if (tab === "pinned") await loadPinned();
      showToast(
        updated.pinned ? "Đã ghim tin nhắn." : "Đã bỏ ghim.",
        "success",
      );
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể ghim tin nhắn."),
        ),
        "error",
      );
    }
  }

  async function removeConversation(item: Conversation) {
    if (!window.confirm(`Xóa vĩnh viễn cuộc trò chuyện "${item.title}"?`))
      return;
    try {
      await historyApi.remove(item.id);
      if (selectedId === item.id) {
        setSelectedId(null);
        setMessages([]);
      }
      await loadConversations();
      showToast("Đã xóa cuộc trò chuyện.", "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(parseApiError(reason, "Không thể xóa.")),
        "error",
      );
    }
  }

  async function removeAll() {
    if (
      !window.confirm(
        "Xóa TOÀN BỘ lịch sử trò chuyện? Hành động này không thể hoàn tác.",
      )
    )
      return;
    try {
      await historyApi.removeAll();
      setSelectedId(null);
      setMessages([]);
      await loadConversations();
      showToast("Đã xóa toàn bộ lịch sử.", "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể xóa lịch sử."),
        ),
        "error",
      );
    }
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div>
          <p>Query history</p>
          <h1>Lịch sử trò chuyện</h1>
          <span>Xem lại, tìm kiếm và ghim các câu hỏi/kết quả trước đây.</span>
        </div>
        <button
          type="button"
          className={styles.dangerButton}
          onClick={() => void removeAll()}
        >
          Xóa toàn bộ
        </button>
      </header>

      <nav className={styles.tabs}>
        <button
          type="button"
          className={tab === "all" ? styles.tabActive : ""}
          onClick={() => switchTab("all")}
        >
          Tất cả
        </button>
        <button
          type="button"
          className={tab === "pinned" ? styles.tabActive : ""}
          onClick={() => switchTab("pinned")}
        >
          Đã ghim
        </button>
        <button
          type="button"
          className={tab === "search" ? styles.tabActive : ""}
          onClick={() => switchTab("search")}
        >
          Tìm kiếm
        </button>
      </nav>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      {tab === "all" && (
        <div className={styles.content}>
          <aside className={styles.list}>
            {loading && conversations.length === 0 ? (
              <p className={styles.center}>Đang tải…</p>
            ) : conversations.length === 0 ? (
              <p className={styles.center}>Chưa có cuộc trò chuyện nào.</p>
            ) : (
              conversations.map((item) => (
                <div
                  className={
                    item.id === selectedId ? styles.itemSelected : styles.item
                  }
                  key={item.id}
                >
                  <button
                    type="button"
                    onClick={() => void openConversation(item.id)}
                  >
                    <strong>{item.title}</strong>
                    <small>
                      {new Date(item.updatedAt).toLocaleString("vi-VN")}
                    </small>
                  </button>
                  <button
                    type="button"
                    aria-label={`Xóa ${item.title}`}
                    onClick={() => void removeConversation(item)}
                  >
                    ×
                  </button>
                </div>
              ))
            )}
          </aside>
          <section className={styles.detail}>
            {selectedId === null ? (
              <p className={styles.center}>
                Chọn một cuộc trò chuyện để xem chi tiết.
              </p>
            ) : (
              messages.map((message) => (
                <article
                  className={
                    message.role === "user"
                      ? styles.userMessage
                      : styles.assistantMessage
                  }
                  key={message.id}
                >
                  <header>
                    <span>
                      {message.role === "user" ? "Bạn" : "AI QueryMate"}
                    </span>
                    <button
                      type="button"
                      onClick={() => void togglePin(message.id)}
                    >
                      {message.pinned ? "★ Đã ghim" : "☆ Ghim"}
                    </button>
                  </header>
                  <p>{message.content}</p>
                  {message.generatedSql && (
                    <pre>
                      <code>{message.generatedSql}</code>
                    </pre>
                  )}
                </article>
              ))
            )}
          </section>
        </div>
      )}

      {tab === "pinned" && (
        <div className={styles.pinnedList}>
          {loading ? (
            <p className={styles.center}>Đang tải…</p>
          ) : pinnedMessages.length === 0 ? (
            <p className={styles.center}>Chưa ghim tin nhắn nào.</p>
          ) : (
            pinnedMessages.map((message) => (
              <article className={styles.pinnedCard} key={message.id}>
                <header>
                  <span>
                    {new Date(message.createdAt).toLocaleString("vi-VN")}
                  </span>
                  <button
                    type="button"
                    onClick={() => void togglePin(message.id)}
                  >
                    Bỏ ghim
                  </button>
                </header>
                <p>{message.content}</p>
                {message.generatedSql && (
                  <pre>
                    <code>{message.generatedSql}</code>
                  </pre>
                )}
              </article>
            ))
          )}
        </div>
      )}

      {tab === "search" && (
        <div>
          <form className={styles.searchForm} onSubmit={submitSearch}>
            <input
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              placeholder="Tìm theo nội dung câu hỏi hoặc SQL…"
            />
            <button type="submit">Tìm</button>
          </form>
          <div className={styles.pinnedList}>
            {searchResults.length === 0 ? (
              <p className={styles.center}>
                Chưa có kết quả. Nhập từ khóa và nhấn Tìm.
              </p>
            ) : (
              searchResults.map((result) => (
                <article className={styles.pinnedCard} key={result.messageId}>
                  <header>
                    <span>{result.conversationTitle}</span>
                    <span>
                      {new Date(result.createdAt).toLocaleString("vi-VN")}
                    </span>
                  </header>
                  <p>{result.content}</p>
                  {result.generatedSql && (
                    <pre>
                      <code>{result.generatedSql}</code>
                    </pre>
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      setTab("all");
                      void openConversation(result.conversationId);
                    }}
                  >
                    Mở cuộc trò chuyện →
                  </button>
                </article>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
