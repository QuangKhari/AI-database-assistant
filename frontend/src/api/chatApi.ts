import { apiRequest, apiRequestBlob, apiRequestSse } from "./client";
import type {
  ChartSuggestion,
  ChartSuggestionRequest,
  QueryStreamEvent,
  ChatMessage,
  DataInsight,
  DataInsightRequest,
  ChatPreviewResult,
  Conversation,
  ExplainSqlRequest,
  ExplainSqlResponse,
  OptimizeSqlRequest,
  OptimizeSqlResponse,
  PageResult,
  QueryResponse,
} from "./types";

export const chatApi = {
  conversations: (connectionId: number) =>
    apiRequest<Conversation[]>(`/conversations?connectionId=${connectionId}`),

  // MỚI: phân trang sidebar hội thoại - khớp GET /api/conversations/paged
  // (ConversationController.java). BE sort theo updatedAt desc nên trang 0
  // luôn là các cuộc trò chuyện vừa cập nhật gần nhất.
  conversationsPaged: (connectionId: number, page: number, size: number) =>
    apiRequest<PageResult<Conversation>>(
      `/conversations/paged?connectionId=${connectionId}&page=${page}&size=${size}`,
    ),

  messages: (conversationId: number) =>
    apiRequest<ChatMessage[]>(`/conversations/${conversationId}/messages`),

  preview: (payload: {
    databaseConnectionId: number;
    conversationId?: number;
    question: string;
  }) =>
    apiRequest<ChatPreviewResult>("/query/preview", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  execute: (payload: {
    databaseConnectionId: number;
    conversationId?: number;
    question: string;
  }) =>
    apiRequest<QueryResponse>("/query/execute", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  explain: (payload: ExplainSqlRequest) =>
    apiRequest<ExplainSqlResponse>("/query/explain", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  optimize: (payload: OptimizeSqlRequest) =>
    apiRequest<OptimizeSqlResponse>("/query/optimize", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  chartSuggestion: (payload: ChartSuggestionRequest) =>
    apiRequest<ChartSuggestion>("/query/chart-suggestion", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  dataInsight: (payload: DataInsightRequest) =>
    apiRequest<DataInsight>("/query/data-insight", {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  removeConversation: (conversationId: number) =>
    apiRequest<void>(`/conversations/${conversationId}`, {
      method: "DELETE",
    }),

  exportExcel: (payload: {
    columns: string[];
    rows: Record<string, unknown>[];
  }) =>
    apiRequestBlob("/query/export/excel", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  executeStream: (
    payload: {
      databaseConnectionId: number;
      conversationId?: number;
      question: string;
    },
    onEvent: (event: QueryStreamEvent) => void,
  ) =>
    apiRequestSse(
      "/query/execute/stream",
      {
        method: "POST",
        body: JSON.stringify(payload),
      },
      ({ event, data }) => {
        try {
          const parsed = JSON.parse(data);

          if (event === "STATUS") {
            onEvent({
              type: "STATUS",
              message:
                typeof parsed === "string"
                  ? parsed
                  : typeof parsed?.message === "string"
                    ? parsed.message
                    : data,
            });
            return;
          }

          if (event === "result") {
            onEvent({
              type: "result",
              data: parsed,
            });
            return;
          }

          if (event === "error") {
            onEvent({
              type: "error",
              message:
                typeof parsed?.message === "string" ? parsed.message : data,
            });
          }
        } catch {
          if (event === "STATUS") {
            onEvent({
              type: "STATUS",
              message: data,
            });
            return;
          }

          if (event === "error") {
            onEvent({
              type: "error",
              message: data,
            });
          }
        }
      },
    ),
};
