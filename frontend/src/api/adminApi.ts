import { apiRequest } from './client'
import type { AdminStats, AdminUser, AdminUserPage } from './types'

export const adminApi = {
  stats: () => apiRequest<AdminStats>('/admin/stats'),
  users: (search: string, page: number, size = 20) => {
    const params = new URLSearchParams({ search, page: String(page), size: String(size) })
    return apiRequest<AdminUserPage>(`/admin/users?${params}`)
  },
  lock: (id: number) => apiRequest<AdminUser>(`/admin/users/${id}/lock`, { method: 'PATCH' }),
  unlock: (id: number) => apiRequest<AdminUser>(`/admin/users/${id}/unlock`, { method: 'PATCH' }),
}
