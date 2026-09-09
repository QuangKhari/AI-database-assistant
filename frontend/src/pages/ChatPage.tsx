import { useCallback, useEffect, useRef, useState } from "react";
import { chatApi } from "../api/chatApi";
import { connectionApi } from "../api/connectionApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type {
  ChatMessage,
  Conversation,
  DatabaseConnection,
  ExplainSqlResponse,
  OptimizeSqlResponse,
  QueryResponse,
} from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";
import styles from "./ChatPage.module.css";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

// Số cuộc trò chuyện hiển thị mỗi trang ở sidebar (GET /api/conversations/paged).
const CONVERSATIONS_PAGE_SIZE = 8;

export function ChatPage() {
  const { showToast } = useToast();
  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [connectionId, setConnectionId] = useState<number | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationsPage, setConversationsPage] = useState(0);
  const [conversationsTotalPages, setConversationsTotalPages] = useState(1);
  const [conversationsTotalElements, setConversationsTotalElements] =
    useState(0);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [streamStatus, setStreamStatus] = useState("");
  const { error, handleError, setError } = useApiError();
  const [preview, setPreview] = useState<{
    generatedSql: string;
    valid: boolean;
    errorMessage: string | null;
  } | null>(null);
  const [previewQuestion, setPreviewQuestion] = useState("");
  const [queryResult, setQueryResult] = useState<QueryResponse | null>(null);
  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([]);
  const [refreshingSuggestions, setRefreshingSuggestions] = useState(false);

  // ===== Giải thích SQL (POST /api/query/explain) — khóa theo messageId để
  // mỗi message có panel riêng, cache lại để không gọi AI lặp lại khi mở/đóng.
  const [explainOpenId, setExplainOpenId] = useState<number | null>(null);
  const [explainLoadingId, setExplainLoadingId] = useState<number | null>(null);
  const [explainCache, setExplainCache] = useState<
    Record<number, ExplainSqlResponse>
  >({});

  // ===== Tối ưu SQL (POST /api/query/optimize) — chỉ hỗ trợ MySQL (BE chặn
  // cứng ở SqlOptimizationService cho các dbType khác).
  const [optimizeOpenId, setOptimizeOpenId] = useState<number | null>(null);
  const [optimizeLoadingId, setOptimizeLoadingId] = useState<number | null>(
    null,
  );
  const [optimizeCache, setOptimizeCache] = useState<
    Record<number, OptimizeSqlResponse>
  >({});

  const [exporting, setExporting] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter(
          (item) => item.active,
        );
        setConnections(available);
        setConnectionId(available[0]?.id ?? null);
      } catch (reason) {
        handleError(reason, "Không tải được connections.");
      } finally {
        setLoading(false);
      }
    }
    void loadConnections();
  }, []);

  const loadConversations = useCallback(
    async (selectedConnectionId: number, page = 0) => {
      setConversationsLoading(true);
      try {
        let result = await chatApi.conversationsPaged(
          selectedConnectionId,
          page,
          CONVERSATIONS_PAGE_SIZE,
        );

        // Trang vừa xóa hết item cuối cùng (và không phải trang đầu) ->
        // lùi về trang trước để không hiển thị sidebar trống một cách vô lý.
        if (result.content.length === 0 && page > 0) {
          result = await chatApi.conversationsPaged(
            selectedConnectionId,
            page - 1,
            CONVERSATIONS_PAGE_SIZE,
          );
        }

        setConversations(result.content);
        setConversationsPage(result.number);
        setConversationsTotalPages(Math.max(1, result.totalPages));
        setConversationsTotalElements(result.totalElements);
        setError("");
      } catch (reason) {
        handleError(reason, "Không tải được conversations.");
      } finally {
        setConversationsLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    setConversationId(null);
    setMessages([]);
    setPreview(null);
    setPreviewQuestion("");
    setQueryResult(null);
    setStreamStatus("");
    setSuggestedQuestions([]);
    if (connectionId !== null) {
      void loadConversations(connectionId);
      // Lỗi suggestions không được làm hỏng Chat (theo đúng yêu cầu 4.8) -
      // chỉ log/bỏ qua, không setError toàn trang.
      connectionApi
        .suggestedQuestions(connectionId)
        .then((result) => setSuggestedQuestions(result.questions))
        .catch(() => setSuggestedQuestions([]));
    }
  }, [connectionId, loadConversations]);

  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === "function") {
      endRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages, preview, queryResult]);

  async function openConversation(id: number) {
    setConversationId(id);
    setPreview(null);
    setPreviewQuestion("");
    setQueryResult(null);
    setLoading(true);
    try {
      setMessages(await chatApi.messages(id));
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được nội dung hội thoại.");
    } finally {
      setLoading(false);
    }
  }

  async function sendQuestion(event: React.FormEvent) {
    event.preventDefault();
    const cleanQuestion = question.trim();
    if (!cleanQuestion || connectionId === null) return;
    setSending(true);
    setPreview(null);
    setQueryResult(null);
    try {
      const result = await chatApi.preview({
        databaseConnectionId: connectionId,
        ...(conversationId ? { conversationId } : {}),
        question: cleanQuestion,
      });
      setPreviewQuestion(cleanQuestion);
      setQuestion("");
      setPreview(result);
      if (!result.valid)
        showToast(
          result.errorMessage || "SQL chưa vượt qua kiểm tra an toàn.",
          "error",
        );
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể tạo SQL preview."),
        ),
        "error",
      );
    } finally {
      setSending(false);
    }
  }

  async function executeQuery() {
    if (!preview?.valid || connectionId === null || !previewQuestion) {
      return;
    }

    setExecuting(true);
    setStreamStatus("Đang chuẩn bị thực thi…");

    let streamResult: QueryResponse | null = null;
    let streamError = "";

    try {
      await chatApi.executeStream(
        {
          databaseConnectionId: connectionId,
          ...(conversationId ? { conversationId } : {}),
          question: previewQuestion,
        },
        (event) => {
          if (event.type === "STATUS") {
            setStreamStatus(event.message);
            return;
          }

          if (event.type === "result") {
            streamResult = event.data;
            setQueryResult(event.data);
            setConversationId(event.data.conversationId);
            return;
          }

          streamError = event.message;
          setStreamStatus(event.message);
        },
      );

      if (streamError) {
        throw new Error(streamError);
      }

      if (!streamResult) {
        throw new Error(
          "Stream kết thúc nhưng không nhận được kết quả truy vấn.",
        );
      }

      const finalResult = streamResult as QueryResponse;

      setMessages(await chatApi.messages(finalResult.conversationId));
      setPreview(null);

      await loadConversations(connectionId);

      setStreamStatus("Hoàn tất.");
    } catch (reason) {
      const fallback =
        reason instanceof Error ? reason.message : "Không thể thực thi SQL.";
      showToast(
        formatErrorWithSupportCode(parseApiError(reason, fallback)),
        "error",
      );
    } finally {
      setExecuting(false);
    }
  }

  async function refreshSuggestedQuestions() {
    if (connectionId === null) return;

    setRefreshingSuggestions(true);

    try {
      const result = await connectionApi.suggestedQuestions(connectionId, true);
      setSuggestedQuestions(result.questions);
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể làm mới câu hỏi gợi ý."),
        ),
        "error",
      );
    } finally {
      setRefreshingSuggestions(false);
    }
  }

  async function exportResult() {
    if (!queryResult || queryResult.result.error) return;
    setExporting(true);
    try {
      const { blob, filename } = await chatApi.exportExcel({
        columns: queryResult.result.columns,
        rows: queryResult.result.rows,
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể xuất Excel."),
        ),
        "error",
      );
    } finally {
      setExporting(false);
    }
  }

  async function removeConversation(item: Conversation) {
    if (!window.confirm(`Xóa cuộc trò chuyện "${item.title}"?`)) return;
    try {
      await chatApi.removeConversation(item.id);
      if (conversationId === item.id) {
        setConversationId(null);
        setMessages([]);
        setPreview(null);
        setPreviewQuestion("");
        setQueryResult(null);
      }
      if (connectionId !== null)
        await loadConversations(connectionId, conversationsPage);
      showToast("Đã xóa cuộc trò chuyện.", "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể xóa cuộc trò chuyện."),
        ),
        "error",
      );
    }
  }

  function goToConversationsPage(nextPage: number) {
    if (
      connectionId === null ||
      conversationsLoading ||
      nextPage < 0 ||
      nextPage >= conversationsTotalPages
    ) {
      return;
    }
    void loadConversations(connectionId, nextPage);
  }

  // Toggle panel "Giải thích SQL" cho một message. Kết quả được cache theo
  // messageId nên bấm đóng/mở lại không gọi lại API (không tốn quota Gemini).
  async function toggleExplain(messageId: number, sql: string) {
    if (explainOpenId === messageId) {
      setExplainOpenId(null);
      return;
    }

    setExplainOpenId(messageId);
    setOptimizeOpenId(null);

    if (explainCache[messageId]) return;

    setExplainLoadingId(messageId);
    try {
      const result = await chatApi.explain({
        sql,
        ...(connectionId ? { databaseConnectionId: connectionId } : {}),
      });
      setExplainCache((prev) => ({ ...prev, [messageId]: result }));
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể giải thích SQL."),
        ),
        "error",
      );
      setExplainOpenId(null);
    } finally {
      setExplainLoadingId(null);
    }
  }

  // Toggle panel "Tối ưu SQL". BE hiện chỉ hỗ trợ MySQL (SqlOptimizationService
  // ném lỗi rõ ràng cho các dbType khác) nên nút này chỉ hiện khi
  // connection đang chọn là mysql (xem điều kiện canOptimize bên dưới).
  async function toggleOptimize(messageId: number, sql: string) {
    if (connectionId === null) return;

    if (optimizeOpenId === messageId) {
      setOptimizeOpenId(null);
      return;
    }

    setOptimizeOpenId(messageId);
    setExplainOpenId(null);

    if (optimizeCache[messageId]) return;

    setOptimizeLoadingId(messageId);
    try {
      const result = await chatApi.optimize({
        sql,
        databaseConnectionId: connectionId,
      });
      setOptimizeCache((prev) => ({ ...prev, [messageId]: result }));
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể tối ưu SQL."),
        ),
        "error",
      );
      setOptimizeOpenId(null);
    } finally {
      setOptimizeLoadingId(null);
    }
  }

  if (!loading && connections.length === 0) {
    return (
      <section className={styles.emptyPage}>
        <h1>Chưa có connection hoạt động</h1>
        <p>Hãy tạo connection và đồng bộ schema trước khi Chat với AI.</p>
      </section>
    );
  }

  // BE (SqlOptimizationService) chỉ hỗ trợ EXPLAIN-based optimize cho MySQL.
  const activeConnection = connections.find((item) => item.id === connectionId);
  const canOptimize = activeConnection?.dbType === "mysql";

  function buildChartData() {
    if (!queryResult?.chartSuggestion) return [];

    const chart = queryResult.chartSuggestion;

    return chart.xAxisLabels.map((label, index) => {
      const item: Record<string, string | number> = {
        name: label,
      };

      chart.series.forEach((series) => {
        item[series.name] = series.values[index] ?? 0;
      });

      return item;
    });
  }

  function renderChart() {
    if (!queryResult?.chartSuggestion) return null;

    const chart = queryResult.chartSuggestion;
    const data = buildChartData();

    if (chart.chartType === "BAR") {
      return (
        <ResponsiveContainer width="100%" height={320}>
          <BarChart data={data}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="name" />
            <YAxis />
            <Tooltip />
            <Legend />
            {chart.series.map((series) => (
              <Bar key={series.name} dataKey={series.name} />
            ))}
          </BarChart>
        </ResponsiveContainer>
      );
    }

    if (chart.chartType === "LINE") {
      return (
        <ResponsiveContainer width="100%" height={320}>
          <LineChart data={data}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="name" />
            <YAxis />
            <Tooltip />
            <Legend />
            {chart.series.map((series) => (
              <Line key={series.name} type="monotone" dataKey={series.name} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      );
    }

    if (chart.chartType === "PIE") {
      const series = chart.series[0];

      if (!series) return null;

      const pieData = chart.xAxisLabels.map((label, index) => ({
        name: label,
        value: series.values[index] ?? 0,
      }));

      return (
        <ResponsiveContainer width="100%" height={320}>
          <PieChart>
            <Tooltip />
            <Legend />
            <Pie
              data={pieData}
              dataKey="value"
              nameKey="name"
              outerRadius={110}
              label
            >
              {pieData.map((item, index) => (
                <Cell key={`${item.name}-${index}`} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
      );
    }

    if (chart.chartType === "DONUT") {
      const series = chart.series[0];

      if (!series) return null;

      const donutData = chart.xAxisLabels.map((label, index) => ({
        name: label,
        value: series.values[index] ?? 0,
      }));

      return (
        <ResponsiveContainer width="100%" height={320}>
          <PieChart>
            <Tooltip />
            <Legend />
            <Pie
              data={donutData}
              dataKey="value"
              nameKey="name"
              innerRadius={60}
              outerRadius={110}
              label
            >
              {donutData.map((item, index) => (
                <Cell key={`${item.name}-${index}`} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
      );
    }

    if (chart.chartType === "TABLE") {
      return (
        <div className={styles.chartTableWrapper}>
          <table className={styles.chartTable}>
            <thead>
              <tr>
                <th>{chart.xAxisColumn ?? "Dimension"}</th>
                {chart.series.map((series) => (
                  <th key={series.name}>{series.name}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.map((item, index) => (
                <tr key={`${item.name}-${index}`}>
                  <td>{item.name}</td>
                  {chart.series.map((series) => (
                    <td key={series.name}>{String(item[series.name] ?? "")}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    }

    return null;
  }

  function copyToClipboard(text: string) {
    navigator.clipboard?.writeText(text).then(
      () => showToast("Đã sao chép vào clipboard.", "success"),
      () => showToast("Không thể sao chép.", "error"),
    );
  }

  function renderExplainPanel(messageId: number) {
    if (explainOpenId !== messageId) return null;

    if (explainLoadingId === messageId) {
      return (
        <div className={styles.aiPanel}>
          <p className={styles.center}>Đang giải thích SQL…</p>
        </div>
      );
    }

    const data = explainCache[messageId];
    if (!data) return null;

    return (
      <div className={styles.explainPanel}>
        <strong>Giải thích SQL</strong>
        <p>{data.summary}</p>
        <ol>
          {data.steps.map((step, index) => (
            <li key={index}>
              <code>{step.clause}</code>
              <span>{step.explanation}</span>
            </li>
          ))}
        </ol>
      </div>
    );
  }

  function renderOptimizePanel(messageId: number) {
    if (optimizeOpenId !== messageId) return null;

    if (optimizeLoadingId === messageId) {
      return (
        <div className={styles.aiPanel}>
          <p className={styles.center}>Đang phân tích EXPLAIN và tối ưu SQL…</p>
        </div>
      );
    }

    const data = optimizeCache[messageId];
    if (!data) return null;

    return (
      <div className={styles.optimizePanel}>
        <strong>Tối ưu SQL</strong>
        <p>{data.aiSummary}</p>

        {data.explainPlan.length > 0 && (
          <div className={styles.explainTableWrapper}>
            <table className={styles.explainTable}>
              <thead>
                <tr>
                  <th>id</th>
                  <th>select_type</th>
                  <th>table</th>
                  <th>type</th>
                  <th>possible_keys</th>
                  <th>key</th>
                  <th>rows</th>
                  <th>extra</th>
                </tr>
              </thead>
              <tbody>
                {data.explainPlan.map((row, index) => (
                  <tr key={index}>
                    <td>{row.id ?? "-"}</td>
                    <td>{row.selectType ?? "-"}</td>
                    <td>{row.table ?? "-"}</td>
                    <td>{row.type ?? "-"}</td>
                    <td>{row.possibleKeys ?? "-"}</td>
                    <td>{row.key ?? "-"}</td>
                    <td>{row.rows ?? "-"}</td>
                    <td>{row.extra ?? "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {data.issues.length > 0 && (
          <div className={styles.issueList}>
            <span className={styles.subHeading}>Vấn đề phát hiện</span>
            <ul>
              {data.issues.map((issue, index) => (
                <li key={index}>
                  <span
                    className={
                      issue.severity === "HIGH"
                        ? styles.severityHigh
                        : issue.severity === "MEDIUM"
                          ? styles.severityMedium
                          : styles.severityLow
                    }
                  >
                    {issue.severity}
                  </span>
                  <div>
                    <strong>{issue.table}</strong>
                    <p>{issue.description}</p>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        {data.suggestions.length > 0 && (
          <div className={styles.indexList}>
            <span className={styles.subHeading}>Đề xuất index</span>
            {data.suggestions.map((suggestion, index) => (
              <div className={styles.indexCard} key={index}>
                <div>
                  <strong>{suggestion.table}</strong>
                  <span>({suggestion.columns.join(", ")})</span>
                </div>
                <p>{suggestion.reason}</p>
                <div className={styles.indexSqlRow}>
                  <code>{suggestion.createIndexSql}</code>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(suggestion.createIndexSql)}
                  >
                    Sao chép
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div>
          <p>Natural language to SQL</p>
          <h1>Chat với database</h1>
          <span>
            AI tạo SQL preview từ schema đã đồng bộ. Bạn có thể kiểm tra SQL
            trước khi thực thi.
          </span>
        </div>
        <label>
          Database
          <select
            value={connectionId ?? ""}
            onChange={(event) => setConnectionId(Number(event.target.value))}
          >
            {connections.map((connection) => (
              <option key={connection.id} value={connection.id}>
                {connection.name} — {connection.databaseName}
              </option>
            ))}
          </select>
        </label>
      </header>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      <div className={styles.workspace}>
        <aside className={styles.sidebar}>
          <button
            type="button"
            onClick={() => {
              setConversationId(null);
              setMessages([]);
              setPreview(null);
              setPreviewQuestion("");
              setQueryResult(null);
            }}
          >
            + Cuộc trò chuyện mới
          </button>
          <h2>Gần đây</h2>
          <div className={styles.conversationList}>
            {conversationsLoading && conversations.length === 0 && (
              <small>Đang tải…</small>
            )}
            {!conversationsLoading && conversations.length === 0 && (
              <small>Chưa có cuộc trò chuyện.</small>
            )}
            {conversations.map((item) => (
              <div
                className={item.id === conversationId ? styles.selected : ""}
                key={item.id}
              >
                <button
                  type="button"
                  onClick={() => void openConversation(item.id)}
                >
                  <strong>{item.title}</strong>
                  <span>
                    {new Date(item.updatedAt).toLocaleString("vi-VN")}
                  </span>
                </button>
                <button
                  type="button"
                  aria-label={`Xóa ${item.title}`}
                  onClick={() => void removeConversation(item)}
                >
                  ×
                </button>
              </div>
            ))}
          </div>

          {conversationsTotalElements > 0 && (
            <div className={styles.conversationsPagination}>
              <span>
                Trang {conversationsPage + 1}/{conversationsTotalPages}
              </span>
              <div>
                <button
                  type="button"
                  disabled={conversationsPage === 0 || conversationsLoading}
                  onClick={() => goToConversationsPage(conversationsPage - 1)}
                >
                  ‹ Trước
                </button>
                <button
                  type="button"
                  disabled={
                    conversationsPage + 1 >= conversationsTotalPages ||
                    conversationsLoading
                  }
                  onClick={() => goToConversationsPage(conversationsPage + 1)}
                >
                  Sau ›
                </button>
              </div>
            </div>
          )}
        </aside>

        <section className={styles.chatPanel}>
          <div className={styles.messages} aria-live="polite">
            {loading ? (
              <p className={styles.center}>Đang tải…</p>
            ) : messages.length === 0 ? (
              <div className={styles.welcome}>
                <span>AI</span>
                <h2>Bạn muốn tìm dữ liệu gì?</h2>
                <p>Ví dụ: "Liệt kê 10 khách hàng có tổng đơn hàng cao nhất."</p>
                <small>
                  AI chỉ dùng schema của connection đang chọn và nhớ tối đa 3
                  lượt gần nhất.
                </small>
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
                  <header>
                    {message.role === "user" ? "Bạn" : "AI QueryMate"}
                  </header>
                  <p>{message.content}</p>
                  {message.generatedSql && (
                    <div className={styles.sql}>
                      <div>
                        <span>SQL</span>
                        <small>Đã thực thi</small>
                      </div>
                      <pre>
                        <code>{message.generatedSql}</code>
                      </pre>
                    </div>
                  )}
                  {message.generatedSql && (
                    <div className={styles.sqlToolsRow}>
                      <button
                        type="button"
                        onClick={() =>
                          void toggleExplain(
                            message.id,
                            message.generatedSql as string,
                          )
                        }
                      >
                        {explainOpenId === message.id
                          ? "▲ Đóng giải thích"
                          : "🔍 Giải thích SQL"}
                      </button>
                      {canOptimize && (
                        <button
                          type="button"
                          onClick={() =>
                            void toggleOptimize(
                              message.id,
                              message.generatedSql as string,
                            )
                          }
                        >
                          {optimizeOpenId === message.id
                            ? "▲ Đóng tối ưu"
                            : "⚡ Tối ưu SQL"}
                        </button>
                      )}
                    </div>
                  )}
                  {renderExplainPanel(message.id)}
                  {renderOptimizePanel(message.id)}
                </article>
              ))
            )}

            {messages.length === 0 &&
              !loading &&
              suggestedQuestions.length > 0 && (
                <div className={styles.suggestions}>
                  <div className={styles.suggestionsHeader}>
                    <span>Gợi ý câu hỏi:</span>
                    <button
                      type="button"
                      onClick={() => void refreshSuggestedQuestions()}
                      disabled={refreshingSuggestions}
                    >
                      {refreshingSuggestions ? "Đang làm mới…" : "↻ Làm mới"}
                    </button>
                  </div>
                  <div>
                    {suggestedQuestions.map((suggestion) => (
                      <button
                        type="button"
                        key={suggestion}
                        onClick={() => setQuestion(suggestion)}
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                </div>
              )}

            {streamStatus && (
              <p className={styles.summary} role="status">
                {streamStatus}
              </p>
            )}

            {preview && (
              <div className={styles.previewPanel}>
                <div className={styles.sql}>
                  <div>
                    <span>SQL preview</span>
                    <small>
                      {preview.valid ? "SQL hợp lệ" : "SQL không hợp lệ"}
                    </small>
                  </div>
                  <pre>
                    <code>{preview.generatedSql}</code>
                  </pre>
                </div>
                {!preview.valid && (
                  <p className={styles.queryError}>
                    {preview.errorMessage ||
                      "SQL không vượt qua kiểm tra an toàn."}
                  </p>
                )}
                {preview.valid && (
                  <button
                    type="button"
                    onClick={() => void executeQuery()}
                    disabled={executing}
                  >
                    {executing ? "Đang thực thi…" : "▶ Thực thi SQL"}
                  </button>
                )}
              </div>
            )}

            {queryResult && (
              <div className={styles.resultPanel}>
                <div className={styles.resultHeader}>
                  <strong>Kết quả truy vấn</strong>
                  <span>
                    {queryResult.result.rowCount} dòng ·{" "}
                    {queryResult.result.executionTimeMs} ms
                  </span>
                </div>

                {queryResult.result.error ? (
                  <p className={styles.queryError}>
                    {queryResult.result.error}
                  </p>
                ) : (
                  <>
                    <div className={styles.resultTableWrapper}>
                      <table className={styles.resultTable}>
                        <thead>
                          <tr>
                            {queryResult.result.columns.map((column) => (
                              <th key={column}>{column}</th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {queryResult.result.rows.map((row, index) => (
                            <tr key={index}>
                              {queryResult.result.columns.map((column) => (
                                <td key={column}>
                                  {String(row[column] ?? "")}
                                </td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {queryResult.summary && (
                      <p className={styles.summary}>{queryResult.summary}</p>
                    )}

                    <div className={styles.resultActions}>
                      <span>
                        {queryResult.attemptCount > 1
                          ? `Đã tự sửa và thử lại ${queryResult.attemptCount}/3 lần`
                          : "SQL chạy ngay ở lần thử đầu tiên"}
                      </span>
                      <button
                        type="button"
                        onClick={() => void exportResult()}
                        disabled={exporting}
                      >
                        {exporting ? "Đang xuất…" : "⬇ Xuất Excel"}
                      </button>
                    </div>

                    <div className={styles.sqlToolsRow}>
                      <button
                        type="button"
                        onClick={() =>
                          void toggleExplain(
                            queryResult.messageId,
                            queryResult.generatedSql,
                          )
                        }
                      >
                        {explainOpenId === queryResult.messageId
                          ? "▲ Đóng giải thích"
                          : "🔍 Giải thích SQL"}
                      </button>
                      {canOptimize && (
                        <button
                          type="button"
                          onClick={() =>
                            void toggleOptimize(
                              queryResult.messageId,
                              queryResult.generatedSql,
                            )
                          }
                        >
                          {optimizeOpenId === queryResult.messageId
                            ? "▲ Đóng tối ưu"
                            : "⚡ Tối ưu SQL"}
                        </button>
                      )}
                    </div>
                    {renderExplainPanel(queryResult.messageId)}
                    {renderOptimizePanel(queryResult.messageId)}

                    {queryResult.dataInsight && (
                      <div className={styles.insightPanel}>
                        <strong>Nhận định dữ liệu</strong>
                        <p>{queryResult.dataInsight.summary}</p>

                        <dl>
                          <div>
                            <dt>Cao nhất</dt>
                            <dd>
                              {queryResult.dataInsight.highestLabel}:{" "}
                              {queryResult.dataInsight.highestValue}
                            </dd>
                          </div>

                          <div>
                            <dt>Thấp nhất</dt>
                            <dd>
                              {queryResult.dataInsight.lowestLabel}:{" "}
                              {queryResult.dataInsight.lowestValue}
                            </dd>
                          </div>

                          {queryResult.dataInsight.growthPercent !== null && (
                            <div>
                              <dt>Tăng trưởng</dt>
                              <dd>
                                {queryResult.dataInsight.growthPercent > 0
                                  ? "+"
                                  : ""}
                                {queryResult.dataInsight.growthPercent}%
                                {queryResult.dataInsight.periodStartLabel &&
                                queryResult.dataInsight.periodEndLabel
                                  ? ` (${queryResult.dataInsight.periodStartLabel} → ${queryResult.dataInsight.periodEndLabel})`
                                  : ""}
                              </dd>
                            </div>
                          )}

                          {queryResult.dataInsight.trend && (
                            <div>
                              <dt>Xu hướng</dt>
                              <dd>
                                {queryResult.dataInsight.trend === "UP"
                                  ? "↗ Tăng"
                                  : queryResult.dataInsight.trend === "DOWN"
                                    ? "↘ Giảm"
                                    : "→ Ổn định"}
                              </dd>
                            </div>
                          )}

                          {queryResult.dataInsight.topSharePercent !== null && (
                            <div>
                              <dt>Tỷ trọng cao nhất</dt>
                              <dd>
                                {queryResult.dataInsight.topShareLabel}:{" "}
                                {queryResult.dataInsight.topSharePercent}%
                              </dd>
                            </div>
                          )}
                        </dl>

                        {queryResult.dataInsight.anomalies.length > 0 && (
                          <div className={styles.anomalies}>
                            <strong>Bất thường phát hiện</strong>
                            <ul>
                              {queryResult.dataInsight.anomalies.map(
                                (anomaly, index) => (
                                  <li key={`${anomaly.label}-${index}`}>
                                    <span>{anomaly.label}</span>
                                    <strong>{anomaly.value}</strong>
                                    {anomaly.direction && (
                                      <small>{anomaly.direction}</small>
                                    )}
                                  </li>
                                ),
                              )}
                            </ul>
                          </div>
                        )}
                      </div>
                    )}

                    {queryResult.chartSuggestion &&
                      queryResult.chartSuggestion.chartType !== "TABLE" && (
                        <div className={styles.chartPanel}>
                          <strong>
                            Biểu đồ đề xuất:{" "}
                            {queryResult.chartSuggestion.chartType}
                          </strong>

                          <p className={styles.chartReason}>
                            {queryResult.chartSuggestion.reason}
                          </p>

                          {queryResult.chartSuggestion.xAxisColumn && (
                            <small className={styles.chartAxis}>
                              Trục X: {queryResult.chartSuggestion.xAxisColumn}
                            </small>
                          )}

                          <div className={styles.chartContainer}>
                            {renderChart()}
                          </div>
                        </div>
                      )}
                  </>
                )}
              </div>
            )}

            <div ref={endRef} />
          </div>

          <form className={styles.composer} onSubmit={sendQuestion}>
            <textarea
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              maxLength={2000}
              placeholder="Hỏi bằng tiếng Việt hoặc tiếng Anh…"
              rows={3}
              disabled={sending || executing || connectionId === null}
            />
            <div>
              <small>{question.length}/2000 · Enter xuống dòng</small>
              <button
                type="submit"
                disabled={sending || executing || !question.trim()}
              >
                {sending ? "AI đang tạo SQL…" : "Tạo SQL preview"}
              </button>
            </div>
          </form>
        </section>
      </div>
    </div>
  );
}
