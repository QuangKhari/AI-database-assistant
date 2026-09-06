import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { adminApi } from "../api/adminApi";
import { ApiError } from "../api/client";
import { connectionApi } from "../api/connectionApi";
import { historyApi } from "../api/historyApi";
import type {
  AdminStats,
  Conversation,
  DatabaseConnection,
} from "../api/types";
import { useAuth } from "../context/AuthContext";
import styles from "./DashboardPage.module.css";

const DB_TYPE_LABEL: Record<DatabaseConnection["dbType"], string> = {
  mysql: "MySQL",
  postgresql: "PostgreSQL",
  excel: "Excel",
};

const DB_TYPE_ICON: Record<DatabaseConnection["dbType"], string> = {
  mysql: "MY",
  postgresql: "PG",
  excel: "XL",
};

function formatRelativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.round(diffMs / 60000);
  if (minutes < 1) return "Vừa xong";
  if (minutes < 60) return `${minutes} phút trước`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} giờ trước`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} ngày trước`;
  return new Date(iso).toLocaleDateString("vi-VN");
}

export function DashboardPage() {
  const { user } = useAuth();
  const name = user?.displayName?.trim() || user?.username;
  const isAdmin = user?.role === "ADMIN";

  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [pinnedCount, setPinnedCount] = useState(0);
  const [adminStats, setAdminStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const [connectionsResult, conversationsResult, pinnedResult] =
          await Promise.all([
            connectionApi.list(),
            historyApi.list(),
            historyApi.pinned(),
          ]);

        if (cancelled) return;

        setConnections(connectionsResult);
        setConversations(conversationsResult);
        setPinnedCount(pinnedResult.length);
        setError("");

        // Thống kê admin là "có thì tốt" - không để lỗi ở phần này (ví
        // dụ chưa có quyền, hoặc cache admin đang lỗi) làm hỏng cả trang
        // Tổng quan của người dùng thường.
        if (isAdmin) {
          try {
            const stats = await adminApi.stats();
            if (!cancelled) setAdminStats(stats);
          } catch {
            // bỏ qua, chỉ ẩn khối thống kê admin
          }
        }
      } catch (reason) {
        if (!cancelled) {
          setError(
            reason instanceof ApiError
              ? reason.message
              : "Không tải được dữ liệu tổng quan.",
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [isAdmin]);

  const activeConnections = useMemo(
    () => connections.filter((c) => c.active),
    [connections],
  );

  const connectionsByType = useMemo(() => {
    const counts: Record<DatabaseConnection["dbType"], number> = {
      mysql: 0,
      postgresql: 0,
      excel: 0,
    };
    for (const c of connections) counts[c.dbType] += 1;
    return counts;
  }, [connections]);

  const recentConversations = useMemo(
    () =>
      [...conversations]
        .sort(
          (a, b) =>
            new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
        )
        .slice(0, 5),
    [conversations],
  );

  function connectionName(id: number): string {
    return connections.find((c) => c.id === id)?.name ?? "Connection đã xoá";
  }

  return (
    <div className={styles.page}>
      <div className={styles.hero}>
        <div>
          <p>Không gian làm việc</p>
          <h1>Xin chào, {name}!</h1>
          <span>
            Hỏi dữ liệu bằng tiếng Việt trên MySQL, PostgreSQL hoặc file Excel —
            hệ thống tự sinh SQL bằng AI, kiểm tra an toàn trước khi chạy và mã
            hóa thông tin kết nối của bạn.
          </span>
        </div>
        <div className={styles.status}>
          <span /> Hệ thống hoạt động
        </div>
      </div>

      {error && <div className={styles.error}>{error}</div>}

      <div className={styles.statsRow}>
        <div className={styles.statCard}>
          <span className={styles.statLabel}>Kết nối cơ sở dữ liệu</span>
          <strong className={styles.statValue}>
            {loading ? "…" : connections.length}
          </strong>
          <div className={styles.typeBadges}>
            {connectionsByType.mysql > 0 && (
              <span className={styles.badge}>
                MySQL {connectionsByType.mysql}
              </span>
            )}
            {connectionsByType.postgresql > 0 && (
              <span className={styles.badge}>
                PostgreSQL {connectionsByType.postgresql}
              </span>
            )}
            {connectionsByType.excel > 0 && (
              <span className={styles.badge}>
                Excel {connectionsByType.excel}
              </span>
            )}
            {connections.length === 0 && !loading && (
              <span className={styles.badgeMuted}>Chưa có connection</span>
            )}
          </div>
        </div>

        <div className={styles.statCard}>
          <span className={styles.statLabel}>Đang hoạt động</span>
          <strong className={styles.statValue}>
            {loading ? "…" : activeConnections.length}
          </strong>
          <small className={styles.statHint}>
            trên tổng số {connections.length} connection
          </small>
        </div>

        <div className={styles.statCard}>
          <span className={styles.statLabel}>Cuộc trò chuyện</span>
          <strong className={styles.statValue}>
            {loading ? "…" : conversations.length}
          </strong>
          <small className={styles.statHint}>
            <Link to="/history">Xem lịch sử →</Link>
          </small>
        </div>

        <div className={styles.statCard}>
          <span className={styles.statLabel}>Câu hỏi đã ghim</span>
          <strong className={styles.statValue}>
            {loading ? "…" : pinnedCount}
          </strong>
          <small className={styles.statHint}>
            <Link to="/history">Xem đã ghim →</Link>
          </small>
        </div>
      </div>

      {isAdmin && adminStats && (
        <section className={`${styles.card} ${styles.adminCard}`}>
          <div className={styles.adminCardHeader}>
            <div>
              <div className={styles.icon}>QT</div>
              <h2>Toàn hệ thống (Admin)</h2>
            </div>
            <Link to="/admin">Vào trang quản trị →</Link>
          </div>
          <div className={styles.adminStatsGrid}>
            <div>
              <strong>{adminStats.totalUsers}</strong>
              <span>Người dùng</span>
            </div>
            <div>
              <strong>{adminStats.totalConnections}</strong>
              <span>Connection</span>
            </div>
            <div>
              <strong>{adminStats.totalConversations}</strong>
              <span>Cuộc trò chuyện</span>
            </div>
            <div>
              <strong>{adminStats.totalQueries}</strong>
              <span>Câu truy vấn</span>
            </div>
          </div>
        </section>
      )}

      <div className={styles.grid}>
        <article className={`${styles.card} ${styles.accountCard}`}>
          <div className={styles.icon}>AI</div>
          <h2>Hỏi dữ liệu bằng AI</h2>
          <p>
            Đặt câu hỏi tự nhiên, xem SQL được sinh ra, kiểm tra an toàn rồi
            thực thi với tiến độ theo thời gian thực.
          </p>
          <Link to="/chat">Bắt đầu trò chuyện →</Link>
        </article>

        <article className={styles.card}>
          <div className={styles.icon}>DB</div>
          <h2>Kết nối cơ sở dữ liệu</h2>
          <p>
            Tạo, kiểm tra và quản lý connection MySQL, PostgreSQL chỉ đọc, hoặc
            tải lên file Excel làm nguồn dữ liệu.
          </p>
          <Link to="/connections">Quản lý connections →</Link>
        </article>

        <article className={styles.card}>
          <div className={styles.icon}>SC</div>
          <h2>Khám phá Schema</h2>
          <p>
            Xem cấu trúc bảng, cột, quan hệ khóa ngoại và thêm mô tả để AI hiểu
            dữ liệu của bạn tốt hơn.
          </p>
          <Link to="/schema">Xem schema →</Link>
        </article>

        <article className={styles.card}>
          <div className={styles.icon}>LS</div>
          <h2>Lịch sử truy vấn</h2>
          <p>
            Tìm lại các cuộc trò chuyện trước đó, ghim câu hỏi quan trọng và xem
            lại SQL đã dùng.
          </p>
          <Link to="/history">Xem lịch sử →</Link>
        </article>

        <article className={styles.card}>
          <div className={styles.icon}>BM</div>
          <h2>Benchmark độ chính xác</h2>
          <p>
            Chạy bộ câu hỏi mẫu để đo tỉ lệ AI sinh đúng SQL trên từng
            connection, theo tiếng Việt hoặc tiếng Anh.
          </p>
          <Link to="/benchmark">Chạy benchmark →</Link>
        </article>

        <article className={styles.card}>
          <div className={styles.icon}>ME</div>
          <h2>Thông tin tài khoản</h2>
          <p>Cập nhật tên hiển thị, email hoặc thay đổi mật khẩu bảo mật.</p>
          <Link to="/profile">Quản lý tài khoản →</Link>
        </article>
      </div>

      <section className={styles.recent}>
        <div className={styles.recentHeader}>
          <h2>Hoạt động gần đây</h2>
          <Link to="/history">Xem tất cả →</Link>
        </div>

        {loading ? (
          <p className={styles.loading}>Đang tải…</p>
        ) : recentConversations.length === 0 ? (
          <p className={styles.recentEmpty}>
            Chưa có cuộc trò chuyện nào.{" "}
            <Link to="/chat">Đặt câu hỏi đầu tiên →</Link>
          </p>
        ) : (
          <ul className={styles.recentList}>
            {recentConversations.map((conversation) => (
              <li key={conversation.id}>
                <span
                  className={styles.recentIcon}
                  title={
                    DB_TYPE_LABEL[
                      connections.find(
                        (c) => c.id === conversation.connectionId,
                      )?.dbType ?? "mysql"
                    ]
                  }
                >
                  {
                    DB_TYPE_ICON[
                      connections.find(
                        (c) => c.id === conversation.connectionId,
                      )?.dbType ?? "mysql"
                    ]
                  }
                </span>
                <div className={styles.recentBody}>
                  <strong>{conversation.title}</strong>
                  <small>{connectionName(conversation.connectionId)}</small>
                </div>
                <span className={styles.recentTime}>
                  {formatRelativeTime(conversation.updatedAt)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className={styles.featureStrip}>
        <span className={styles.featureChip}>NL → SQL bằng Gemini</span>
        <span className={styles.featureChip}>RAG cho schema lớn</span>
        <span className={styles.featureChip}>Streaming tiến độ (SSE)</span>
        <span className={styles.featureChip}>MySQL · PostgreSQL · Excel</span>
        <span className={styles.featureChip}>Kiểm tra SQL chỉ đọc</span>
      </section>
    </div>
  );
}
