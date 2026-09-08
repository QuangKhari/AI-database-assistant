import { apiRequest } from "./client";
import type { DatabaseSchema } from "./types";

export const schemaApi = {
  /**
   * Get existing schema metadata.
   */
  get: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/schema/connections/${connectionId}`),

  /**
   * Synchronize schema metadata from database.
   */
  sync: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/schema/connections/${connectionId}/sync`, {
      method: "POST",
    }),

  /**
   * Update table description.
   */
  updateTableDescription: (tableId: number, description: string) =>
    apiRequest<void>(`/schema/tables/${tableId}`, {
      method: "PUT",
      body: JSON.stringify({
        description,
      }),
    }),

  /**
   * Update column description.
   */
  updateColumnDescription: (columnId: number, description: string) =>
    apiRequest<void>(`/schema/columns/${columnId}`, {
      method: "PUT",
      body: JSON.stringify({
        description,
      }),
    }),
};
