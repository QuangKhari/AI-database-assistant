import { apiRequest } from './client'
import type { QueryExecutionResponse } from './types'

export const queryApi = {
  execute: (payload: { assistantMessageId: number; timeoutSeconds?: number }) =>
    apiRequest<QueryExecutionResponse>('/query/execute', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}
