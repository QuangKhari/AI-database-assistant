import { type FormEvent, useCallback, useEffect, useState } from "react";
import { historyApi } from "../api/historyApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type {
  ChatMessage,
  Conversation,
  HistorySearchResult,
} from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";
import styles from "./HistoryPage.module.css";

type Tab = "all" | "pinned" | "search";

const PAGE_SIZE = 10;

export function HistoryPage() {
  const { showToast } = useToast();

  const [tab, setTab] = useState<Tab>("all");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pinnedMessages, setPinnedMessages] = useState<ChatMessage[]>([]);
  const [searchInput, setSearchInput] = useState("");
  const [searchResults, setSearchResults] = useState<HistorySearchResult[]>([]);

  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [loading, setLoading] = useState(true);

  const { error, handleError, setError } = useApiError();

  const loadConversations = useCallback(
    async (page = 0) => {
      setLoading(true);

      try {
        const result = await historyApi.listPaged(page, PAGE_SIZE);

        setConversations(result.content);
        setCurrentPage(result.number);
        setTotalPages(result.totalPages);
        setError("");
      } catch (reason) {
        handleError(reason, "Không tải được lịch sử.");
      } finally {
        setLoading(false);
      }
    },
    [handleError, setError],
  );

  useEffect(() => {
    void loadConversations(0);
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
      const page = await historyApi.search({
        keyword: searchInput.trim(),
        page: 0,
        size: PAGE_SIZE,
      });

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

    if (next === "pinned") {
      void loadPinned();
    }
  }

  async function togglePin(messageId: number) {
    try {
      const updated = await historyApi.togglePin(messageId);

      setMessages((current) =>
        current.map((m) => (m.id === messageId ? updated : m)),
      );

      if (tab === "pinned") {
        await loadPinned();
      }

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
    if (!window.confirm(`Xóa vĩnh viễn cuộc trò chuyện "${item.title}"?`)) {
      return;
    }

    try {
      await historyApi.remove(item.id);

      if (selectedId === item.id) {
        setSelectedId(null);
        setMessages([]);
      }

      const nextPage =
        conversations.length === 1 && currentPage > 0
          ? currentPage - 1
          : currentPage;

      await loadConversations(nextPage);

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
    ) {
      return;
    }

    try {
      await historyApi.removeAll();

      setSelectedId(null);
      setMessages([]);
      setCurrentPage(0);
      setTotalPages(0);

      await loadConversations(0);

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

  function goToPreviousPage() {
    if (currentPage <= 0 || loading) {
      return;
    }

    void loadConversations(currentPage - 1);
  }

  function goToNextPage() {
    if (currentPage >= totalPages - 1 || loading) {
      return;
    }

    void loadConversations(currentPage + 1);
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div className={styles.headingCopy}>
          <div className={styles.eyebrow}>
            <span className={styles.eyebrowDot} />
            Query history
          </div>

          <h1>Lịch sử trò chuyện</h1>

          <p>
            Xem lại, tìm kiếm và quản lý các câu hỏi, câu trả lời và truy vấn
            SQL trước đây.
          </p>
        </div>

        <button
          type="button"
          className={styles.dangerButton}
          onClick={() => void removeAll()}
        >
          <span className={styles.dangerIcon}>⌫</span>
          Xóa toàn bộ
        </button>
      </header>

      <nav className={styles.tabs} aria-label="Lịch sử">
        <button
          type="button"
          className={tab === "all" ? styles.tabActive : ""}
          onClick={() => switchTab("all")}
        >
          <span className={styles.tabIcon}>◷</span>
          Tất cả
        </button>

        <button
          type="button"
          className={tab === "pinned" ? styles.tabActive : ""}
          onClick={() => switchTab("pinned")}
        >
          <span className={styles.tabIcon}>★</span>
          Đã ghim
        </button>

        <button
          type="button"
          className={tab === "search" ? styles.tabActive : ""}
          onClick={() => switchTab("search")}
        >
          <span className={styles.tabIcon}>⌕</span>
          Tìm kiếm
        </button>
      </nav>

      {error && (
        <div className={styles.error} role="alert">
          <span className={styles.errorIcon}>!</span>
          <span>{error}</span>
        </div>
      )}

      {tab === "all" && (
        <div className={styles.content}>
          <aside className={styles.listPanel}>
            <div className={styles.listHeader}>
              <div>
                <span className={styles.sectionLabel}>CONVERSATIONS</span>
                <h2>Lịch sử gần đây</h2>
              </div>

              <span className={styles.countBadge}>{conversations.length}</span>
            </div>

            <div className={styles.list}>
              {loading && conversations.length === 0 ? (
                <>
                  <div className={styles.skeletonItem} />
                  <div className={styles.skeletonItem} />
                  <div className={styles.skeletonItem} />
                  <div className={styles.skeletonItem} />
                </>
              ) : conversations.length === 0 ? (
                <div className={styles.emptyState}>
                  <div className={styles.emptyIcon}>◷</div>
                  <strong>Chưa có cuộc trò chuyện</strong>
                  <span>Các cuộc trò chuyện của bạn sẽ xuất hiện ở đây.</span>
                </div>
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
                      className={styles.itemMain}
                      onClick={() => void openConversation(item.id)}
                    >
                      <span className={styles.itemIndicator} />

                      <span className={styles.itemContent}>
                        <strong>{item.title}</strong>

                        <small>
                          {new Date(item.updatedAt).toLocaleString("vi-VN")}
                        </small>
                      </span>
                    </button>

                    <button
                      type="button"
                      className={styles.deleteButton}
                      aria-label={`Xóa ${item.title}`}
                      onClick={() => void removeConversation(item)}
                    >
                      ×
                    </button>
                  </div>
                ))
              )}
            </div>

            {totalPages > 1 && (
              <div className={styles.pagination}>
                <button
                  type="button"
                  disabled={currentPage === 0 || loading}
                  onClick={goToPreviousPage}
                >
                  ←
                </button>

                <span>
                  Trang <strong>{currentPage + 1}</strong> / {totalPages}
                </span>

                <button
                  type="button"
                  disabled={currentPage >= totalPages - 1 || loading}
                  onClick={goToNextPage}
                >
                  →
                </button>
              </div>
            )}
          </aside>

          <section className={styles.detailPanel}>
            <div className={styles.detailHeader}>
              <div>
                <span className={styles.sectionLabel}>CONVERSATION</span>
                <h2>Chi tiết trò chuyện</h2>
              </div>

              {selectedId !== null && (
                <span className={styles.liveBadge}>
                  <span />
                  Đã chọn
                </span>
              )}
            </div>

            <div className={styles.detail}>
              {selectedId === null ? (
                <div className={styles.detailEmpty}>
                  <div className={styles.detailEmptyIcon}>☷</div>
                  <h3>Chọn một cuộc trò chuyện</h3>
                  <p>
                    Chọn một mục bên trái để xem lại câu hỏi, câu trả lời và SQL
                    đã được tạo.
                  </p>
                </div>
              ) : loading && messages.length === 0 ? (
                <div className={styles.detailLoading}>
                  <div className={styles.loadingBar} />
                  <div className={styles.loadingBarShort} />
                  <div className={styles.loadingBox} />
                </div>
              ) : messages.length === 0 ? (
                <div className={styles.detailEmpty}>
                  <div className={styles.detailEmptyIcon}>☷</div>
                  <h3>Chưa có nội dung</h3>
                  <p>Cuộc trò chuyện này chưa có tin nhắn.</p>
                </div>
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
                    <header className={styles.messageHeader}>
                      <div className={styles.author}>
                        <span
                          className={
                            message.role === "user"
                              ? styles.userAvatar
                              : styles.aiAvatar
                          }
                        >
                          {message.role === "user" ? "B" : "AI"}
                        </span>

                        <span>
                          {message.role === "user" ? "Bạn" : "AI QueryMate"}
                        </span>
                      </div>

                      <button
                        type="button"
                        className={styles.pinButton}
                        onClick={() => void togglePin(message.id)}
                      >
                        {message.pinned ? "★ Đã ghim" : "☆ Ghim"}
                      </button>
                    </header>

                    <p className={styles.messageContent}>{message.content}</p>

                    {message.generatedSql && (
                      <div className={styles.sqlBlock}>
                        <div className={styles.sqlHeader}>
                          <span>
                            <span className={styles.sqlDot} />
                            Generated SQL
                          </span>
                        </div>

                        <pre>
                          <code>{message.generatedSql}</code>
                        </pre>
                      </div>
                    )}
                  </article>
                ))
              )}
            </div>
          </section>
        </div>
      )}

      {tab === "pinned" && (
        <section className={styles.singlePanel}>
          <div className={styles.singlePanelHeader}>
            <div>
              <span className={styles.sectionLabel}>SAVED MESSAGES</span>
              <h2>Tin nhắn đã ghim</h2>
              <p>Những nội dung bạn muốn lưu lại để xem nhanh.</p>
            </div>

            <span className={styles.countBadge}>{pinnedMessages.length}</span>
          </div>

          <div className={styles.pinnedList}>
            {loading ? (
              <>
                <div className={styles.skeletonCard} />
                <div className={styles.skeletonCard} />
              </>
            ) : pinnedMessages.length === 0 ? (
              <div className={styles.emptyStateLarge}>
                <div className={styles.emptyIcon}>★</div>
                <strong>Chưa ghim tin nhắn nào</strong>
                <span>
                  Khi ghim một tin nhắn trong lịch sử, nội dung sẽ xuất hiện ở
                  đây.
                </span>
              </div>
            ) : (
              pinnedMessages.map((message) => (
                <article className={styles.pinnedCard} key={message.id}>
                  <header>
                    <div className={styles.pinnedMeta}>
                      <span className={styles.starBadge}>★</span>
                      <span>
                        {new Date(message.createdAt).toLocaleString("vi-VN")}
                      </span>
                    </div>

                    <button
                      type="button"
                      onClick={() => void togglePin(message.id)}
                    >
                      Bỏ ghim
                    </button>
                  </header>

                  <p>{message.content}</p>

                  {message.generatedSql && (
                    <div className={styles.sqlBlock}>
                      <div className={styles.sqlHeader}>
                        <span>
                          <span className={styles.sqlDot} />
                          Generated SQL
                        </span>
                      </div>

                      <pre>
                        <code>{message.generatedSql}</code>
                      </pre>
                    </div>
                  )}
                </article>
              ))
            )}
          </div>
        </section>
      )}

      {tab === "search" && (
        <section className={styles.singlePanel}>
          <div className={styles.singlePanelHeader}>
            <div>
              <span className={styles.sectionLabel}>SEARCH</span>
              <h2>Tìm kiếm lịch sử</h2>
              <p>Tìm lại câu hỏi hoặc câu SQL từ các cuộc trò chuyện.</p>
            </div>
          </div>

          <form className={styles.searchForm} onSubmit={submitSearch}>
            <div className={styles.searchInputWrapper}>
              <span className={styles.searchIcon}>⌕</span>

              <input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Tìm theo nội dung câu hỏi hoặc SQL…"
              />

              {searchInput && (
                <button
                  type="button"
                  className={styles.clearSearch}
                  onClick={() => setSearchInput("")}
                  aria-label="Xóa từ khóa"
                >
                  ×
                </button>
              )}
            </div>

            <button type="submit" className={styles.searchButton}>
              <span>⌕</span>
              Tìm kiếm
            </button>
          </form>

          <div className={styles.searchResults}>
            {loading ? (
              <>
                <div className={styles.skeletonCard} />
                <div className={styles.skeletonCard} />
              </>
            ) : searchResults.length === 0 ? (
              <div className={styles.emptyStateLarge}>
                <div className={styles.emptyIcon}>⌕</div>
                <strong>Chưa có kết quả</strong>
                <span>
                  Nhập từ khóa để tìm kiếm trong nội dung câu hỏi và SQL.
                </span>
              </div>
            ) : (
              searchResults.map((result) => (
                <article className={styles.searchCard} key={result.messageId}>
                  <header>
                    <div>
                      <span className={styles.searchConversation}>
                        {result.conversationTitle}
                      </span>

                      <span className={styles.searchDate}>
                        {new Date(result.createdAt).toLocaleString("vi-VN")}
                      </span>
                    </div>

                    <span className={styles.resultBadge}>Kết quả</span>
                  </header>

                  <p>{result.content}</p>

                  {result.generatedSql && (
                    <div className={styles.sqlBlock}>
                      <div className={styles.sqlHeader}>
                        <span>
                          <span className={styles.sqlDot} />
                          Generated SQL
                        </span>
                      </div>

                      <pre>
                        <code>{result.generatedSql}</code>
                      </pre>
                    </div>
                  )}

                  <button
                    type="button"
                    className={styles.openConversationButton}
                    onClick={() => {
                      setTab("all");
                      void openConversation(result.conversationId);
                    }}
                  >
                    Mở cuộc trò chuyện
                    <span>→</span>
                  </button>
                </article>
              ))
            )}
          </div>
        </section>
      )}
    </div>
  );
}
