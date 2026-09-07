import { type FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import { connectionApi } from "../api/connectionApi";
import type { ConnectionPayload } from "../api/types";
import { useToast } from "../context/ToastContext";
import styles from "./ConnectionFormPage.module.css";

const emptyForm: ConnectionPayload = {
  name: "",
  dbType: "mysql",
  host: "",
  port: 3306,
  databaseName: "",
  username: "",
  password: "",
};

export function ConnectionFormPage() {
  const { id } = useParams();
  const connectionId = id ? Number(id) : null;
  const editing = connectionId !== null;
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [form, setForm] = useState<ConnectionPayload>(emptyForm);
  const [loading, setLoading] = useState(editing);
  const [working, setWorking] = useState<"test" | "save" | null>(null);
  const [message, setMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  useEffect(() => {
    if (!connectionId) return;
    connectionApi
      .get(connectionId)
      .then((connection) => {
        setForm({
          name: connection.name,
          dbType: connection.dbType === "postgresql" ? "postgresql" : "mysql",
          host: connection.host,
          port: connection.port,
          databaseName: connection.databaseName,
          username: connection.username,
          password: "",
        });
      })
      .catch((reason) => {
        setMessage({
          type: "error",
          text: formatErrorWithSupportCode(
            parseApiError(reason, "Không tải được connection."),
          ),
        });
      })
      .finally(() => setLoading(false));
  }, [connectionId]);

  function update<K extends keyof ConnectionPayload>(
    key: K,
    value: ConnectionPayload[K],
  ) {
    setForm((current) => ({ ...current, [key]: value }));
    setMessage(null);
  }

  function valid() {
    if (
      !form.name.trim() ||
      !form.host.trim() ||
      !form.databaseName.trim() ||
      !form.username.trim()
    ) {
      setMessage({
        type: "error",
        text: "Vui lòng nhập đầy đủ các trường bắt buộc.",
      });
      return false;
    }
    if (!editing && !form.password) {
      setMessage({ type: "error", text: "Vui lòng nhập mật khẩu database." });
      return false;
    }
    if (form.port < 1 || form.port > 65535) {
      setMessage({
        type: "error",
        text: "Port phải nằm trong khoảng 1–65535.",
      });
      return false;
    }
    return true;
  }

  async function testConnection() {
    if (!valid()) return;
    if (editing && !form.password) {
      setMessage({
        type: "error",
        text: "Nhập lại mật khẩu để kiểm tra cấu hình vừa sửa. Khi lưu, bạn có thể để trống để giữ mật khẩu cũ.",
      });
      return;
    }
    setWorking("test");
    try {
      const result = await connectionApi.test(form);
      setMessage({
        type: result.successful ? "success" : "error",
        text: `${result.message} (${result.durationMs} ms)`,
      });
    } catch (reason) {
      setMessage({
        type: "error",
        text: formatErrorWithSupportCode(
          parseApiError(reason, "Không thể kiểm tra kết nối."),
        ),
      });
    } finally {
      setWorking(null);
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!valid()) return;
    setWorking("save");
    try {
      if (connectionId) {
        const { dbType: _dbType, ...payload } = form;
        await connectionApi.update(connectionId, payload);
      } else {
        await connectionApi.create(form);
      }
      showToast(
        editing ? "Đã cập nhật connection." : "Đã tạo connection an toàn.",
        "success",
      );
      navigate("/connections");
    } catch (reason) {
      setMessage({
        type: "error",
        text: formatErrorWithSupportCode(
          parseApiError(reason, "Không thể lưu connection."),
        ),
      });
    } finally {
      setWorking(null);
    }
  }

  if (loading) return <p>Đang tải connection…</p>;

  return (
    <div className={styles.page}>
      <Link className={styles.back} to="/connections">
        ← Danh sách connections
      </Link>
      <header>
        <p>Target Database</p>
        <h1>{editing ? "Chỉnh sửa connection" : "Thêm connection"}</h1>
        <span>
          Chỉ lưu sau khi kết nối thành công và xác minh tài khoản chỉ có quyền
          đọc.
        </span>
      </header>

      <form className={styles.form} onSubmit={save}>
        <section className={styles.panel}>
          <h2>Thông tin kết nối</h2>
          <div className={styles.grid}>
            <label className={styles.full}>
              <span>Tên hiển thị *</span>
              <input
                value={form.name}
                maxLength={100}
                onChange={(e) => update("name", e.target.value)}
                placeholder="Ví dụ: Database bán hàng"
              />
            </label>
            <label className={styles.full}>
              <span>Host *</span>
              <input
                value={form.host}
                maxLength={253}
                onChange={(e) => update("host", e.target.value)}
                placeholder="localhost hoặc địa chỉ MySQL"
              />
              <small>
                Không nhập JDBC URL. Local dev cho phép localhost, mạng riêng và
                Docker.
              </small>
            </label>
            <label>
              <span>Port *</span>
              <input
                type="number"
                min="1"
                max="65535"
                value={form.port}
                onChange={(e) => update("port", Number(e.target.value))}
              />
            </label>
            <label>
              <span>Loại database</span>
              <select
                value={form.dbType}
                disabled={editing}
                onChange={(e) => {
                  const dbType = e.target.value as ConnectionPayload["dbType"];
                  update("dbType", dbType);
                  // Đổi port mặc định theo loại DB nếu người dùng chưa chỉnh tay
                  // (3306 MySQL mặc định / 5432 PostgreSQL mặc định).
                  if (form.port === 3306 || form.port === 5432) {
                    update("port", dbType === "postgresql" ? 5432 : 3306);
                  }
                }}
              >
                <option value="mysql">MySQL</option>
                <option value="postgresql">PostgreSQL</option>
              </select>
            </label>
            <label>
              <span>Tên database *</span>
              <input
                value={form.databaseName}
                maxLength={64}
                onChange={(e) => update("databaseName", e.target.value)}
                placeholder="sample_store"
              />
            </label>
            <label>
              <span>Username DB *</span>
              <input
                autoComplete="username"
                value={form.username}
                maxLength={64}
                onChange={(e) => update("username", e.target.value)}
                placeholder="aidb_reader"
              />
            </label>
            <label className={styles.full}>
              <span>Mật khẩu DB {editing ? "" : "*"}</span>
              <input
                type="password"
                autoComplete="new-password"
                value={form.password}
                maxLength={256}
                onChange={(e) => update("password", e.target.value)}
                placeholder={
                  editing
                    ? "Để trống nếu giữ mật khẩu cũ"
                    : "Mật khẩu của tài khoản read-only (MySQL/PostgreSQL)"
                }
              />
              <small>
                Mật khẩu được mã hóa AES-GCM trước khi lưu và không hiển thị
                lại.
              </small>
            </label>
          </div>
        </section>

        <aside className={styles.safety}>
          <strong>Điều kiện an toàn</strong>
          <ul>
            <li>Tài khoản DB cần có quyền SELECT và không có quyền ghi.</li>
            <li>Timeout kết nối 5 giây, timeout thực thi truy vấn 15 giây.</li>
            <li>Kết nối được đóng ngay sau khi kiểm tra.</li>
            <li>Tối đa 10 lần kiểm tra/phút/tài khoản.</li>
          </ul>
        </aside>
        {message && (
          <div
            role="alert"
            className={
              message.type === "success" ? styles.success : styles.error
            }
          >
            {message.text}
          </div>
        )}
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.secondary}
            disabled={working !== null}
            onClick={testConnection}
          >
            {working === "test" ? "Đang kiểm tra…" : "Kiểm tra kết nối"}
          </button>
          <button
            type="submit"
            className={styles.primary}
            disabled={working !== null}
          >
            {working === "save"
              ? "Đang xác minh và lưu…"
              : editing
                ? "Lưu thay đổi"
                : "Xác minh và tạo"}
          </button>
        </div>
      </form>
    </div>
  );
}
