import { useCallback, useEffect, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";
import { Link } from "react-router-dom";
import { connectionApi } from "../api/connectionApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type { DatabaseConnection } from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";
import styles from "./ConnectionsPage.module.css";

export function ConnectionsPage() {
  const { showToast } = useToast();
  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [loading, setLoading] = useState(true);
  const { error, handleError, setError } = useApiError();
  const [workingId, setWorkingId] = useState<number | null>(null);
  const [excelFile, setExcelFile] = useState<File | null>(null);
  const [excelName, setExcelName] = useState("");
  const [uploadingExcel, setUploadingExcel] = useState(false);

  const loadConnections = useCallback(async () => {
    try {
      setConnections(await connectionApi.list());
      setError("");
    } catch (reason) {
      handleError(reason, "Không tải được danh sách connection.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadConnections();
  }, [loadConnections]);

  async function reconnect(connection: DatabaseConnection) {
    setWorkingId(connection.id);
    try {
      const result = await connectionApi.reconnect(connection.id);
      showToast(result.message, result.successful ? "success" : "error");
      await loadConnections();
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể kiểm tra connection."),
        ),
        "error",
      );
    } finally {
      setWorkingId(null);
    }
  }

  async function disconnect(connection: DatabaseConnection) {
    if (
      !window.confirm(
        `Ngắt connection “${connection.name}”? Cấu hình và lịch sử vẫn được giữ lại.`,
      )
    )
      return;
    setWorkingId(connection.id);
    try {
      await connectionApi.disconnect(connection.id);
      showToast("Đã ngắt connection.", "success");
      await loadConnections();
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể ngắt connection."),
        ),
        "error",
      );
    } finally {
      setWorkingId(null);
    }
  }

  async function uploadExcel() {
    if (!excelFile) {
      showToast("Vui lòng chọn file Excel.", "error");
      return;
    }

    if (!excelName.trim()) {
      showToast("Vui lòng nhập tên connection.", "error");
      return;
    }

    setUploadingExcel(true);

    try {
      await connectionApi.uploadExcel(excelFile, excelName.trim());

      setExcelFile(null);
      setExcelName("");

      showToast("Đã tạo connection từ file Excel.", "success");

      await loadConnections();
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể tạo connection từ file Excel."),
        ),
        "error",
      );
    } finally {
      setUploadingExcel(false);
    }
  }

  const activeCount = connections.filter((item) => item.active).length;

  return (
    <div className={styles.page}>
      <div className={styles.heading}>
        <div>
          <p>Target database</p>
          <h1>Kết nối MySQL / PostgreSQL</h1>
          <span>
            Kết nối bằng tài khoản chỉ có quyền đọc (read-only) để đảm bảo an
            toàn.
          </span>
        </div>
        <Link className={styles.addButton} to="/connections/new">
          + Thêm connection
        </Link>
      </div>

      <div className={styles.summary}>
        <div>
          <strong>{connections.length}</strong>
          <span>Tổng cấu hình</span>
        </div>
        <div>
          <strong>{activeCount}</strong>
          <span>Đang hoạt động</span>
        </div>
      </div>

      <section className={styles.excelPanel}>
        <div>
          <p>Excel dataset</p>
          <h2>Tạo connection từ file Excel</h2>
          <span>
            Upload file .xlsx để hệ thống chuyển dữ liệu thành connection có thể
            truy vấn bằng Chat AI.
          </span>
        </div>

        <div className={styles.excelForm}>
          <label>
            <span>Tên connection</span>
            <input
              value={excelName}
              maxLength={100}
              onChange={(event) => setExcelName(event.target.value)}
              placeholder="Ví dụ: Doanh số 2026"
              disabled={uploadingExcel}
            />
          </label>

          <div className={styles.fileField}>
            <span>File Excel</span>
            <label className={styles.filePicker}>
              <input
                aria-label="Chọn file Excel"
                className={styles.hiddenFileInput}
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(event) =>
                  setExcelFile(event.target.files?.[0] ?? null)
                }
                disabled={uploadingExcel}
              />
              <span className={styles.fileButton}>
                <Upload size={16} aria-hidden="true" />
                Chọn file
              </span>
              <span
                className={excelFile ? styles.selectedFile : styles.filePlaceholder}
                title={excelFile?.name}
              >
                <FileSpreadsheet size={16} aria-hidden="true" />
                {excelFile?.name ?? "Chưa chọn file .xlsx"}
              </span>
            </label>
          </div>

          <button
            type="button"
            onClick={() => void uploadExcel()}
            disabled={uploadingExcel || !excelFile || !excelName.trim()}
          >
            {uploadingExcel ? "Đang upload…" : "Tạo connection Excel"}
          </button>
        </div>
      </section>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
      {loading ? (
        <p className={styles.loading}>Đang tải connections…</p>
      ) : connections.length === 0 ? (
        <section className={styles.empty}>
          <div>DB</div>
          <h2>Chưa có database nào</h2>
          <p>Thêm Target MySQL bằng tài khoản chỉ có quyền đọc để bắt đầu.</p>
          <Link to="/connections/new">Tạo connection đầu tiên</Link>
        </section>
      ) : (
        <div className={styles.grid}>
          {connections.map((connection) => (
            <article className={styles.card} key={connection.id}>
              <div className={styles.cardTop}>
                <div className={styles.dbIcon}>
                  {connection.dbType === "postgresql"
                    ? "PG"
                    : connection.dbType === "excel"
                      ? "XL"
                      : "MY"}
                </div>
                <span
                  className={
                    connection.active ? styles.active : styles.inactive
                  }
                >
                  <i />
                  {connection.active ? "Đang hoạt động" : "Đã ngắt"}
                </span>
              </div>
              <h2>{connection.name}</h2>
              <p className={styles.endpoint}>
                {connection.dbType === "excel"
                  ? "File Excel đã tải lên"
                  : `${connection.host}:${connection.port}`}
              </p>
              <dl>
                {connection.dbType !== "excel" && (
                  <div>
                    <dt>Database</dt>
                    <dd>{connection.databaseName}</dd>
                  </div>
                )}
                {connection.dbType !== "excel" && (
                  <div>
                    <dt>
                      {connection.dbType === "postgresql"
                        ? "PostgreSQL user"
                        : "MySQL user"}
                    </dt>
                    <dd>{connection.username}</dd>
                  </div>
                )}
                <div>
                  <dt>Kiểm tra gần nhất</dt>
                  <dd>
                    {connection.lastTestedAt
                      ? new Date(connection.lastTestedAt).toLocaleString(
                          "vi-VN",
                        )
                      : "Chưa có"}
                  </dd>
                </div>
              </dl>
              <div className={styles.actions}>
                <button
                  type="button"
                  disabled={workingId === connection.id}
                  onClick={() => reconnect(connection)}
                >
                  {workingId === connection.id
                    ? "Đang kiểm tra…"
                    : connection.active
                      ? "Kiểm tra lại"
                      : "Kết nối lại"}
                </button>
                <Link to={`/connections/${connection.id}/edit`}>Chỉnh sửa</Link>
                {connection.active && (
                  <button
                    className={styles.disconnect}
                    type="button"
                    onClick={() => disconnect(connection)}
                  >
                    Ngắt
                  </button>
                )}
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
