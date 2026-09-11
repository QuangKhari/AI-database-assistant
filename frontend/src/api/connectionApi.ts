import { apiRequest } from "./client";
import type {
  ConnectionPayload,
  ConnectionTestResult,
  DatabaseConnection,
  SuggestedQuestionsResult,
} from "./types";

export const connectionApi = {
  list: () => apiRequest<DatabaseConnection[]>("/connections"),
  get: (id: number) => apiRequest<DatabaseConnection>(`/connections/${id}`),
  test: (payload: ConnectionPayload) =>
    apiRequest<ConnectionTestResult>("/connections/test", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  create: (payload: ConnectionPayload) =>
    apiRequest<DatabaseConnection>("/connections", {
      method: "POST",
      body: JSON.stringify(payload),
    }),
  update: (id: number, payload: Omit<ConnectionPayload, "dbType">) =>
    apiRequest<DatabaseConnection>(`/connections/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    }),
  reconnect: (id: number) =>
    apiRequest<ConnectionTestResult>(`/connections/${id}/reconnect`, {
      method: "POST",
    }),
  disconnect: (id: number) =>
    apiRequest<void>(`/connections/${id}`, { method: "DELETE" }),

  uploadExcel: (file: File, name: string) => {
    const form = new FormData();
    form.append("file", file);
    form.append("name", name);
    return apiRequest<DatabaseConnection>("/connections/excel", {
      method: "POST",
      body: form,
    });
  },

  // MỚI: gợi ý câu hỏi cho 1 connection (GET /{id}/suggested-questions?refresh=)
  suggestedQuestions: (id: number, refresh = false) =>
    apiRequest<SuggestedQuestionsResult>(
      `/connections/${id}/suggested-questions?refresh=${refresh}`,
    ),
};
