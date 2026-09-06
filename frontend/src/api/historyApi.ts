import { apiRequest } from "./client";
import type {
  ChatMessage,
  Conversation,
  HistorySearchResult,
  PageResult,
} from "./types";

// Khớp HistoryController.java (/api/history)
export const historyApi = {
  list: () => apiRequest<Conversation[]>("/history"),
  detail: (conversationId: number) =>
    apiRequest<ChatMessage[]>(`/history/${conversationId}`),
  remove: (conversationId: number) =>
    apiRequest<void>(`/history/${conversationId}`, { method: "DELETE" }),
  removeAll: () => apiRequest<void>("/history", { method: "DELETE" }),

  togglePin: (messageId: number) =>
    apiRequest<ChatMessage>(`/history/messages/${messageId}/pin`, {
      method: "PATCH",
    }),

  pinned: () => apiRequest<ChatMessage[]>("/history/pinned"),

  search: (params: {
    keyword?: string;
    pinnedOnly?: boolean;
    page?: number;
    size?: number;
  }) => {
    const query = new URLSearchParams();
    if (params.keyword) query.set("keyword", params.keyword);
    if (params.pinnedOnly) query.set("pinnedOnly", "true");
    query.set("page", String(params.page ?? 0));
    query.set("size", String(params.size ?? 20));
    return apiRequest<PageResult<HistorySearchResult>>(
      `/history/search?${query}`,
    );
  },
};
