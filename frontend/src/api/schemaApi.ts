import { apiRequest } from './client'
import type { DatabaseSchema } from './types'

export const schemaApi = {
  get: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/schema/connections/${connectionId}`),
  sync: (connectionId: number) =>
    apiRequest<DatabaseSchema>(`/schema/connections/${connectionId}/sync`, { method: 'POST' }),
}
