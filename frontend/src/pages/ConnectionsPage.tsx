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
  if (bytes < 1024) {
    return `${bytes} B`;
  }

  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }

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
  }, [handleError, setError]);

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
    ) {
      return;
    }

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

    if (uploadingExcel) {
      return;
    }

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

      if (excelInputRef.current) {
        excelInputRef.current.value = "";
      }

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

  const mysqlCount = connections.filter(
    (item) => item.dbType === "mysql",
  ).length;

  const postgresCount = connections.filter(
    (item) => item.dbType === "postgresql",
  ).length;

  const excelCount = connections.filter(
    (item) => item.dbType === "excel",
  ).length;

  const sqlCount = mysqlCount + postgresCount;

  return (
    <div className={styles.page}>
      {/* =====================================================
          HERO
          ===================================================== */}

      <header className={styles.hero}>
        <div className={styles.heroCopy}>
          <div className={styles.eyebrow}>
            <span className={styles.eyebrowDot} />
            Target databases
          </div>

          <h1>Connections</h1>

          <p>
            Quản lý các nguồn dữ liệu để Chat AI có thể truy vấn an toàn, với
            tài khoản read-only và trạng thái kết nối rõ ràng.
          </p>

          <div className={styles.heroMeta}>
            <span>
              <i />
              {activeCount} đang hoạt động
            </span>

            <span>{connections.length} cấu hình</span>
          </div>
        </div>

        <div className={styles.heroActions}>
          <Link className={styles.addButton} to="/connections/new">
            <span>＋</span>
            Thêm connection
          </Link>

          <span className={styles.securityBadge}>
            <span>🔒</span>
            Read-only
          </span>
        </div>
      </header>

      {/* =====================================================
          SUMMARY
          ===================================================== */}

      <div className={styles.summary}>
        <div className={styles.summaryCard}>
          <span className={styles.summaryIcon}>DB</span>

          <div>
            <strong>{connections.length}</strong>
            <span>Tổng connection</span>
          </div>
        </div>

        <div className={styles.summaryCard}>
          <span className={`${styles.summaryIcon} ${styles.summaryIconActive}`}>
            ✓
          </span>

          <div>
            <strong>{activeCount}</strong>
            <span>Đang hoạt động</span>
          </div>
        </div>

        <div className={styles.summaryCard}>
          <span className={`${styles.summaryIcon} ${styles.summaryIconTypes}`}>
            SQL
          </span>

          <div>
            <strong>{sqlCount}</strong>
            <span>SQL databases</span>
          </div>
        </div>

        <div className={styles.summaryCard}>
          <span className={`${styles.summaryIcon} ${styles.summaryIconExcel}`}>
            XL
          </span>

          <div>
            <strong>{excelCount}</strong>
            <span>Excel datasets</span>
          </div>
        </div>
      </div>

      {/* =====================================================
          EXCEL UPLOAD
          ===================================================== */}

      <section className={styles.excelPanel}>
        <div className={styles.excelHeader}>
          <div className={styles.excelHeaderIcon}>XL</div>

          <div>
            <p>Excel dataset</p>

            <h2>Thêm dữ liệu từ Excel</h2>

            <span>
              Upload file .xlsx và biến từng sheet thành dữ liệu có thể truy vấn
              trực tiếp bằng Chat AI.
            </span>
          </div>
        </div>

        <div className={styles.excelForm}>
          {/* NAME */}

          <label className={styles.excelNameField}>
            <span>Tên connection</span>

            <input
              value={excelName}
              maxLength={100}
              onChange={(event) => setExcelName(event.target.value)}
              placeholder="Ví dụ: Doanh số 2026"
              disabled={uploadingExcel}
            />

            <small>Tên này sẽ được hiển thị trong danh sách connection.</small>
          </label>

          {/* FILE */}

          <div className={styles.excelDropzoneField}>
            <span>File Excel</span>

            <div
              className={[
                styles.dropzone,
                isDraggingExcel ? styles.dropzoneActive : "",
                excelFile ? styles.dropzoneFilled : "",
              ].join(" ")}
              onClick={() => excelInputRef.current?.click()}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();

                  excelInputRef.current?.click();
                }
              }}
              onDragOver={(event) => {
                event.preventDefault();

                if (!uploadingExcel) {
                  setIsDraggingExcel(true);
                }
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

                      if (excelInputRef.current) {
                        excelInputRef.current.value = "";
                      }
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

                  <small>
                    Tối đa 20MB · mỗi sheet trở thành 1 bảng dữ liệu
                  </small>
                </div>
              )}
            </div>
          </div>

          {/* SUBMIT */}

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

      {/* =====================================================
          ERROR
          ===================================================== */}

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      {/* =====================================================
          LOADING
          ===================================================== */}

      {loading ? (
        <p className={styles.loading}>Đang tải connections…</p>
      ) : connections.length === 0 ? (
        /* ===================================================
           EMPTY STATE
           =================================================== */

        <section className={styles.empty}>
          <div className={styles.emptyIcon}>DB</div>

          <h2>Chưa có database nào</h2>

          <p>
            Thêm Target MySQL hoặc PostgreSQL bằng tài khoản chỉ có quyền đọc để
            bắt đầu.
          </p>

          <Link to="/connections/new">Tạo connection đầu tiên</Link>
        </section>
      ) : (
        /* ===================================================
           CONNECTION GRID
           =================================================== */

        <div className={styles.grid}>
          {connections.map((connection) => {
            const isExcel = connection.dbType === "excel";

            const isPostgres = connection.dbType === "postgresql";

            const isWorking = workingId === connection.id;

            return (
              <article
                className={[styles.card, isExcel ? styles.cardExcel : ""].join(
                  " ",
                )}
                key={connection.id}
              >
                {/* CARD HEADER */}

                <div className={styles.cardTop}>
                  <div className={styles.dbIdentity}>
                    <div
                      className={[
                        styles.dbIcon,
                        isExcel ? styles.dbIconExcel : "",
                      ].join(" ")}
                    >
                      {isPostgres ? "PG" : isExcel ? "XL" : "MY"}
                    </div>

                    <div>
                      <span className={styles.dbTypeLabel}>
                        {isPostgres
                          ? "PostgreSQL"
                          : isExcel
                            ? "Excel dataset"
                            : "MySQL"}
                      </span>

                      <span className={styles.dbSourceLabel}>Data source</span>
                    </div>
                  </div>

                  <span
                    className={
                      connection.active ? styles.active : styles.inactive
                    }
                  >
                    <i />

                    {connection.active ? "Connected" : "Disconnected"}
                  </span>
                </div>

                {/* NAME */}

                <h2>{connection.name}</h2>

                {/* ENDPOINT */}

                <p className={styles.endpoint}>
                  {isExcel
                    ? "File Excel đã tải lên"
                    : `${connection.host}:${connection.port}`}
                </p>

                {/* DETAILS */}

                <dl>
                  {!isExcel && (
                    <div>
                      <dt>Database</dt>

                      <dd>{connection.databaseName}</dd>
                    </div>
                  )}

                  {!isExcel && (
                    <div>
                      <dt>{isPostgres ? "PostgreSQL user" : "MySQL user"}</dt>

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

                {/* ACTIONS */}

                <div className={styles.actions}>
                  <button
                    type="button"
                    disabled={isWorking}
                    onClick={() => void reconnect(connection)}
                  >
                    {isWorking
                      ? "Đang kiểm tra…"
                      : connection.active
                        ? "Kiểm tra lại"
                        : "Kết nối lại"}
                  </button>

                  <Link to={`/connections/${connection.id}/edit`}>
                    Chỉnh sửa
                  </Link>

                  {connection.active && (
                    <button
                      className={styles.disconnect}
                      type="button"
                      disabled={isWorking}
                      onClick={() => void disconnect(connection)}
                    >
                      Ngắt
                    </button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
