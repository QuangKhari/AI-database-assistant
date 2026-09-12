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

type DatabaseType = ConnectionPayload["dbType"];

const databaseOptions: Array<{
  type: DatabaseType;
  icon: string;
  name: string;
  description: string;
  detail: string;
}> = [
  {
    type: "mysql",
    icon: "🐬",
    name: "MySQL",
    description: "Relational SQL database",
    detail: "Phù hợp cho dữ liệu quan hệ và truy vấn SQL.",
  },
  {
    type: "postgresql",
    icon: "🐘",
    name: "PostgreSQL",
    description: "Advanced SQL database",
    detail: "Hỗ trợ SQL mạnh và cấu hình SSL.",
  },
];

export function ConnectionFormPage() {
  const { id } = useParams();
  const connectionId = id ? Number(id) : null;
  const editing = connectionId !== null;

  const navigate = useNavigate();
  const { showToast } = useToast();

  const [form, setForm] = useState<ConnectionPayload>(emptyForm);

  const [loading, setLoading] = useState(editing);

  const [working, setWorking] = useState<"test" | "save" | null>(null);

  const [showPassword, setShowPassword] = useState(false);

  const [message, setMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  useEffect(() => {
    if (!connectionId) return;

    connectionApi
      .get(connectionId)
      .then((connection) => {
        if (connection.dbType === "excel") {
          showToast(
            "Connection Excel được quản lý trực tiếp ở trang Connections.",
            "error",
          );

          navigate("/connections");

          return;
        }

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

  function selectDatabaseType(dbType: DatabaseType) {
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
    return (
      <div className={styles.loadingPage}>
        <div className={styles.loadingCard}>
          <div className={styles.loadingSpinner} />
          <strong>Đang tải connection</strong>
          <span>Vui lòng chờ trong giây lát...</span>
        </div>
      </div>
    );
  }

  const selectedDatabase = databaseOptions.find(
    (item) => item.type === form.dbType,
  );

  return (
    <div className={styles.page}>
      <div className={styles.topbar}>
        <Link className={styles.back} to="/connections">
          <span className={styles.backIcon}>←</span>
          <span>Danh sách connections</span>
        </Link>

        <div className={styles.topbarBadge}>
          <span className={styles.topbarDot} />
          Database connection
        </div>
      </div>

      <header className={styles.hero}>
        <div className={styles.heroCopy}>
          <div className={styles.eyebrow}>
            {editing ? "EDIT CONNECTION" : "NEW CONNECTION"}
          </div>

          <h1>{editing ? "Chỉnh sửa connection" : "Kết nối cơ sở dữ liệu"}</h1>

          <p>
            {editing
              ? "Cập nhật thông tin kết nối và xác minh cấu hình trước khi lưu."
              : "Thêm một nguồn dữ liệu mới để AI có thể phân tích schema và hỗ trợ truy vấn SQL."}
          </p>
        </div>

        <div className={styles.heroVisual} aria-hidden="true">
          <div className={styles.heroGlow} />
          <div className={styles.heroDatabase}>
            <div className={styles.heroDatabaseTop}>
              <span />
              <span />
              <span />
            </div>

            <div className={styles.heroDatabaseBody}>
              <div className={styles.databaseCylinder}>
                <div className={styles.databaseCylinderTop} />
                <div className={styles.databaseCylinderLine} />
                <div className={styles.databaseCylinderLine} />
                <div className={styles.databaseCylinderBottom} />
              </div>
            </div>
          </div>
        </div>
      </header>

      <div className={styles.layout}>
        <main>
          <form className={styles.form} onSubmit={save}>
            {/* DATABASE TYPE */}
            <section className={styles.panel}>
              <div className={styles.sectionHeader}>
                <div className={styles.sectionIcon}>01</div>

                <div>
                  <h2>Chọn loại dữ liệu</h2>
                  <p>Chọn nguồn dữ liệu mà hệ thống sẽ kết nối tới.</p>
                </div>
              </div>

              <div className={styles.databaseGrid}>
                {databaseOptions.map((database) => {
                  const active = form.dbType === database.type;

                  return (
                    <button
                      key={database.type}
                      type="button"
                      aria-pressed={active}
                      className={`${styles.databaseCard} ${
                        active ? styles.databaseCardActive : ""
                      }`}
                      onClick={() => selectDatabaseType(database.type)}
                    >
                      <div className={styles.databaseCardIcon}>
                        {database.icon}
                      </div>

                      <div className={styles.databaseInfo}>
                        <strong>{database.name}</strong>

                        <span>{database.description}</span>

                        <small>{database.detail}</small>
                      </div>

                      <span
                        className={`${styles.databaseRadio} ${
                          active ? styles.databaseRadioActive : ""
                        }`}
                      >
                        {active && "✓"}
                      </span>
                    </button>
                  );
                })}
              </div>
            </section>

            {/* BASIC INFORMATION */}
            <section className={styles.panel}>
              <div className={styles.sectionHeader}>
                <div className={styles.sectionIcon}>02</div>

                <div>
                  <h2>Thông tin kết nối</h2>
                  <p>Nhập thông tin server và database cần truy cập.</p>
                </div>
              </div>

              <div className={styles.grid}>
                <label className={`${styles.field} ${styles.full}`}>
                  <span>
                    Tên hiển thị
                    <b>*</b>
                  </span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>◈</span>

                    <input
                      value={form.name}
                      maxLength={100}
                      onChange={(e) => update("name", e.target.value)}
                      placeholder="Ví dụ: Database bán hàng"
                    />
                  </div>

                  <small>
                    Tên giúp bạn dễ dàng nhận diện connection trong danh sách.
                  </small>
                </label>

                <label className={`${styles.field} ${styles.full}`}>
                  <span>
                    Host
                    <b>*</b>
                  </span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>⌁</span>

                    <input
                      value={form.host}
                      maxLength={253}
                      onChange={(e) => update("host", e.target.value)}
                      placeholder="localhost hoặc địa chỉ server"
                    />
                  </div>

                  <small>
                    Không nhập JDBC URL. Có thể sử dụng localhost, mạng riêng
                    hoặc Docker.
                  </small>
                </label>

                <label className={styles.field}>
                  <span>
                    Port
                    <b>*</b>
                  </span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>#</span>

                    <input
                      type="number"
                      min="1"
                      max="65535"
                      value={form.port}
                      onChange={(e) => update("port", Number(e.target.value))}
                    />
                  </div>

                  <small>1 – 65535</small>
                </label>

                <label className={styles.field}>
                  <span>
                    Tên database
                    <b>*</b>
                  </span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>▣</span>

                    <input
                      value={form.databaseName}
                      maxLength={64}
                      onChange={(e) => update("databaseName", e.target.value)}
                      placeholder="sample_store"
                    />
                  </div>

                  <small>Database/schema cần được truy cập.</small>
                </label>
              </div>
            </section>

            {/* CREDENTIALS */}
            <section className={styles.panel}>
              <div className={styles.sectionHeader}>
                <div className={styles.sectionIcon}>03</div>

                <div>
                  <h2>Thông tin xác thực</h2>
                  <p>Sử dụng tài khoản database có quyền đọc dữ liệu.</p>
                </div>
              </div>

              <div className={styles.grid}>
                <label className={`${styles.field} ${styles.full}`}>
                  <span>
                    Username DB
                    <b>*</b>
                  </span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>♙</span>

                    <input
                      autoComplete="username"
                      value={form.username}
                      maxLength={64}
                      onChange={(e) => update("username", e.target.value)}
                      placeholder="aidb_reader"
                    />
                  </div>

                  <small>
                    Khuyến nghị sử dụng một tài khoản read-only riêng cho hệ
                    thống.
                  </small>
                </label>

                <label className={`${styles.field} ${styles.full}`}>
                  <span>Mật khẩu DB {!editing && <b>*</b>}</span>

                  <div className={styles.inputWrapper}>
                    <span className={styles.inputIcon}>◆</span>

                    <input
                      type={showPassword ? "text" : "password"}
                      autoComplete="new-password"
                      value={form.password}
                      maxLength={256}
                      onChange={(e) => update("password", e.target.value)}
                      placeholder={
                        editing
                          ? "Để trống nếu giữ mật khẩu cũ"
                          : "Nhập mật khẩu của tài khoản read-only"
                      }
                    />

                    <button
                      type="button"
                      className={styles.passwordToggle}
                      onClick={() => setShowPassword((current) => !current)}
                      aria-label={
                        showPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu"
                      }
                    >
                      {showPassword ? "Ẩ" : "Hi"}
                    </button>
                  </div>

                  <small>
                    {editing
                      ? "Để trống khi muốn giữ nguyên mật khẩu hiện tại. Nhập lại nếu muốn thay đổi hoặc test connection."
                      : "Mật khẩu được mã hóa AES-GCM trước khi lưu và không được hiển thị lại."}
                  </small>
                </label>
              </div>
            </section>

            {/* POSTGRES SSL */}
            {form.dbType === "postgresql" && (
              <section className={styles.securityPanel}>
                <div className={styles.securityIcon}>✓</div>

                <div className={styles.securityContent}>
                  <div className={styles.securityTitle}>
                    <div>
                      <strong>Cấu hình bảo mật</strong>
                      <span>PostgreSQL SSL</span>
                    </div>

                    <label className={styles.switch}>
                      <input
                        type="checkbox"
                        checked={form.sslEnabled}
                        onChange={(e) => update("sslEnabled", e.target.checked)}
                      />

                      <span className={styles.slider} />
                    </label>
                  </div>

                  <p>
                    Bật SSL nếu PostgreSQL yêu cầu kết nối mã hóa, ví dụ các
                    database cloud như Neon.
                  </p>
                </div>
              </section>
            )}

            {/* MESSAGE */}
            {message && (
              <div
                role="alert"
                className={`${styles.message} ${
                  message.type === "success" ? styles.success : styles.error
                }`}
              >
                <span className={styles.messageIcon}>
                  {message.type === "success" ? "✓" : "!"}
                </span>

                <div>
                  <strong>
                    {message.type === "success"
                      ? "Kết nối thành công"
                      : "Không thể thực hiện"}
                  </strong>

                  <span>{message.text}</span>
                </div>
              </div>
            )}

            {/* ACTIONS */}
            <div className={styles.actions}>
              <Link className={styles.cancelButton} to="/connections">
                Hủy
              </Link>

              <button
                type="button"
                className={styles.testButton}
                disabled={working !== null}
                onClick={testConnection}
              >
                <span className={styles.buttonIcon} aria-hidden="true">
                  {working === "test" ? "…" : "✓"}
                </span>

                {working === "test" ? "Đang kiểm tra..." : "Kiểm tra kết nối"}
              </button>

              <button
                type="submit"
                className={styles.saveButton}
                disabled={working !== null}
              >
                <span className={styles.buttonIcon} aria-hidden="true">
                  {working === "save" ? "…" : "→"}
                </span>

                {working === "save"
                  ? "Đang xác minh..."
                  : editing
                    ? "Lưu thay đổi"
                    : "Xác minh và tạo"}
              </button>
            </div>
          </form>
        </main>

        {/* RIGHT SIDEBAR */}
        <aside className={styles.sidebar}>
          <div className={styles.summaryCard}>
            <div className={styles.summaryHeader}>
              <span className={styles.summaryStatus}>
                <span />
                READY
              </span>

              <span className={styles.summaryNumber}>
                0{editing ? "2" : "1"}
              </span>
            </div>

            <h3>
              {editing ? "Cập nhật nguồn dữ liệu" : "Thiết lập nguồn dữ liệu"}
            </h3>

            <p>
              {selectedDatabase
                ? `Bạn đang cấu hình ${selectedDatabase.name}.`
                : "Hoàn tất thông tin bên trái để tạo connection."}
            </p>

            <div className={styles.summaryLine} />

            <div className={styles.summaryRow}>
              <span>Database</span>
              <strong>{selectedDatabase?.name ?? "—"}</strong>
            </div>

            <div className={styles.summaryRow}>
              <span>Host</span>
              <strong className={styles.summaryValue}>
                {form.host || "Chưa nhập"}
              </strong>
            </div>

            <div className={styles.summaryRow}>
              <span>Port</span>
              <strong>{form.port || "—"}</strong>
            </div>

            <div className={styles.summaryRow}>
              <span>SSL</span>
              <strong>
                {form.dbType === "postgresql"
                  ? form.sslEnabled
                    ? "Enabled"
                    : "Disabled"
                  : "N/A"}
              </strong>
            </div>
          </div>

          <div className={styles.safetyCard}>
            <div className={styles.safetyHeader}>
              <span className={styles.safetyIcon}>✓</span>

              <div>
                <strong>Security first</strong>
                <span>Kết nối an toàn</span>
              </div>
            </div>

            <ul>
              <li>
                <span>✓</span>
                Tài khoản chỉ nên có quyền SELECT.
              </li>

              <li>
                <span>✓</span>
                Timeout kết nối tối đa 5 giây.
              </li>

              <li>
                <span>✓</span>
                Timeout truy vấn tối đa 15 giây.
              </li>

              <li>
                <span>✓</span>
                Connection được đóng sau khi kiểm tra.
              </li>

              <li>
                <span>✓</span>
                Tối đa 10 lần kiểm tra/phút/tài khoản.
              </li>
            </ul>
          </div>

          <div className={styles.helpCard}>
            <span className={styles.helpIcon}>?</span>

            <div>
              <strong>Cần lưu ý?</strong>

              <p>
                Sau khi tạo connection, bạn có thể đồng bộ schema để AI hiểu cấu
                trúc dữ liệu trước khi bắt đầu chat.
              </p>
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}
