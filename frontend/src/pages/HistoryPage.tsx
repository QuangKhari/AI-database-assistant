import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { ChevronLeft, ChevronRight, Clock3, History, MessageSquareText, Search, Star, Trash2, X } from "lucide-react";
import { historyApi } from "../api/historyApi";
import type { ChatMessage, Conversation, HistorySearchResult } from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";
import styles from "./HistoryPage.module.css";

type Tab = "all" | "pinned" | "search";
type DeleteTarget = { type: "one"; conversation: Conversation } | { type: "all" } | null;
const HISTORY_PAGE_SIZE = 8;
const SEARCH_PAGE_SIZE = 10;

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Không rõ thời gian"
    : date.toLocaleString("vi-VN", { dateStyle: "short", timeStyle: "short" });
}

export function HistoryPage() {
  const { showToast } = useToast();
  const { error, handleError, setError } = useApiError();
  const [tab, setTab] = useState<Tab>("all");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pinnedMessages, setPinnedMessages] = useState<ChatMessage[]>([]);
  const [searchInput, setSearchInput] = useState("");
  const [searchResults, setSearchResults] = useState<HistorySearchResult[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [searchPage, setSearchPage] = useState(0);
  const [searchTotalPages, setSearchTotalPages] = useState(0);
  const [searchTotalElements, setSearchTotalElements] = useState(0);
  const [hasSearched, setHasSearched] = useState(false);
  const [listLoading, setListLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [pinnedLoading, setPinnedLoading] = useState(false);
  const [searchLoading, setSearchLoading] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget>(null);
  const [deleting, setDeleting] = useState(false);

  const historyTotalPages = Math.max(1, Math.ceil(conversations.length / HISTORY_PAGE_SIZE));
  const visibleConversations = useMemo(
    () => conversations.slice(historyPage * HISTORY_PAGE_SIZE, (historyPage + 1) * HISTORY_PAGE_SIZE),
    [conversations, historyPage],
  );

  const loadConversations = useCallback(async () => {
    setListLoading(true);
    try {
      const result = await historyApi.list();
      setConversations(result);
      setHistoryPage((current) => Math.min(current, Math.max(0, Math.ceil(result.length / HISTORY_PAGE_SIZE) - 1)));
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được lịch sử trò chuyện.");
    } finally {
      setListLoading(false);
    }
  }, [handleError, setError]);

  useEffect(() => { void loadConversations(); }, [loadConversations]);

  async function openConversation(id: number) {
    setSelectedId(id);
    setMessages([]);
    setDetailLoading(true);
    try {
      setMessages(await historyApi.detail(id));
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được nội dung cuộc trò chuyện.");
    } finally {
      setDetailLoading(false);
    }
  }

  async function loadPinned() {
    setPinnedLoading(true);
    try {
      setPinnedMessages(await historyApi.pinned());
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được các tin nhắn đã ghim.");
    } finally {
      setPinnedLoading(false);
    }
  }

  async function runSearch(page: number) {
    setSearchLoading(true);
    setHasSearched(true);
    try {
      const result = await historyApi.search({ keyword: searchInput.trim(), page, size: SEARCH_PAGE_SIZE });
      setSearchResults(result.content);
      setSearchPage(result.number);
      setSearchTotalPages(result.totalPages);
      setSearchTotalElements(result.totalElements);
      setError("");
    } catch (reason) {
      handleError(reason, "Không thể tìm kiếm lịch sử.");
    } finally {
      setSearchLoading(false);
    }
  }

  function submitSearch(event: FormEvent) { event.preventDefault(); void runSearch(0); }
  function switchTab(next: Tab) { setTab(next); setError(""); if (next === "pinned") void loadPinned(); }

  async function togglePin(messageId: number) {
    try {
      const updated = await historyApi.togglePin(messageId);
      setMessages((current) => current.map((message) => message.id === messageId ? updated : message));
      setSearchResults((current) => current.map((result) => result.messageId === messageId ? { ...result, pinned: updated.pinned } : result));
      if (tab === "pinned") await loadPinned();
      showToast(updated.pinned ? "Đã ghim tin nhắn." : "Đã bỏ ghim.", "success");
    } catch (reason) {
      handleError(reason, "Không thể thay đổi trạng thái ghim.");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      if (deleteTarget.type === "all") {
        await historyApi.removeAll();
        setSelectedId(null); setMessages([]); setPinnedMessages([]); setSearchResults([]); setHasSearched(false);
        showToast("Đã xóa toàn bộ lịch sử.", "success");
      } else {
        await historyApi.remove(deleteTarget.conversation.id);
        if (selectedId === deleteTarget.conversation.id) { setSelectedId(null); setMessages([]); }
        showToast("Đã xóa cuộc trò chuyện.", "success");
      }
      setDeleteTarget(null);
      await loadConversations();
    } catch (reason) {
      handleError(reason, "Không thể xóa lịch sử.");
    } finally { setDeleting(false); }
  }

  function renderMessage(message: ChatMessage) {
    const queryLogs = message.queryLogs ?? [];
    const successful = queryLogs.some((entry) => entry.status === "SUCCESS");
    return (
      <article className={message.role === "user" ? styles.userMessage : styles.assistantMessage} key={message.id}>
        <header className={styles.messageHeader}>
          <div><span>{message.role === "user" ? "Bạn" : "AI QueryMate"}</span><time>{formatDate(message.createdAt)}</time></div>
          <button type="button" className={message.pinned ? styles.pinActive : styles.pinButton} onClick={() => void togglePin(message.id)}>
            <Star size={15} fill={message.pinned ? "currentColor" : "none"} />{message.pinned ? "Đã ghim" : "Ghim"}
          </button>
        </header>
        <p className={styles.messageContent}>{message.content}</p>
        {message.generatedSql && <div className={styles.sqlBlock}><span>SQL đã tạo</span><pre><code>{message.generatedSql}</code></pre></div>}
        {queryLogs.length > 0 && (
          <details className={styles.executionDetails}>
            <summary><span>Chi tiết thực thi</span><small>{successful ? "Thành công" : "Có lỗi"} · {queryLogs.length} lần</small></summary>
            <div className={styles.attemptList}>
              {queryLogs.map((entry) => (
                <div className={styles.attempt} key={`${message.id}-${entry.attemptNumber}`}>
                  <div><strong>Lần {entry.attemptNumber}</strong><span className={entry.status === "SUCCESS" ? styles.successBadge : styles.failedBadge}>{entry.status === "SUCCESS" ? "Thành công" : "Thất bại"}</span></div>
                  <small>{entry.rowCount ?? 0} dòng{entry.executionTimeMs !== null ? ` · ${entry.executionTimeMs} ms` : ""}</small>
                  {entry.errorMessage && <p>{entry.errorMessage}</p>}
                </div>
              ))}
            </div>
          </details>
        )}
      </article>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div className={styles.headingCopy}><span className={styles.headingIcon}><History size={22} /></span><div><p>Nhật ký làm việc</p><h1>Lịch sử trò chuyện</h1><span>Xem lại câu hỏi, SQL và kết quả thực thi trước đây.</span></div></div>
        <button type="button" className={styles.dangerButton} disabled={conversations.length === 0 || deleting} onClick={() => setDeleteTarget({ type: "all" })}><Trash2 size={16} /> Xóa toàn bộ</button>
      </header>

      <nav className={styles.tabs} aria-label="Các nhóm lịch sử">
        <button type="button" className={tab === "all" ? styles.tabActive : ""} onClick={() => switchTab("all")}><Clock3 size={16} /> Tất cả <span>{conversations.length}</span></button>
        <button type="button" className={tab === "pinned" ? styles.tabActive : ""} onClick={() => switchTab("pinned")}><Star size={16} /> Đã ghim</button>
        <button type="button" className={tab === "search" ? styles.tabActive : ""} onClick={() => switchTab("search")}><Search size={16} /> Tìm kiếm</button>
      </nav>
      {error && <div className={styles.error} role="alert">{error}</div>}

      {tab === "all" && (
        <div className={styles.content}>
          <aside className={styles.list} aria-label="Danh sách cuộc trò chuyện">
            <div className={styles.listHeader}><strong>Cuộc trò chuyện</strong><small>Trang {historyPage + 1}/{historyTotalPages}</small></div>
            {listLoading && conversations.length === 0 ? <div className={styles.emptyState}><span className={styles.spinner} />Đang tải lịch sử…</div>
              : conversations.length === 0 ? <div className={styles.emptyState}><MessageSquareText size={30} /><strong>Chưa có lịch sử</strong><span>Các cuộc trò chuyện mới sẽ xuất hiện tại đây.</span></div>
              : visibleConversations.map((item) => (
                <div className={item.id === selectedId ? styles.itemSelected : styles.item} key={item.id}>
                  <button type="button" onClick={() => void openConversation(item.id)}><strong>{item.title}</strong><small>{formatDate(item.updatedAt)}</small></button>
                  <button type="button" aria-label={`Xóa ${item.title}`} onClick={() => setDeleteTarget({ type: "one", conversation: item })}><Trash2 size={15} /></button>
                </div>
              ))}
            {conversations.length > HISTORY_PAGE_SIZE && <div className={styles.pagination}><button type="button" aria-label="Trang lịch sử trước" disabled={historyPage === 0} onClick={() => setHistoryPage((page) => page - 1)}><ChevronLeft size={16} /></button><span>{historyPage + 1} / {historyTotalPages}</span><button type="button" aria-label="Trang lịch sử sau" disabled={historyPage + 1 >= historyTotalPages} onClick={() => setHistoryPage((page) => page + 1)}><ChevronRight size={16} /></button></div>}
          </aside>
          <section className={styles.detail} aria-live="polite">
            {detailLoading ? <div className={styles.emptyState}><span className={styles.spinner} />Đang tải nội dung…</div>
              : selectedId === null ? <div className={styles.emptyState}><MessageSquareText size={34} /><strong>Chọn một cuộc trò chuyện</strong><span>Thông tin câu hỏi, SQL và các lần thực thi sẽ hiển thị ở đây.</span></div>
              : messages.length === 0 ? <div className={styles.emptyState}><strong>Cuộc trò chuyện chưa có nội dung</strong></div>
              : messages.map(renderMessage)}
          </section>
        </div>
      )}

      {tab === "pinned" && <section className={styles.cardList}>{pinnedLoading ? <div className={styles.emptyState}><span className={styles.spinner} />Đang tải nội dung đã ghim…</div> : pinnedMessages.length === 0 ? <div className={styles.emptyState}><Star size={32} /><strong>Chưa có tin nhắn đã ghim</strong><span>Ghim những câu trả lời quan trọng để tìm lại nhanh hơn.</span></div> : pinnedMessages.map(renderMessage)}</section>}

      {tab === "search" && (
        <section className={styles.searchSection}>
          <form className={styles.searchForm} onSubmit={submitSearch}><Search size={18} /><input value={searchInput} onChange={(event) => setSearchInput(event.target.value)} placeholder="Nhập câu hỏi, nội dung hoặc câu SQL…" aria-label="Từ khóa tìm kiếm lịch sử" />{searchInput && <button className={styles.clearSearch} type="button" aria-label="Xóa từ khóa" onClick={() => setSearchInput("")}><X size={16} /></button>}<button className={styles.searchButton} type="submit" disabled={searchLoading}>{searchLoading ? "Đang tìm…" : "Tìm kiếm"}</button></form>
          {hasSearched && !searchLoading && <p className={styles.resultCount}>Tìm thấy {searchTotalElements} kết quả</p>}
          <div className={styles.cardList}>
            {searchLoading ? <div className={styles.emptyState}><span className={styles.spinner} />Đang tìm trong lịch sử…</div>
              : !hasSearched ? <div className={styles.emptyState}><Search size={32} /><strong>Tìm lại nội dung bạn cần</strong><span>Có thể tìm theo câu hỏi hoặc đoạn SQL đã tạo.</span></div>
              : searchResults.length === 0 ? <div className={styles.emptyState}><strong>Không tìm thấy kết quả</strong><span>Thử từ khóa ngắn hơn hoặc kiểm tra lại chính tả.</span></div>
              : searchResults.map((result) => <article className={styles.searchCard} key={result.messageId}><header><div><strong>{result.conversationTitle}</strong><time>{formatDate(result.createdAt)}</time></div>{result.pinned && <span className={styles.pinnedLabel}><Star size={13} fill="currentColor" /> Đã ghim</span>}</header><p>{result.content}</p>{result.generatedSql && <div className={styles.sqlBlock}><span>SQL đã tạo</span><pre><code>{result.generatedSql}</code></pre></div>}<button type="button" className={styles.openButton} onClick={() => { setTab("all"); void openConversation(result.conversationId); }}>Mở cuộc trò chuyện <ChevronRight size={15} /></button></article>)}
          </div>
          {searchTotalPages > 1 && !searchLoading && <div className={styles.searchPagination}><button type="button" disabled={searchPage === 0} onClick={() => void runSearch(searchPage - 1)}><ChevronLeft size={16} /> Trang trước</button><span>Trang {searchPage + 1} / {searchTotalPages}</span><button type="button" disabled={searchPage + 1 >= searchTotalPages} onClick={() => void runSearch(searchPage + 1)}>Trang sau <ChevronRight size={16} /></button></div>}
        </section>
      )}

      {deleteTarget && <div className={styles.modalBackdrop} role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !deleting) setDeleteTarget(null); }}><section className={styles.modal} role="dialog" aria-modal="true" aria-labelledby="delete-title"><span className={styles.modalIcon}><Trash2 size={22} /></span><h2 id="delete-title">{deleteTarget.type === "all" ? "Xóa toàn bộ lịch sử?" : "Xóa cuộc trò chuyện?"}</h2><p>{deleteTarget.type === "all" ? "Tất cả cuộc trò chuyện và thông tin truy vấn của bạn sẽ bị xóa vĩnh viễn." : <>Cuộc trò chuyện <strong>“{deleteTarget.conversation.title}”</strong> sẽ bị xóa vĩnh viễn.</>}</p><div className={styles.modalActions}><button type="button" disabled={deleting} onClick={() => setDeleteTarget(null)}>Giữ lại</button><button type="button" disabled={deleting} onClick={() => void confirmDelete()}>{deleting ? "Đang xóa…" : "Xóa vĩnh viễn"}</button></div></section></div>}
    </div>
  );
}
