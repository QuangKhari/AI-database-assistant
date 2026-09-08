import { apiRequest } from "./client";
import type {
  AdminConnection,
  AdminStats,
  AdminUser,
  AdminUserPage,
} from "./types";

const PAGE_SIZE = 20;
function toPage(
  users: AdminUser[],
  page: number,
  size = PAGE_SIZE,
): AdminUserPage {
  const totalElements = users.length;
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
  const start = page * size;
  return {
    content: users.slice(start, start + size),
    page,
    size,
    totalElements,
    totalPages,
  };
}
export type UserRole = "USER" | "ADMIN";

export const adminApi = {
  stats: () => apiRequest<AdminStats>("/admin/stats"),

  users: async (
    search: string,
    page: number,
    size = PAGE_SIZE,
  ): Promise<AdminUserPage> => {
    const keyword = search.trim();
    const list = keyword
      ? await apiRequest<AdminUser[]>(
          `/admin/users/search?keyword=${encodeURIComponent(keyword)}`,
        )
      : await apiRequest<AdminUser[]>("/admin/users");
    return toPage(list, page, size);
  },

  lock: (id: number) =>
    apiRequest<AdminUser>(`/admin/users/${id}/lock`, { method: "PATCH" }),
  unlock: (id: number) =>
    apiRequest<AdminUser>(`/admin/users/${id}/unlock`, { method: "PATCH" }),

  connections: () => apiRequest<AdminConnection[]>("/admin/connections"),
  deleteConnection: (id: number) =>
    apiRequest<void>(`/admin/connections/${id}`, { method: "DELETE" }),

  updateRole: (id: number, role: string) =>
    apiRequest<AdminUser>(`/admin/users/${id}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    }),
};
