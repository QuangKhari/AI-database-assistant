import { apiRequest } from "./client";
import type {
  AdminConnection,
  AdminStats,
  AdminUser,
  AdminUserPage,
} from "./types";

const PAGE_SIZE = 20;

// BE (AdminController) chỉ có:
//   GET  /admin/users                  -> List<AdminUserResponse>
//   GET  /admin/users/search?keyword=  -> List<AdminUserResponse>
//   PATCH /admin/users/{id}/lock | /unlock
//   GET  /admin/connections
//   DELETE /admin/connections/{id}
//   GET  /admin/stats
// Không có phân trang server-side, nên FE tự cắt trang từ List trả về (đủ
// dùng cho MVP; nếu dữ liệu lớn, chuyển sang Phase 10 "pagination
// server-side" như kế hoạch đã ghi ở mục 9).
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
