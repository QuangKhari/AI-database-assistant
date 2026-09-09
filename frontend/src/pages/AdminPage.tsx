import { type FormEvent, useCallback, useEffect, useState } from "react";

import { adminApi } from "../api/adminApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type { AdminConnection, AdminStats, AdminUserPage } from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";

import styles from "./AdminPage.module.css";

export function AdminPage() {
  const { showToast } = useToast();

  const [stats, setStats] = useState<AdminStats | null>(null);
  const [users, setUsers] = useState<AdminUserPage | null>(null);
  const [connections, setConnections] = useState<AdminConnection[]>([]);

  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [workingId, setWorkingId] = useState<number | null>(null);
  const [deletingConnectionId, setDeletingConnectionId] = useState<
    number | null
  >(null);
  const { error, handleError, setError } = useApiError();

  /**
   * Đổi role USER <-> ADMIN
   *
   * BE:
   * PATCH /api/admin/users/{id}/role
   *
   * Body:
   * {
   *   "role": "ADMIN"
   * }
   */
  async function handleRoleChange(
    userId: number,
    username: string,
    role: "USER" | "ADMIN",
  ) {
    const verb =
      role === "ADMIN" ? "cấp quyền ADMIN cho" : "hạ quyền ADMIN của";

    if (
      !window.confirm(`Bạn chắc chắn muốn ${verb} tài khoản "${username}"?`)
    ) {
      return;
    }

    setWorkingId(userId);

    try {
      const updatedUser = await adminApi.updateRole(userId, role);

      /**
       * AdminUserPage chứa danh sách ở thuộc tính content,
       * không phải chính nó là một array.
       */
      setUsers((currentUsers) => {
        if (!currentUsers) {
          return currentUsers;
        }

        return {
          ...currentUsers,
          content: currentUsers.content.map((user) =>
            user.id === userId ? updatedUser : user,
          ),
        };
      });

      showToast(
        role === "ADMIN"
          ? `Đã cấp quyền ADMIN cho ${username}.`
          : `Đã hạ quyền ADMIN của ${username}.`,
        "success",
      );
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể cập nhật vai trò tài khoản."),
        ),
        "error",
      );
    } finally {
      setWorkingId(null);
    }
  }

  const load = useCallback(async () => {
    setLoading(true);

    try {
      const [nextStats, nextUsers, nextConnections] = await Promise.all([
        adminApi.stats(),
        adminApi.users(search, page),
        adminApi.connections(),
      ]);

      setStats(nextStats);
      setUsers(nextUsers);
      setConnections(nextConnections);
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được dữ liệu quản trị.");
    } finally {
      setLoading(false);
    }
  }, [page, search]);

  useEffect(() => {
    void load();
  }, [load]);

  function submitSearch(event: FormEvent) {
    event.preventDefault();

    setPage(0);
    setSearch(searchInput.trim());
  }

  async function toggleLock(userId: number, username: string, locked: boolean) {
    const verb = locked ? "mở khóa" : "khóa";

    if (
      !window.confirm(`Bạn chắc chắn muốn ${verb} tài khoản "${username}"?`)
    ) {
      return;
    }

    setWorkingId(userId);

    try {
      await (locked ? adminApi.unlock(userId) : adminApi.lock(userId));

      showToast(`Đã ${verb} tài khoản ${username}.`, "success");

      await load();
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, `Không thể ${verb} tài khoản.`),
        ),
        "error",
      );
    } finally {
      setWorkingId(null);
    }
  }

  /**
   * Xóa connection với tư cách admin.
   *
   * BE: DELETE /api/admin/connections/{id}
   *
   * Đây là thao tác admin cưỡng chế (không phải chủ sở hữu connection),
   * nên luôn confirm trước và cập nhật lại danh sách + stats sau khi xóa
   * để totalConnections không bị lệch với thực tế.
   */
  async function handleDeleteConnection(connection: AdminConnection) {
    if (
      !window.confirm(
        `Xóa vĩnh viễn connection "${connection.name}" (chủ sở hữu: ${connection.ownerUsername})? Hành động này không thể hoàn tác.`,
      )
    ) {
      return;
    }

    setDeletingConnectionId(connection.id);

    try {
      await adminApi.deleteConnection(connection.id);

      setConnections((current) =>
        current.filter((item) => item.id !== connection.id),
      );
      setStats((current) =>
        current
          ? { ...current, totalConnections: current.totalConnections - 1 }
          : current,
      );

      showToast(`Đã xóa connection "${connection.name}".`, "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể xóa connection."),
        ),
        "error",
      );
    } finally {
      setDeletingConnectionId(null);
    }
  }

  return (
    <div className={styles.page}>
      <header>
        <div>
          <p>Administration</p>

          <h1>Quản trị hệ thống</h1>

          <span>Theo dõi tổng quan và khóa/mở khóa tài khoản người dùng.</span>
        </div>

        <i>ADMIN</i>
      </header>

      {/*
        Khớp AdminStatsResponse.java thật:
        totalUsers / totalConnections /
        totalConversations / totalQueries

        BE không tách activeUsers/lockedUsers.
      */}
      <div className={styles.stats}>
        <article>
          <span>Tổng người dùng</span>
          <strong>{stats?.totalUsers ?? "—"}</strong>
        </article>

        <article>
          <span>Tổng connections</span>
          <strong>{stats?.totalConnections ?? "—"}</strong>
        </article>

        <article>
          <span>Tổng conversations</span>
          <strong>{stats?.totalConversations ?? "—"}</strong>
        </article>

        <article>
          <span>Tổng truy vấn</span>
          <strong>{stats?.totalQueries ?? "—"}</strong>
        </article>
      </div>

      <section className={styles.users}>
        <div className={styles.toolbar}>
          <div>
            <h2>Tài khoản người dùng</h2>

            <p>Admin không thể xem mật khẩu database hoặc dữ liệu truy vấn.</p>
          </div>

          <form onSubmit={submitSearch}>
            <input
              aria-label="Tìm user"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder="Username hoặc email…"
            />

            <button type="submit">Tìm</button>
          </form>
        </div>

        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}

        {loading ? (
          <p className={styles.loading}>Đang tải danh sách…</p>
        ) : users?.content.length === 0 ? (
          <p className={styles.empty}>Không tìm thấy tài khoản phù hợp.</p>
        ) : (
          <div className={styles.tableWrap}>
            <table>
              <thead>
                <tr>
                  <th>Người dùng</th>
                  <th>Vai trò</th>
                  <th>Trạng thái</th>
                  <th>Connections</th>
                  <th>Ngày đăng ký</th>
                  <th aria-label="Thao tác" />
                </tr>
              </thead>

              <tbody>
                {users?.content.map((user) => {
                  const isWorking = workingId === user.id;

                  return (
                    <tr key={user.id}>
                      <td>
                        <strong>{user.username}</strong>

                        <span>{user.email}</span>
                      </td>

                      {/* ============================
                          ROLE
                          ============================ */}
                      <td className={styles.roleCell}>
                        <select
                          aria-label={`Vai trò của ${user.username}`}
                          className={
                            user.role === "ADMIN"
                              ? styles.adminRole
                              : styles.userRole
                          }
                          value={user.role}
                          disabled={isWorking}
                          onChange={(event) =>
                            void handleRoleChange(
                              user.id,
                              user.username,
                              event.target.value as "USER" | "ADMIN",
                            )
                          }
                        >
                          <option value="USER">Người dùng</option>

                          <option value="ADMIN">Quản trị viên</option>
                        </select>
                      </td>

                      {/* ============================
                          LOCK STATUS
                          ============================ */}
                      <td>
                        <b
                          className={
                            user.locked ? styles.locked : styles.active
                          }
                        >
                          {user.locked ? "Đã khóa" : "Hoạt động"}
                        </b>
                      </td>

                      <td>{user.connectionCount}</td>

                      <td>
                        {new Date(user.createdAt).toLocaleDateString("vi-VN")}
                      </td>

                      {/* ============================
                          LOCK / UNLOCK
                          ============================ */}
                      <td>
                        <button
                          type="button"
                          disabled={isWorking}
                          className={user.locked ? styles.unlock : styles.lock}
                          onClick={() =>
                            void toggleLock(user.id, user.username, user.locked)
                          }
                        >
                          {isWorking
                            ? "Đang xử lý…"
                            : user.locked
                              ? "Mở khóa"
                              : "Khóa"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {users && users.totalPages > 1 && (
          <div className={styles.pagination}>
            <span>
              Trang {users.page + 1}/{users.totalPages} · {users.totalElements}{" "}
              users
            </span>

            <div>
              <button
                type="button"
                disabled={page === 0 || loading}
                onClick={() => setPage((value) => value - 1)}
              >
                Trước
              </button>

              <button
                type="button"
                disabled={page + 1 >= users.totalPages || loading}
                onClick={() => setPage((value) => value + 1)}
              >
                Sau
              </button>
            </div>
          </div>
        )}
      </section>

      {/*
        ============================================================
        QUẢN LÝ CONNECTIONS (admin)
        ============================================================
        Khớp AdminConnectionResponse.java (AdminConnection trong types.ts):
        id / name / dbType / host / databaseName / ownerUsername / createdAt.
        BE: GET /api/admin/connections, DELETE /api/admin/connections/{id}.
        Không phân trang server-side (danh sách toàn hệ thống, MVP).
      */}
      <section className={styles.users}>
        <div className={styles.toolbar}>
          <div>
            <h2>Connections trong hệ thống</h2>

            <p>
              Admin có thể xóa connection của bất kỳ người dùng nào khi cần (vi
              phạm, dữ liệu rác…). Admin không xem được mật khẩu database.
            </p>
          </div>
        </div>

        {loading ? (
          <p className={styles.loading}>Đang tải danh sách…</p>
        ) : connections.length === 0 ? (
          <p className={styles.empty}>Chưa có connection nào.</p>
        ) : (
          <div className={styles.tableWrap}>
            <table>
              <thead>
                <tr>
                  <th>Tên connection</th>
                  <th>Loại DB</th>
                  <th>Host / Database</th>
                  <th>Chủ sở hữu</th>
                  <th>Ngày tạo</th>
                  <th aria-label="Thao tác" />
                </tr>
              </thead>

              <tbody>
                {connections.map((connection) => {
                  const isDeleting = deletingConnectionId === connection.id;

                  return (
                    <tr key={connection.id}>
                      <td>
                        <strong>{connection.name}</strong>
                      </td>

                      <td>{connection.dbType}</td>

                      <td className={styles.hostCell}>
                        <div>
                          <small>Host</small>
                          <strong>{connection.host || "—"}</strong>
                        </div>
                        <div>
                          <small>Database</small>
                          <span>{connection.databaseName}</span>
                        </div>
                      </td>

                      <td>{connection.ownerUsername}</td>

                      <td>
                        {new Date(connection.createdAt).toLocaleDateString(
                          "vi-VN",
                        )}
                      </td>

                      <td>
                        <button
                          type="button"
                          disabled={isDeleting}
                          className={styles.lock}
                          onClick={() =>
                            void handleDeleteConnection(connection)
                          }
                        >
                          {isDeleting ? "Đang xóa…" : "Xóa"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
