import { apiRequest } from "./client";
import type { DatabaseSchema } from "./types";

export const schemaApi = {
  get: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/schema/connections/${connectionId}`),

  sync: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/connections/${connectionId}/schema`, {
      method: "POST",
    }),

  updateTableDescription: (tableId: number, description: string) =>
    apiRequest<void>(`/schema/tables/${tableId}`, {
      method: "PUT",
      body: JSON.stringify({ description }),
    }),

  updateColumnDescription: (columnId: number, description: string) =>
    apiRequest<void>(`/schema/columns/${columnId}`, {
      method: "PUT",
      body: JSON.stringify({ description }),
    }),
};
