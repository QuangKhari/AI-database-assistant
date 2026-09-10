import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { connectionApi } from "../api/connectionApi";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import type { DatabaseConnection } from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";
import styles from "./ConnectionsPage.module.css";

const ACCEPTED_EXCEL_TYPES = [
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
];

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function isXlsxFile(file: File): boolean {
  return (
    file.name.toLowerCase().endsWith(".xlsx") ||
    ACCEPTED_EXCEL_TYPES.includes(file.type)
  );
}

export function ConnectionsPage() {
  const { showToast } = useToast();
  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [loading, setLoading] = useState(true);
  const { error, handleError, setError } = useApiError();
  const [workingId, setWorkingId] = useState<number | null>(null);
  const [excelFile, setExcelFile] = useState<File | null>(null);
  const [excelName, setExcelName] = useState("");
  const [uploadingExcel, setUploadingExcel] = useState(false);
  const [isDraggingExcel, setIsDraggingExcel] = useState(false);
  const excelInputRef = useRef<HTMLInputElement>(null);

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

  function pickExcelFile(file: File | null) {
    if (file && !isXlsxFile(file)) {
      showToast("Chỉ hỗ trợ file .xlsx.", "error");
      return;
    }
    setExcelFile(file);
    if (file && !excelName.trim()) {
      setExcelName(file.name.replace(/\.xlsx$/i, ""));
    }
  }

  function handleExcelDrop(event: React.DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setIsDraggingExcel(false);
    if (uploadingExcel) return;
    pickExcelFile(event.dataTransfer.files?.[0] ?? null);
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
          <label className={styles.excelNameField}>
            <span>Tên connection</span>
            <input
              value={excelName}
              maxLength={100}
              onChange={(event) => setExcelName(event.target.value)}
              placeholder="Ví dụ: Doanh số 2026"
              disabled={uploadingExcel}
            />
          </label>

          <div className={styles.excelDropzoneField}>
            <span>File Excel</span>
            <div
              className={`${styles.dropzone} ${isDraggingExcel ? styles.dropzoneActive : ""} ${excelFile ? styles.dropzoneFilled : ""}`}
              onClick={() => excelInputRef.current?.click()}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  excelInputRef.current?.click();
                }
              }}
              onDragOver={(event) => {
                event.preventDefault();
                if (!uploadingExcel) setIsDraggingExcel(true);
              }}
              onDragLeave={() => setIsDraggingExcel(false)}
              onDrop={handleExcelDrop}
              role="button"
              tabIndex={0}
              aria-label="Chọn hoặc kéo thả file Excel"
            >
              <input
                ref={excelInputRef}
                type="file"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={(event) =>
                  pickExcelFile(event.target.files?.[0] ?? null)
                }
                disabled={uploadingExcel}
                hidden
              />

              {excelFile ? (
                <div className={styles.dropzoneFile}>
                  <i className={styles.dropzoneFileIcon}>XL</i>
                  <div className={styles.dropzoneFileInfo}>
                    <strong>{excelFile.name}</strong>
                    <small>{formatFileSize(excelFile.size)}</small>
                  </div>
                  <button
                    type="button"
                    className={styles.dropzoneClear}
                    disabled={uploadingExcel}
                    onClick={(event) => {
                      event.stopPropagation();
                      setExcelFile(null);
                      if (excelInputRef.current)
                        excelInputRef.current.value = "";
                    }}
                    aria-label="Bỏ chọn file"
                  >
                    ×
                  </button>
                </div>
              ) : (
                <div className={styles.dropzoneEmpty}>
                  <i className={styles.dropzoneIcon}>XL</i>
                  <p>
                    Kéo thả file .xlsx vào đây, hoặc <u>chọn từ máy tính</u>
                  </p>
                  <small>Tối đa 20MB, mỗi sheet trở thành 1 bảng dữ liệu</small>
                </div>
              )}
            </div>
          </div>

          <button
            type="button"
            className={styles.excelSubmit}
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
            <article
              className={`${styles.card} ${connection.dbType === "excel" ? styles.cardExcel : ""}`}
              key={connection.id}
            >
              <div className={styles.cardTop}>
                <div
                  className={`${styles.dbIcon} ${connection.dbType === "excel" ? styles.dbIconExcel : ""}`}
                >
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
