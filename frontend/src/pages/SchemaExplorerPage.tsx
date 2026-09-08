import { useCallback, useEffect, useMemo, useState } from "react";

import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import { connectionApi } from "../api/connectionApi";
import { schemaApi } from "../api/schemaApi";
import type {
  DatabaseConnection,
  DatabaseSchema,
  SchemaColumn,
  SchemaTable,
} from "../api/types";
import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";

import styles from "./SchemaExplorerPage.module.css";

function formatDate(value: string | null | undefined) {
  if (!value) return "Chưa có dữ liệu";

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString("vi-VN", {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

function countForeignKeys(tables: SchemaTable[]) {
  return tables.reduce(
    (total, table) =>
      total + table.columns.filter((column) => column.foreignKey).length,
    0,
  );
}

export function SchemaExplorerPage() {
  const { showToast } = useToast();

  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [connectionId, setConnectionId] = useState<number | null>(null);
  const [schema, setSchema] = useState<DatabaseSchema | null>(null);

  const [search, setSearch] = useState("");

  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);

  const { error, handleError, setError } = useApiError();

  const [editingTableId, setEditingTableId] = useState<number | null>(null);
  const [tableDescription, setTableDescription] = useState("");

  const [editingColumnId, setEditingColumnId] = useState<number | null>(null);
  const [columnDescription, setColumnDescription] = useState("");

  const [savingId, setSavingId] = useState<number | null>(null);

  /**
   * Load active database connections.
   */
  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter(
          (item) => item.active,
        );

        setConnections(available);
        setConnectionId(available[0]?.id ?? null);
      } catch (reason) {
        handleError(reason, "Không tải được danh sách connection.");
      } finally {
        setLoading(false);
      }
    }

    void loadConnections();
  }, [handleError]);

  /**
   * Load schema for selected connection.
   */
  const loadSchema = useCallback(
    async (selectedId: number) => {
      setLoading(true);

      try {
        const result = await schemaApi.get(selectedId);

        setSchema(result);
        setError("");
      } catch (reason) {
        setSchema(null);

        handleError(reason, "Không tải được schema.");
      } finally {
        setLoading(false);
      }
    },
    [handleError, setError],
  );

  useEffect(() => {
    if (connectionId !== null) {
      void loadSchema(connectionId);
    }
  }, [connectionId, loadSchema]);

  /**
   * Synchronize database schema.
   */
  async function syncSchema() {
    if (connectionId === null) {
      return;
    }

    setSyncing(true);

    try {
      const result = await schemaApi.sync(connectionId);

      setSchema(result);
      setError("");

      setEditingTableId(null);
      setEditingColumnId(null);

      showToast(`Đã đồng bộ ${result.tables.length} bảng.`, "success");
    } catch (reason) {
      const message = formatErrorWithSupportCode(
        parseApiError(reason, "Không thể đồng bộ schema."),
      );

      setError(message);
      showToast(message, "error");
    } finally {
      setSyncing(false);
    }
  }

  /**
   * Start editing table description.
   */
  function startEditTable(
    tableId: number,
    description: string | null | undefined,
  ) {
    setEditingColumnId(null);

    setEditingTableId(tableId);
    setTableDescription(description ?? "");
  }

  /**
   * Cancel table description editing.
   */
  function cancelEditTable() {
    setEditingTableId(null);
    setTableDescription("");
  }

  /**
   * Save table description.
   */
  async function saveTableDescription(tableId: number) {
    setSavingId(tableId);

    try {
      const description = tableDescription.trim();

      await schemaApi.updateTableDescription(tableId, description);

      setSchema((current) =>
        current
          ? {
              ...current,
              tables: current.tables.map((table) =>
                table.id === tableId
                  ? {
                      ...table,
                      description: description || null,
                    }
                  : table,
              ),
            }
          : current,
      );

      cancelEditTable();

      showToast("Đã cập nhật mô tả bảng.", "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể cập nhật mô tả bảng."),
        ),
        "error",
      );
    } finally {
      setSavingId(null);
    }
  }

  /**
   * Start editing column description.
   */
  function startEditColumn(
    columnId: number,
    description: string | null | undefined,
  ) {
    setEditingTableId(null);

    setEditingColumnId(columnId);
    setColumnDescription(description ?? "");
  }

  /**
   * Cancel column description editing.
   */
  function cancelEditColumn() {
    setEditingColumnId(null);
    setColumnDescription("");
  }

  /**
   * Save column description.
   */
  async function saveColumnDescription(columnId: number) {
    setSavingId(columnId);

    try {
      const description = columnDescription.trim();

      await schemaApi.updateColumnDescription(columnId, description);

      setSchema((current) =>
        current
          ? {
              ...current,
              tables: current.tables.map((table) => ({
                ...table,
                columns: table.columns.map((column) =>
                  column.id === columnId
                    ? {
                        ...column,
                        description: description || null,
                      }
                    : column,
                ),
              })),
            }
          : current,
      );

      cancelEditColumn();

      showToast("Đã cập nhật mô tả cột.", "success");
    } catch (reason) {
      showToast(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không thể cập nhật mô tả cột."),
        ),
        "error",
      );
    } finally {
      setSavingId(null);
    }
  }

  /**
   * Filter tables by table name or column name.
   */
  const visibleTables = useMemo(() => {
    const keyword = search.trim().toLowerCase();

    if (!keyword) {
      return schema?.tables ?? [];
    }

    return (schema?.tables ?? []).filter(
      (table) =>
        table.name.toLowerCase().includes(keyword) ||
        table.columns.some((column) =>
          column.name.toLowerCase().includes(keyword),
        ),
    );
  }, [schema, search]);

  /**
   * Number of visible columns.
   */
  const visibleColumnCount = useMemo(
    () =>
      visibleTables.reduce((total, table) => total + table.columns.length, 0),
    [visibleTables],
  );

  /**
   * No active connection.
   */
  if (!loading && connections.length === 0) {
    return (
      <section className={styles.emptyPage}>
        <div className={styles.emptyIcon}>DB</div>

        <p className={styles.eyebrow}>DATABASE METADATA</p>

        <h1>Chưa có connection hoạt động</h1>

        <p>
          Hãy thêm và kiểm tra một database connection trước khi đồng bộ schema.
        </p>
      </section>
    );
  }

  return (
    <div className={styles.page}>
      {/* =====================================================
          PAGE HEADER
      ====================================================== */}
      <header className={styles.heading}>
        <div className={styles.headingCopy}>
          <p className={styles.eyebrow}>DATABASE METADATA</p>

          <div className={styles.titleRow}>
            <div className={styles.titleIcon}>⌘</div>

            <div>
              <h1>Schema Explorer</h1>

              <span>Khám phá cấu trúc bảng, cột và quan hệ giữa các bảng.</span>
            </div>
          </div>
        </div>

        <button
          className={styles.syncButton}
          type="button"
          onClick={() => void syncSchema()}
          disabled={syncing || connectionId === null}
        >
          <span className={syncing ? styles.spinner : styles.syncIcon}>
            {syncing ? "" : "↻"}
          </span>

          {syncing ? "Đang đồng bộ…" : "Đồng bộ schema"}
        </button>
      </header>

      {/* =====================================================
          FILTER TOOLBAR
      ====================================================== */}
      <section className={styles.toolbar}>
        <div className={styles.filterField}>
          <label htmlFor="schema-connection">Connection</label>

          <div className={styles.selectWrap}>
            <span className={styles.fieldIcon}>◉</span>

            <select
              id="schema-connection"
              value={connectionId ?? ""}
              onChange={(event) => setConnectionId(Number(event.target.value))}
            >
              {connections.map((connection) => (
                <option key={connection.id} value={connection.id}>
                  {connection.name} — {connection.databaseName}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className={styles.filterField}>
          <label htmlFor="schema-search">Tìm bảng hoặc cột</label>

          <div className={styles.inputWrap}>
            <span className={styles.fieldIcon}>⌕</span>

            <input
              id="schema-search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Ví dụ: orders, customer_id"
            />

            {search && (
              <button
                className={styles.clearButton}
                type="button"
                onClick={() => setSearch("")}
                aria-label="Xóa tìm kiếm"
              >
                ×
              </button>
            )}
          </div>
        </div>

        <div className={styles.syncInfo}>
          <span className={styles.syncLabel}>LẦN ĐỒNG BỘ GẦN NHẤT</span>

          <strong>{formatDate(schema?.lastSyncedAt)}</strong>

          <span className={styles.syncStatus}>
            <i />

            {schema ? "Metadata đã tải" : "Chưa có metadata"}
          </span>
        </div>
      </section>

      {/* =====================================================
          ERROR
      ====================================================== */}
      {error && (
        <div className={styles.error} role="alert">
          <span className={styles.errorIcon}>!</span>

          <span>{error}</span>
        </div>
      )}

      {/* =====================================================
          LOADING
      ====================================================== */}
      {loading ? (
        <section className={styles.loadingCard}>
          <div className={styles.loader} />

          <div>
            <strong>Đang đọc schema…</strong>

            <span>Đang tải metadata từ database.</span>
          </div>
        </section>
      ) : !schema ? (
        /* ===================================================
           NO SCHEMA
        ==================================================== */
        <section className={styles.empty}>
          <div className={styles.emptyIcon}>DB</div>

          <h2>Schema chưa được đồng bộ</h2>

          <p>Nhấn “Đồng bộ schema” để đọc metadata mới nhất từ database.</p>

          <button
            className={styles.emptyAction}
            type="button"
            onClick={() => void syncSchema()}
            disabled={syncing || connectionId === null}
          >
            {syncing ? "Đang đồng bộ…" : "Đồng bộ ngay"}
          </button>
        </section>
      ) : (
        /* ===================================================
           SCHEMA CONTENT
        ==================================================== */
        <div className={styles.content}>
          {/* =================================================
              SIDEBAR
          ================================================== */}
          <aside className={styles.sidebar}>
            <div className={styles.sidebarTop}>
              <span className={styles.sidebarKicker}>DATABASE</span>

              <strong
                className={styles.databaseName}
                title={schema.databaseName}
              >
                {schema.databaseName}
              </strong>
            </div>

            <div className={styles.sidebarStats}>
              <div>
                <strong>{schema.tables.length}</strong>

                <span>Bảng</span>
              </div>

              <div>
                <strong>
                  {schema.tables.reduce(
                    (sum, table) => sum + table.columns.length,
                    0,
                  )}
                </strong>

                <span>Cột</span>
              </div>

              <div>
                <strong>{countForeignKeys(schema.tables)}</strong>

                <span>FK</span>
              </div>
            </div>

            <div className={styles.sidebarList}>
              <div className={styles.sidebarListHeader}>
                <span>BẢNG</span>

                <span>
                  {visibleTables.length}/{schema.tables.length}
                </span>
              </div>

              {schema.tables.map((table) => {
                const matched = visibleTables.some(
                  (item) => item.id === table.id,
                );

                return (
                  <a
                    className={`${styles.tableNavItem} ${
                      matched ? "" : styles.tableNavMuted
                    }`}
                    key={table.id}
                    href={`#table-${table.id}`}
                    title={table.name}
                  >
                    <span className={styles.tableDot} />

                    <span>{table.name}</span>

                    <small>{table.columns.length}</small>
                  </a>
                );
              })}
            </div>
          </aside>

          {/* =================================================
              MAIN
          ================================================== */}
          <main className={styles.main}>
            <div className={styles.resultsHeader}>
              <div>
                <p className={styles.eyebrow}>SCHEMA OVERVIEW</p>

                <h2>
                  {search ? "Kết quả tìm kiếm" : "Các bảng trong database"}
                </h2>
              </div>

              <div className={styles.resultCount}>
                <strong>{visibleTables.length}</strong>

                <span>bảng · {visibleColumnCount} cột</span>
              </div>
            </div>

            {/* =================================================
                NO SEARCH RESULT
            ================================================== */}
            {visibleTables.length === 0 ? (
              <section className={styles.noResult}>
                <div className={styles.noResultIcon}>⌕</div>

                <h3>Không tìm thấy kết quả</h3>

                <p>Thử tìm bằng tên bảng hoặc tên cột khác.</p>

                <button type="button" onClick={() => setSearch("")}>
                  Xóa bộ lọc
                </button>
              </section>
            ) : (
              <div className={styles.tableList}>
                {visibleTables.map((table) => (
                  <details
                    className={styles.tableCard}
                    id={`table-${table.id}`}
                    key={table.id}
                    open={visibleTables.length <= 3 || Boolean(search)}
                  >
                    {/* =================================================
                          TABLE HEADER
                      ================================================== */}
                    <summary className={styles.tableSummary}>
                      <div className={styles.tableTitle}>
                        <span className={styles.tableIcon}>▦</span>

                        <div>
                          <div className={styles.tableNameRow}>
                            <strong>{table.name}</strong>

                            <span className={styles.countBadge}>
                              {table.columns.length} cột
                            </span>
                          </div>

                          <small>
                            {table.description || "Chưa có mô tả bảng"}
                          </small>
                        </div>
                      </div>

                      <span className={styles.chevron}>⌄</span>
                    </summary>

                    <div className={styles.tableBody}>
                      {/* =================================================
                            TABLE DESCRIPTION
                        ================================================== */}
                      <div className={styles.descriptionBlock}>
                        {editingTableId === table.id ? (
                          <div className={styles.editor}>
                            <label htmlFor={`table-description-${table.id}`}>
                              Mô tả bảng
                            </label>

                            <textarea
                              id={`table-description-${table.id}`}
                              value={tableDescription}
                              onChange={(event) =>
                                setTableDescription(event.target.value)
                              }
                              placeholder="Nhập mô tả giúp AI hiểu mục đích của bảng…"
                              rows={3}
                              autoFocus
                            />

                            <div className={styles.editorActions}>
                              <button
                                className={styles.primarySmall}
                                type="button"
                                disabled={savingId === table.id}
                                onClick={() =>
                                  void saveTableDescription(table.id)
                                }
                              >
                                {savingId === table.id
                                  ? "Đang lưu…"
                                  : "Lưu mô tả"}
                              </button>

                              <button
                                className={styles.secondarySmall}
                                type="button"
                                disabled={savingId === table.id}
                                onClick={cancelEditTable}
                              >
                                Hủy
                              </button>
                            </div>
                          </div>
                        ) : (
                          <>
                            <div className={styles.descriptionText}>
                              <span className={styles.descriptionLabel}>
                                MÔ TẢ
                              </span>

                              <p
                                className={
                                  !table.description ? styles.missing : ""
                                }
                              >
                                {table.description ||
                                  "Chưa có mô tả. Thêm mô tả để cải thiện ngữ cảnh cho AI."}
                              </p>
                            </div>

                            <button
                              className={styles.textButton}
                              type="button"
                              disabled={savingId !== null}
                              onClick={() =>
                                startEditTable(table.id, table.description)
                              }
                            >
                              ✎ Sửa mô tả
                            </button>
                          </>
                        )}
                      </div>

                      {/* =================================================
                            COLUMNS
                        ================================================== */}
                      <div className={styles.columns}>
                        <div className={styles.columnHeader}>
                          <span>TÊN CỘT</span>

                          <span>KIỂU DỮ LIỆU</span>

                          <span>RÀNG BUỘC</span>
                        </div>

                        {table.columns.map((column: SchemaColumn) => (
                          <div className={styles.column} key={column.id}>
                            {/* COLUMN NAME */}
                            <div className={styles.columnName}>
                              <div className={styles.nameLine}>
                                <strong>{column.name}</strong>

                                {column.primaryKey && (
                                  <span
                                    className={`${styles.constraintBadge} ${styles.pk}`}
                                  >
                                    PK
                                  </span>
                                )}

                                {column.foreignKey && (
                                  <span
                                    className={`${styles.constraintBadge} ${styles.fk}`}
                                  >
                                    FK
                                  </span>
                                )}
                              </div>

                              {/* COLUMN DESCRIPTION */}
                              {editingColumnId === column.id ? (
                                <div className={styles.columnEditor}>
                                  <textarea
                                    value={columnDescription}
                                    onChange={(event) =>
                                      setColumnDescription(event.target.value)
                                    }
                                    placeholder="Nhập mô tả cho cột…"
                                    rows={2}
                                    autoFocus
                                  />

                                  <div className={styles.editorActions}>
                                    <button
                                      className={styles.primaryTiny}
                                      type="button"
                                      disabled={savingId === column.id}
                                      onClick={() =>
                                        void saveColumnDescription(column.id)
                                      }
                                    >
                                      {savingId === column.id
                                        ? "Đang lưu…"
                                        : "Lưu"}
                                    </button>

                                    <button
                                      className={styles.secondaryTiny}
                                      type="button"
                                      disabled={savingId === column.id}
                                      onClick={cancelEditColumn}
                                    >
                                      Hủy
                                    </button>
                                  </div>
                                </div>
                              ) : (
                                <div className={styles.columnDescription}>
                                  <span
                                    className={
                                      !column.description ? styles.missing : ""
                                    }
                                  >
                                    {column.description || "Chưa có mô tả"}
                                  </span>

                                  <button
                                    className={styles.editLink}
                                    type="button"
                                    disabled={savingId !== null}
                                    onClick={() =>
                                      startEditColumn(
                                        column.id,
                                        column.description,
                                      )
                                    }
                                  >
                                    Sửa
                                  </button>
                                </div>
                              )}
                            </div>

                            {/* DATA TYPE */}
                            <code className={styles.dataType}>
                              {column.dataType}
                            </code>

                            {/* CONSTRAINTS */}
                            <div className={styles.constraints}>
                              {column.primaryKey && (
                                <span
                                  className={`${styles.pill} ${styles.pillPk}`}
                                >
                                  Khóa chính
                                </span>
                              )}

                              {column.foreignKey && (
                                <span
                                  className={`${styles.pill} ${styles.pillFk}`}
                                >
                                  Khóa ngoại
                                </span>
                              )}

                              {column.nullable ? (
                                <span
                                  className={`${styles.pill} ${styles.pillNeutral}`}
                                >
                                  NULL
                                </span>
                              ) : (
                                <span
                                  className={`${styles.pill} ${styles.pillRequired}`}
                                >
                                  NOT NULL
                                </span>
                              )}

                              {column.foreignKey &&
                                column.referencedTable &&
                                column.referencedColumn && (
                                  <span className={styles.reference}>
                                    → {column.referencedTable}.
                                    {column.referencedColumn}
                                  </span>
                                )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  </details>
                ))}
              </div>
            )}
          </main>
        </div>
      )}
    </div>
  );
}
