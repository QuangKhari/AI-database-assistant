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
  sslEnabled: false,
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

          // Giữ nguyên đúng loại DB từ backend.
          // Không biến DuckDB / Excel thành MySQL.
          dbType: connection.dbType,

          host: connection.host,
          port: connection.port,
          databaseName: connection.databaseName,
          username: connection.username,

          // Không load lại password.
          password: "",

          // SSL chỉ áp dụng cho PostgreSQL.
          sslEnabled:
            connection.dbType === "postgresql" ? connection.sslEnabled : false,
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
    setForm((current) => ({
      ...current,
      [key]: value,
    }));

    setMessage(null);
  }

  function selectDatabaseType(dbType: ConnectionPayload["dbType"]) {
    setForm((current) => ({
      ...current,
      dbType,

      // SSL chỉ dành cho PostgreSQL.
      sslEnabled: dbType === "postgresql" ? current.sslEnabled : false,
    }));

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
      setMessage({
        type: "error",
        text: "Vui lòng nhập mật khẩu database.",
      });

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

  if (loading) {
    return <p>Đang tải connection…</p>;
  }

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
            {/* Tên connection */}
            <label className={styles.full}>
              <span>Tên hiển thị *</span>

              <input
                value={form.name}
                maxLength={100}
                onChange={(e) => update("name", e.target.value)}
                placeholder="Ví dụ: Database bán hàng"
              />
            </label>

            {/* Host */}
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

            {/* Port */}
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

            {/* Database type */}
            <div className={`${styles.full} ${styles.databaseTypeSection}`}>
              <div className={styles.databaseHeading}>
                <span className={styles.sectionLabel}>Loại cơ sở dữ liệu</span>

                <small className={styles.sectionDescription}>
                  Chọn loại database mà bạn muốn kết nối
                </small>
              </div>

              <div className={styles.databaseGrid}>
                {/* MySQL */}
                <button
                  type="button"
                  aria-pressed={form.dbType === "mysql"}
                  className={`${styles.databaseCard} ${
                    form.dbType === "mysql" ? styles.databaseCardActive : ""
                  }`}
                  onClick={() => selectDatabaseType("mysql")}
                >
                  <div className={styles.databaseIcon}>🐬</div>

                  <div className={styles.databaseInfo}>
                    <strong>MySQL</strong>

                    <span>Relational SQL database</span>
                  </div>

                  {form.dbType === "mysql" && (
                    <span className={styles.databaseCheck} aria-hidden="true">
                      ✓
                    </span>
                  )}
                </button>

                {/* PostgreSQL */}
                <button
                  type="button"
                  aria-pressed={form.dbType === "postgresql"}
                  className={`${styles.databaseCard} ${
                    form.dbType === "postgresql"
                      ? styles.databaseCardActive
                      : ""
                  }`}
                  onClick={() => selectDatabaseType("postgresql")}
                >
                  <div className={styles.databaseIcon}>🐘</div>

                  <div className={styles.databaseInfo}>
                    <strong>PostgreSQL</strong>

                    <span>Advanced SQL database</span>
                  </div>

                  {form.dbType === "postgresql" && (
                    <span className={styles.databaseCheck} aria-hidden="true">
                      ✓
                    </span>
                  )}
                </button>

                {/* Excel */}
                <button
                  type="button"
                  aria-pressed={form.dbType === "excel"}
                  className={`${styles.databaseCard} ${
                    form.dbType === "excel" ? styles.databaseCardActive : ""
                  }`}
                  onClick={() => selectDatabaseType("excel")}
                >
                  <div className={styles.databaseIcon}>📊</div>

                  <div className={styles.databaseInfo}>
                    <strong>Excel</strong>

                    <span>Spreadsheet data source</span>
                  </div>

                  {form.dbType === "excel" && (
                    <span className={styles.databaseCheck} aria-hidden="true">
                      ✓
                    </span>
                  )}
                </button>

                {/* DuckDB */}
                <button
                  type="button"
                  aria-pressed={form.dbType === "duckdb"}
                  className={`${styles.databaseCard} ${
                    form.dbType === "duckdb" ? styles.databaseCardActive : ""
                  }`}
                  onClick={() => selectDatabaseType("duckdb")}
                >
                  <div className={styles.databaseIcon}>🦆</div>

                  <div className={styles.databaseInfo}>
                    <strong>DuckDB</strong>

                    <span>Analytics database</span>
                  </div>

                  {form.dbType === "duckdb" && (
                    <span className={styles.databaseCheck} aria-hidden="true">
                      ✓
                    </span>
                  )}
                </button>
              </div>
            </div>

            {/* PostgreSQL SSL */}
            {form.dbType === "postgresql" && (
              <label className={`${styles.full} ${styles.sslOption}`}>
                <span>Cấu hình bảo mật</span>

                <div className={styles.sslRow}>
                  <input
                    type="checkbox"
                    checked={form.sslEnabled}
                    onChange={(e) => update("sslEnabled", e.target.checked)}
                  />

                  <div>
                    <strong>Sử dụng SSL</strong>

                    <small>
                      Bật nếu database yêu cầu kết nối SSL, ví dụ PostgreSQL
                      trên Neon.
                    </small>
                  </div>
                </div>
              </label>
            )}

            {/* Database name */}
            <label>
              <span>Tên database *</span>

              <input
                value={form.databaseName}
                maxLength={64}
                onChange={(e) => update("databaseName", e.target.value)}
                placeholder="sample_store"
              />
            </label>

            {/* Username */}
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

            {/* Password */}
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

        {/* Safety */}
        <aside className={styles.safety}>
          <strong>Điều kiện an toàn</strong>

          <ul>
            <li>Tài khoản DB cần có quyền SELECT và không có quyền ghi.</li>

            <li>Timeout kết nối 5 giây, timeout thực thi truy vấn 15 giây.</li>

            <li>Kết nối được đóng ngay sau khi kiểm tra.</li>

            <li>Tối đa 10 lần kiểm tra/phút/tài khoản.</li>
          </ul>
        </aside>

        {/* Message */}
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

        {/* Actions */}
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
