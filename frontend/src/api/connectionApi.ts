import { apiRequest } from './client'
import type { ConnectionPayload, ConnectionTestResult, DatabaseConnection } from './types'

export const connectionApi = {
  list: () => apiRequest<DatabaseConnection[]>('/connections'),
  get: (id: number) => apiRequest<DatabaseConnection>(`/connections/${id}`),
  test: (payload: ConnectionPayload) => apiRequest<ConnectionTestResult>('/connections/test', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  create: (payload: ConnectionPayload) => apiRequest<DatabaseConnection>('/connections', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  update: (id: number, payload: Omit<ConnectionPayload, 'dbType'>) =>
    apiRequest<DatabaseConnection>(`/connections/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  reconnect: (id: number) => apiRequest<ConnectionTestResult>(`/connections/${id}/reconnect`, {
    method: 'POST',
  }),
  disconnect: (id: number) => apiRequest<void>(`/connections/${id}`, { method: 'DELETE' }),
}
