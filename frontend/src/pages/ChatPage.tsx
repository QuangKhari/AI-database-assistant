import { useCallback, useEffect, useRef, useState } from "react";

import { chatApi } from "../api/chatApi";
import { connectionApi } from "../api/connectionApi";

import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";

import type {
  ChartSuggestion,
  ChatMessage,
  Conversation,
  DatabaseConnection,
  DataInsight,
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

/**
 * Số cuộc trò chuyện hiển thị mỗi trang ở sidebar
 * (GET /api/conversations/paged).
 */
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

  /**
   * Mỗi messageId giữ một QueryResponse riêng.
   *
   * Ví dụ:
   *
   * {
   *   101: result của câu hỏi 1,
   *   105: result của câu hỏi 2,
   *   109: result của câu hỏi 3
   * }
   *
   * Nhờ vậy khi hỏi câu mới, kết quả cũ không bị ghi đè.
   */
  const [queryResults, setQueryResults] = useState<
    Record<number, QueryResponse>
  >({});

  const [suggestedQuestions, setSuggestedQuestions] = useState<string[]>([]);
  const [refreshingSuggestions, setRefreshingSuggestions] = useState(false);

  /**
   * Giải thích SQL
   * Khóa theo messageId để mỗi message có panel riêng.
   */
  const [explainOpenId, setExplainOpenId] = useState<number | null>(null);
  const [explainLoadingId, setExplainLoadingId] = useState<number | null>(null);

  const [explainCache, setExplainCache] = useState<
    Record<number, ExplainSqlResponse>
  >({});

  /**
   * Tối ưu SQL
   * Chỉ hỗ trợ MySQL.
   */
  const [optimizeOpenId, setOptimizeOpenId] = useState<number | null>(null);
  const [optimizeLoadingId, setOptimizeLoadingId] = useState<number | null>(
    null,
  );

  const [optimizeCache, setOptimizeCache] = useState<
    Record<number, OptimizeSqlResponse>
  >({});

  /**
   * Vẽ biểu đồ + phân tích dữ liệu (chart suggestion + data insight).
   * Khóa theo messageId, giống explainCache/optimizeCache.
   */
  const [chartOpenId, setChartOpenId] = useState<number | null>(null);
  const [chartLoadingId, setChartLoadingId] = useState<number | null>(null);

  const [chartCache, setChartCache] = useState<
    Record<
      number,
      {
        chartSuggestion: ChartSuggestion;
        dataInsight: DataInsight | null;
      }
    >
  >({});

  const [exporting, setExporting] = useState(false);

  const endRef = useRef<HTMLDivElement>(null);

  /**
   * Load connections.
   */
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

  /**
   * Load conversations theo pagination.
   */
  const loadConversations = useCallback(
    async (selectedConnectionId: number, page = 0) => {
      setConversationsLoading(true);

      try {
        let result = await chatApi.conversationsPaged(
          selectedConnectionId,
          page,
          CONVERSATIONS_PAGE_SIZE,
        );

        /**
         * Trang vừa xóa hết item cuối cùng và không phải trang đầu
         * -> lùi về trang trước.
         */
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
    [handleError, setError],
  );

  /**
   * Khi đổi connection:
   *
   * - reset conversation hiện tại
   * - reset messages
   * - reset preview
   * - reset query results
   * - load conversations
   * - load suggested questions
   */
  useEffect(() => {
    setConversationId(null);
    setMessages([]);
    setPreview(null);
    setPreviewQuestion("");
    setQueryResults({});
    setStreamStatus("");

    setSuggestedQuestions([]);

    setExplainOpenId(null);
    setExplainLoadingId(null);
    setExplainCache({});

    setOptimizeOpenId(null);
    setOptimizeLoadingId(null);
    setOptimizeCache({});

    if (connectionId !== null) {
      void loadConversations(connectionId);

      /**
       * Lỗi suggestions không được làm hỏng Chat.
       */
      connectionApi
        .suggestedQuestions(connectionId)
        .then((result) => setSuggestedQuestions(result.questions))
        .catch(() => setSuggestedQuestions([]));
    }
  }, [connectionId, loadConversations]);

  /**
   * Auto scroll khi có message / preview / result mới.
   */
  useEffect(() => {
    if (typeof endRef.current?.scrollIntoView === "function") {
      endRef.current.scrollIntoView({
        behavior: "smooth",
      });
    }
  }, [messages, preview, queryResults]);

  /**
   * Mở một conversation.
   *
   * QueryResults được reset vì result của conversation cũ
   * không nên hiển thị nhầm sang conversation mới.
   */
  async function openConversation(id: number) {
    setConversationId(id);

    setPreview(null);
    setPreviewQuestion("");

    setQueryResults({});

    setExplainOpenId(null);
    setExplainLoadingId(null);
    setExplainCache({});

    setOptimizeOpenId(null);
    setOptimizeLoadingId(null);
    setOptimizeCache({});

    setLoading(true);

    try {
      const loadedMessages = await chatApi.messages(id);

      setMessages(loadedMessages);

      /*
       * Khôi phục các QueryResponse đã được backend lưu theo từng
       * assistant message. Không chạy SQL/Gemini lại.
       */
      const restoredResults: Record<number, QueryResponse> = {};

      for (const message of loadedMessages) {
        if (message.role === "assistant" && message.queryResult) {
          restoredResults[message.id] = message.queryResult;
        }
      }

      setQueryResults(restoredResults);

      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được nội dung hội thoại.");
    } finally {
      setLoading(false);
    }
  }

  /**
   * Tạo SQL Preview.
   *
   * QUAN TRỌNG:
   * Không xóa queryResults ở đây.
   *
   * Vì người dùng hỏi câu mới thì kết quả của câu trước
   * vẫn phải được giữ lại.
   */
  async function sendQuestion(event: React.FormEvent) {
    event.preventDefault();

    const cleanQuestion = question.trim();

    if (!cleanQuestion || connectionId === null) {
      return;
    }

    setSending(true);
    setPreview(null);

    try {
      const result = await chatApi.preview({
        databaseConnectionId: connectionId,
        ...(conversationId ? { conversationId } : {}),
        question: cleanQuestion,
      });

      setQuestion("");

      /**
       * Thao tác ghi (xoá/sửa/thêm/INSERT/UPDATE/DELETE...) bị chặn
       * ngay từ Preview.
       *
       * KHÔNG hiện khối "SQL preview" (không có SQL nào để hiện) -
       * chỉ cần một thông báo (toast) rồi dừng lại, không cần đi tiếp
       * qua luồng hỏi-đáp bình thường (không có nút "Thực thi SQL").
       */
      if (result.blocked) {
        showToast(
          result.errorMessage || "Không được phép thực hiện thao tác này.",
          "error",
        );
        return;
      }

      setPreviewQuestion(cleanQuestion);
      setPreview(result);

      if (!result.valid) {
        showToast(
          result.errorMessage || "SQL chưa vượt qua kiểm tra an toàn.",
          "error",
        );
      }
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

  /**
   * Execute SQL.
   *
   * Hướng A:
   *
   * Preview
   *   ↓
   * generatedSql
   *   ↓
   * Execute
   *   ↓
   * result
   *   ↓
   * summary background
   *
   * Không generate SQL lần 2.
   */
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

          /**
           * Dùng chính SQL đã tạo ở bước Preview.
           *
           * Backend không cần gọi Gemini tạo SQL lần 2.
           */
          generatedSql: preview.generatedSql,
        },

        (event) => {
          /**
           * STATUS
           */
          if (event.type === "STATUS") {
            setStreamStatus(event.message);
            return;
          }

          /**
           * RESULT
           *
           * Mỗi QueryResponse được lưu theo messageId.
           *
           * Đây là phần quan trọng nhất của bản sửa.
           */
          if (event.type === "result") {
            streamResult = event.data;

            setQueryResults((current) => ({
              ...current,
              [event.data.messageId]: event.data,
            }));

            setConversationId(event.data.conversationId);

            return;
          }

          /**
           * SUMMARY
           *
           * Summary được backend gửi sau khi result đã hiển thị.
           *
           * Không tạo QueryResponse mới.
           * Chỉ update summary của đúng message.
           */
          if (event.type === "summary") {
            const resultMessageId = streamResult?.messageId;

            if (resultMessageId !== undefined && resultMessageId !== null) {
              setQueryResults((current) => {
                const existing = current[resultMessageId];

                if (!existing) {
                  return current;
                }

                return {
                  ...current,
                  [resultMessageId]: {
                    ...existing,
                    summary: event.summary,
                  },
                };
              });
            }

            return;
          }

          /**
           * ERROR
           */
          streamError = event.message;
          setStreamStatus(event.message);
        },
      );

      if (streamError) {
        throw new Error(streamError);
      }

      /**
       * Chỉ yêu cầu RESULT.
       *
       * Summary có thể đến background.
       */
      if (!streamResult) {
        throw new Error(
          "Stream kết thúc nhưng không nhận được kết quả truy vấn.",
        );
      }

      const finalResult = streamResult as QueryResponse;

      /**
       * Reload messages để assistant message được lưu vào conversation.
       */
      const loadedMessages = await chatApi.messages(finalResult.conversationId);

      setMessages(loadedMessages);

      /*
       * Backend đã persist QueryResponse. Đồng bộ lại map từ response
       * mới nhất để đảm bảo result vẫn tồn tại sau khi reload messages.
       */
      const restoredResults: Record<number, QueryResponse> = {};

      for (const message of loadedMessages) {
        if (message.role === "assistant" && message.queryResult) {
          restoredResults[message.id] = message.queryResult;
        }
      }

      setQueryResults(restoredResults);

      /**
       * Preview không còn cần nữa sau execute.
       */
      setPreview(null);
      setPreviewQuestion("");

      /**
       * Refresh sidebar.
       */
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

  /**
   * Refresh suggested questions.
   */
  async function refreshSuggestedQuestions() {
    if (connectionId === null) {
      return;
    }

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

  /**
   * Export một QueryResponse cụ thể.
   *
   * Không còn phụ thuộc vào queryResult global.
   */
  async function exportResult(result: QueryResponse) {
    if (result.result.error) {
      return;
    }

    setExporting(true);

    try {
      const { blob, filename } = await chatApi.exportExcel({
        columns: result.result.columns,
        rows: result.result.rows,
      });

      const url = URL.createObjectURL(blob);

      const link = document.createElement("a");
      link.href = url;
      link.download = filename;

      document.body.appendChild(link);
      link.click();
      link.remove();

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

  /**
   * Xóa conversation.
   */
  async function removeConversation(item: Conversation) {
    if (!window.confirm(`Xóa cuộc trò chuyện "${item.title}"?`)) {
      return;
    }

    try {
      await chatApi.removeConversation(item.id);

      if (conversationId === item.id) {
        setConversationId(null);
        setMessages([]);
        setPreview(null);
        setPreviewQuestion("");
        setQueryResults({});

        setExplainOpenId(null);
        setExplainLoadingId(null);
        setExplainCache({});

        setOptimizeOpenId(null);
        setOptimizeLoadingId(null);
        setOptimizeCache({});
      }

      if (connectionId !== null) {
        await loadConversations(connectionId, conversationsPage);
      }

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

  /**
   * Pagination conversation.
   */
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

  /**
   * Toggle "Giải thích SQL".
   *
   * Cache theo messageId.
   */
  async function toggleExplain(messageId: number, sql: string) {
    if (explainOpenId === messageId) {
      setExplainOpenId(null);
      return;
    }

    setExplainOpenId(messageId);
    setOptimizeOpenId(null);

    if (explainCache[messageId]) {
      return;
    }

    setExplainLoadingId(messageId);

    try {
      const result = await chatApi.explain({
        sql,
        ...(connectionId ? { databaseConnectionId: connectionId } : {}),
      });

      setExplainCache((prev) => ({
        ...prev,
        [messageId]: result,
      }));
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

  /**
   * Toggle "Tối ưu SQL".
   *
   * Chỉ MySQL.
   */
  async function toggleOptimize(messageId: number, sql: string) {
    if (connectionId === null) {
      return;
    }

    if (optimizeOpenId === messageId) {
      setOptimizeOpenId(null);
      return;
    }

    setOptimizeOpenId(messageId);
    setExplainOpenId(null);

    if (optimizeCache[messageId]) {
      return;
    }

    setOptimizeLoadingId(messageId);

    try {
      const result = await chatApi.optimize({
        sql,
        databaseConnectionId: connectionId,
      });

      setOptimizeCache((prev) => ({
        ...prev,
        [messageId]: result,
      }));
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

  /**
   * Toggle "Vẽ biểu đồ" (chart suggestion + data insight).
   *
   * Gọi 2 endpoint stateless /query/chart-suggestion và
   * /query/data-insight song song, truyền lại đúng columns/rows của kết
   * quả truy vấn (không chạy lại SQL). Cache theo messageId - bấm lại
   * chỉ đóng/mở panel, không gọi lại API.
   */
  async function toggleChart(messageId: number, result: QueryResponse) {
    if (chartOpenId === messageId) {
      setChartOpenId(null);
      return;
    }

    setChartOpenId(messageId);
    setExplainOpenId(null);
    setOptimizeOpenId(null);

    if (chartCache[messageId]) {
      return;
    }

    setChartLoadingId(messageId);

    try {
      const payload = {
        columns: result.result.columns,
        rows: result.result.rows,
        ...(connectionId ? { connectionId } : {}),
      };

      const [chartSuggestion, dataInsight] = await Promise.all([
        chatApi.chartSuggestion(payload),
        chatApi.dataInsight(payload),
      ]);

      setChartCache((prev) => ({
        ...prev,
        [messageId]: { chartSuggestion, dataInsight },
      }));
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể tạo biểu đồ."),
        ),
        "error",
      );

      setChartOpenId(null);
    } finally {
      setChartLoadingId(null);
    }
  }

  /**
   * Connection không tồn tại.
   */
  if (!loading && connections.length === 0) {
    return (
      <section className={styles.emptyPage}>
        <h1>Chưa có connection hoạt động</h1>

        <p>Hãy tạo connection và đồng bộ schema trước khi Chat với AI.</p>
      </section>
    );
  }

  /**
   * Connection hiện tại.
   */
  const activeConnection = connections.find((item) => item.id === connectionId);

  /**
   * BE chỉ hỗ trợ optimize MySQL.
   */
  const canOptimize = activeConnection?.dbType === "mysql";

  /**
   * Kiểm tra một QueryResponse có đủ dữ liệu để biểu diễn
   * chart hay không.
   *
   * 1 dòng:
   *   -> Không chart.
   *
   * >= 2 dòng:
   *   -> Có thể chart nếu backend trả chartSuggestion.
   */
  function canShowChart(
    chart: ChartSuggestion | undefined,
    result: QueryResponse,
  ) {
    if (!chart) {
      return false;
    }

    if (result.result.error) {
      return false;
    }

    if (result.result.rows.length < 2) {
      return false;
    }

    if (chart.xAxisLabels.length < 2) {
      return false;
    }

    return true;
  }

  /**
   * Build chart data từ một ChartSuggestion đã lấy về (chartCache).
   */
  function buildChartData(chart: ChartSuggestion) {
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

  /**
   * Render chart từ một ChartSuggestion đã lấy về (chartCache).
   *
   * Không còn đọc result.chartSuggestion (backend luôn trả null) -
   * chart giờ là dữ liệu ON-DEMAND, xem toggleChart().
   */
  function renderChart(chart: ChartSuggestion) {
    const data = buildChartData(chart);

    /**
     * BAR
     */
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

    /**
     * LINE
     */
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

    /**
     * PIE
     */
    if (chart.chartType === "PIE") {
      const series = chart.series[0];

      if (!series) {
        return null;
      }

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

    /**
     * DONUT
     */
    if (chart.chartType === "DONUT") {
      const series = chart.series[0];

      if (!series) {
        return null;
      }

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

    /**
     * TABLE chart.
     *
     * Giữ nguyên chức năng cũ.
     */
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

  /**
   * Copy clipboard.
   */
  function copyToClipboard(text: string) {
    navigator.clipboard?.writeText(text).then(
      () => showToast("Đã sao chép vào clipboard.", "success"),
      () => showToast("Không thể sao chép.", "error"),
    );
  }

  /**
   * Render Explain panel.
   */
  function renderExplainPanel(messageId: number) {
    if (explainOpenId !== messageId) {
      return null;
    }

    if (explainLoadingId === messageId) {
      return (
        <div className={styles.aiPanel}>
          <p className={styles.center}>Đang giải thích SQL…</p>
        </div>
      );
    }

    const data = explainCache[messageId];

    if (!data) {
      return null;
    }

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

  /**
   * Render Optimize panel.
   */
  function renderOptimizePanel(messageId: number) {
    if (optimizeOpenId !== messageId) {
      return null;
    }

    if (optimizeLoadingId === messageId) {
      return (
        <div className={styles.aiPanel}>
          <p className={styles.center}>Đang phân tích EXPLAIN và tối ưu SQL…</p>
        </div>
      );
    }

    const data = optimizeCache[messageId];

    if (!data) {
      return null;
    }

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

  /**
   * Render panel "Vẽ biểu đồ" (data insight + chart).
   *
   * Giống renderExplainPanel/renderOptimizePanel: chỉ đọc từ chartCache
   * (được điền bởi toggleChart() khi người dùng bấm nút), KHÔNG còn đọc
   * result.dataInsight/result.chartSuggestion (backend luôn trả null từ
   * khi chuyển 2 tính năng này thành on-demand).
   */
  function renderChartPanel(messageId: number, result: QueryResponse) {
    if (chartOpenId !== messageId) {
      return null;
    }

    if (chartLoadingId === messageId) {
      return (
        <div className={styles.aiPanel}>
          <p className={styles.center}>Đang tạo biểu đồ và phân tích…</p>
        </div>
      );
    }

    const data = chartCache[messageId];

    if (!data) {
      return null;
    }

    const { chartSuggestion, dataInsight } = data;

    return (
      <>
        {/* =========================
            DATA INSIGHT
           ========================= */}
        {dataInsight && (
          <div className={styles.insightPanel}>
            <strong>Nhận định dữ liệu</strong>

            <p>{dataInsight.summary}</p>

            <dl>
              <div>
                <dt>Cao nhất</dt>

                <dd>
                  {dataInsight.highestLabel}: {dataInsight.highestValue}
                </dd>
              </div>

              <div>
                <dt>Thấp nhất</dt>

                <dd>
                  {dataInsight.lowestLabel}: {dataInsight.lowestValue}
                </dd>
              </div>

              {dataInsight.growthPercent !== null && (
                <div>
                  <dt>Tăng trưởng</dt>

                  <dd>
                    {dataInsight.growthPercent > 0 ? "+" : ""}
                    {dataInsight.growthPercent}%
                    {dataInsight.periodStartLabel && dataInsight.periodEndLabel
                      ? ` (${dataInsight.periodStartLabel} → ${dataInsight.periodEndLabel})`
                      : ""}
                  </dd>
                </div>
              )}

              {dataInsight.trend && (
                <div>
                  <dt>Xu hướng</dt>

                  <dd>
                    {dataInsight.trend === "UP"
                      ? "↗ Tăng"
                      : dataInsight.trend === "DOWN"
                        ? "↘ Giảm"
                        : "→ Ổn định"}
                  </dd>
                </div>
              )}

              {dataInsight.topSharePercent !== null && (
                <div>
                  <dt>Tỷ trọng cao nhất</dt>

                  <dd>
                    {dataInsight.topShareLabel}: {dataInsight.topSharePercent}%
                  </dd>
                </div>
              )}
            </dl>

            {dataInsight.anomalies.length > 0 && (
              <div className={styles.anomalies}>
                <strong>Bất thường phát hiện</strong>

                <ul>
                  {dataInsight.anomalies.map((anomaly, index) => (
                    <li key={`${anomaly.label}-${index}`}>
                      <span>{anomaly.label}</span>

                      <strong>{anomaly.value}</strong>

                      {anomaly.direction && <small>{anomaly.direction}</small>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}

        {/* =========================
            CHART

            QUAN TRỌNG:
            Chỉ hiện nếu có >= 2 rows.

            1 row:
            -> không chart
            -> không chart reason
           ========================= */}
        {canShowChart(chartSuggestion, result) &&
          chartSuggestion.chartType !== "TABLE" && (
            <div className={styles.chartPanel}>
              <strong>Biểu đồ đề xuất: {chartSuggestion.chartType}</strong>

              <p className={styles.chartReason}>{chartSuggestion.reason}</p>

              {chartSuggestion.xAxisColumn && (
                <small className={styles.chartAxis}>
                  Trục X: {chartSuggestion.xAxisColumn}
                </small>
              )}

              <div className={styles.chartContainer}>
                {renderChart(chartSuggestion)}
              </div>
            </div>
          )}
      </>
    );
  }

  /**
   * Render QueryResult của một message.
   *
   * Đây là phần sửa lớn nhất:
   *
   * queryResults[message.id]
   *
   * thay vì queryResult global.
   */
  function renderQueryResult(result: QueryResponse) {
    return (
      <div className={styles.resultPanel}>
        <div className={styles.resultHeader}>
          <strong>Kết quả truy vấn</strong>

          <span>
            {result.result.rowCount} dòng · {result.result.executionTimeMs} ms
          </span>
        </div>

        {result.result.error ? (
          <p className={styles.queryError}>{result.result.error}</p>
        ) : (
          <>
            {/* =========================
                RESULT TABLE
               ========================= */}
            <div className={styles.resultTableWrapper}>
              <table className={styles.resultTable}>
                <thead>
                  <tr>
                    {result.result.columns.map((column) => (
                      <th key={column}>{column}</th>
                    ))}
                  </tr>
                </thead>

                <tbody>
                  {result.result.rows.map((row, index) => (
                    <tr key={index}>
                      {result.result.columns.map((column) => (
                        <td key={column}>{String(row[column] ?? "")}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* =========================
                SUMMARY
               ========================= */}
            {result.summary && (
              <p className={styles.summary}>{result.summary}</p>
            )}

            {/* =========================
                RESULT ACTIONS
               ========================= */}
            <div className={styles.resultActions}>
              <span>
                {result.attemptCount > 1
                  ? `Đã tự sửa và thử lại ${result.attemptCount}/3 lần`
                  : "SQL chạy ngay ở lần thử đầu tiên"}
              </span>

              <button
                type="button"
                onClick={() => void exportResult(result)}
                disabled={exporting}
              >
                {exporting ? "Đang xuất…" : "⬇ Xuất Excel"}
              </button>
            </div>

            {/* =========================
                SQL TOOLS
               ========================= */}
            <div className={styles.sqlToolsRow}>
              <button
                type="button"
                onClick={() =>
                  void toggleExplain(result.messageId, result.generatedSql)
                }
              >
                {explainOpenId === result.messageId
                  ? "▲ Đóng giải thích"
                  : "🔍 Giải thích SQL"}
              </button>

              {canOptimize && (
                <button
                  type="button"
                  onClick={() =>
                    void toggleOptimize(result.messageId, result.generatedSql)
                  }
                >
                  {optimizeOpenId === result.messageId
                    ? "▲ Đóng tối ưu"
                    : "⚡ Tối ưu SQL"}
                </button>
              )}

              {!result.result.error && result.result.rows.length >= 2 && (
                <button
                  type="button"
                  onClick={() => void toggleChart(result.messageId, result)}
                >
                  {chartOpenId === result.messageId
                    ? "▲ Đóng biểu đồ"
                    : "📊 Vẽ biểu đồ"}
                </button>
              )}
            </div>

            {/* =========================
                EXPLAIN
               ========================= */}
            {renderExplainPanel(result.messageId)}

            {/* =========================
                OPTIMIZE
               ========================= */}
            {renderOptimizePanel(result.messageId)}

            {/* =========================
                VẼ BIỂU ĐỒ (data insight + chart)

                Đã chuyển thành on-demand - xem toggleChart()/
                renderChartPanel(). Chỉ hiện khi người dùng bấm nút
                "📊 Vẽ biểu đồ" ở SQL TOOLS phía trên.
               ========================= */}
            {renderChartPanel(result.messageId, result)}
          </>
        )}
      </div>
    );
  }

  return (
    <div className={styles.page}>
      {/* =========================
          HEADER
         ========================= */}
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

      {/* =========================
          GLOBAL ERROR
         ========================= */}
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      <div className={styles.workspace}>
        {/* =========================
            SIDEBAR
           ========================= */}
        <aside className={styles.sidebar}>
          <button
            type="button"
            onClick={() => {
              setConversationId(null);
              setMessages([]);

              setPreview(null);
              setPreviewQuestion("");

              setQueryResults({});

              setExplainOpenId(null);
              setExplainLoadingId(null);
              setExplainCache({});

              setOptimizeOpenId(null);
              setOptimizeLoadingId(null);
              setOptimizeCache({});

              setStreamStatus("");
            }}
          >
            <span aria-hidden="true">+</span>
            Cuộc trò chuyện mới
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

        {/* =========================
            CHAT
           ========================= */}
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
              messages.map((message) => {
                /**
                 * Result tương ứng với message
                 * hiện tại.
                 */
                const messageResult =
                  message.role === "assistant"
                    ? queryResults[message.id]
                    : undefined;

                return (
                  <article
                    className={
                      message.role === "user"
                        ? styles.userMessage
                        : styles.assistantMessage
                    }
                    key={message.id}
                  >
                    <header>
                      <span className={styles.avatar} aria-hidden="true">
                        {message.role === "user" ? "B" : "AI"}
                      </span>
                      {message.role === "user" ? "Bạn" : "AI QueryMate"}
                    </header>

                    <p>{message.content}</p>

                    {/* =================
                          SQL MESSAGE
                         ================= */}
                    {message.generatedSql && (
                      <>
                        <div className={styles.sql}>
                          <div>
                            <span>SQL</span>

                            <small>Đã thực thi</small>
                          </div>

                          <pre>
                            <code>{message.generatedSql}</code>
                          </pre>
                        </div>

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
                      </>
                    )}

                    {renderExplainPanel(message.id)}

                    {renderOptimizePanel(message.id)}

                    {/* =================
                          QUERY RESULT
                         
                          Chỉ render result
                          của message này.
                         ================= */}
                    {messageResult && renderQueryResult(messageResult)}
                  </article>
                );
              })
            )}

            {/* =========================
                SUGGESTIONS
               ========================= */}
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

            {/* =========================
                STREAM STATUS
               ========================= */}
            {streamStatus && (
              <p className={styles.summary} role="status">
                {streamStatus}
              </p>
            )}

            {/* =========================
                SQL PREVIEW
               ========================= */}
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

            <div ref={endRef} />
          </div>

          {/* =========================
              COMPOSER
             ========================= */}
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
                {sending ? (
                  "AI đang tạo SQL…"
                ) : (
                  <>
                    Tạo SQL preview
                    <span aria-hidden="true">→</span>
                  </>
                )}
              </button>
            </div>
          </form>
        </section>
      </div>
    </div>
  );
}
