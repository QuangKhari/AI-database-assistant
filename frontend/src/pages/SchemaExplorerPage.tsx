import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ChevronDown,
  ChevronRight,
  Columns3,
  Database,
  Pencil,
  RefreshCw,
  Search,
  Table2,
} from "lucide-react";

import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";
import { connectionApi } from "../api/connectionApi";
import { schemaApi } from "../api/schemaApi";
import type { DatabaseConnection, DatabaseSchema } from "../api/types";
import { useToast } from "../context/ToastContext";

import styles from "./SchemaExplorerPage.module.css";

export function SchemaExplorerPage() {
  const { showToast } = useToast();

  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [connectionId, setConnectionId] = useState<number | null>(null);
  const [schema, setSchema] = useState<DatabaseSchema | null>(null);

  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState("");

  // Đang sửa mô tả bảng
  const [editingTableId, setEditingTableId] = useState<number | null>(null);
  const [tableDescription, setTableDescription] = useState("");

  // Đang sửa mô tả cột
  const [editingColumnId, setEditingColumnId] = useState<number | null>(null);
  const [columnDescription, setColumnDescription] = useState("");

  // ID đang lưu description
  const [savingId, setSavingId] = useState<number | null>(null);
  const [expandedTableIds, setExpandedTableIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter(
          (item) => item.active,
        );

        setConnections(available);
        setConnectionId(available[0]?.id ?? null);
      } catch (reason) {
        setError(
          formatErrorWithSupportCode(
            parseApiError(reason, "Không tải được connections."),
          ),
        );
      } finally {
        setLoading(false);
      }
    }

    void loadConnections();
  }, []);

  const loadSchema = useCallback(async (selectedId: number) => {
    setLoading(true);

    try {
      const result = await schemaApi.get(selectedId);
      setSchema(result);
      setExpandedTableIds(
        new Set(
          result.tables.length <= 4
            ? result.tables.map((table) => table.id)
            : result.tables.slice(0, 1).map((table) => table.id),
        ),
      );
      setError("");
    } catch (reason) {
      setSchema(null);
      setError(
        formatErrorWithSupportCode(
          parseApiError(reason, "Không tải được schema."),
        ),
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (connectionId !== null) {
      void loadSchema(connectionId);
    }
  }, [connectionId, loadSchema]);

  async function syncSchema() {
    if (connectionId === null) return;

    setSyncing(true);

    try {
      const result = await schemaApi.sync(connectionId);

      setSchema(result);
      setExpandedTableIds(
        new Set(
          result.tables.length <= 4
            ? result.tables.map((table) => table.id)
            : result.tables.slice(0, 1).map((table) => table.id),
        ),
      );
      setError("");

      // Hủy trạng thái edit nếu đang đồng bộ
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
   * Bắt đầu sửa mô tả bảng
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
   * Hủy sửa mô tả bảng
   */
  function cancelEditTable() {
    setEditingTableId(null);
    setTableDescription("");
  }

  /**
   * Lưu mô tả bảng
   *
   * BE:
   * PUT /api/schema/tables/{tableId}
   *
   * Body:
   * {
   *   "description": "..."
   * }
   */
  async function saveTableDescription(tableId: number) {
    setSavingId(tableId);

    try {
      await schemaApi.updateTableDescription(tableId, tableDescription.trim());

      setSchema((currentSchema) => {
        if (!currentSchema) {
          return currentSchema;
        }

        return {
          ...currentSchema,
          tables: currentSchema.tables.map((table) =>
            table.id === tableId
              ? {
                  ...table,
                  description: tableDescription.trim() || null,
                }
              : table,
          ),
        };
      });

      setEditingTableId(null);
      setTableDescription("");

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
   * Bắt đầu sửa mô tả cột
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
   * Hủy sửa mô tả cột
   */
  function cancelEditColumn() {
    setEditingColumnId(null);
    setColumnDescription("");
  }

  /**
   * Lưu mô tả cột
   *
   * BE:
   * PUT /api/schema/columns/{columnId}
   *
   * Body:
   * {
   *   "description": "..."
   * }
   */
  async function saveColumnDescription(columnId: number) {
    setSavingId(columnId);

    try {
      await schemaApi.updateColumnDescription(
        columnId,
        columnDescription.trim(),
      );

      setSchema((currentSchema) => {
        if (!currentSchema) {
          return currentSchema;
        }

        return {
          ...currentSchema,
          tables: currentSchema.tables.map((table) => ({
            ...table,
            columns: table.columns.map((column) =>
              column.id === columnId
                ? {
                    ...column,
                    description: columnDescription.trim() || null,
                  }
                : column,
            ),
          })),
        };
      });

      setEditingColumnId(null);
      setColumnDescription("");

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

  useEffect(() => {
    if (!search.trim()) return;
    setExpandedTableIds((current) => {
      const next = new Set(current);
      visibleTables.forEach((table) => next.add(table.id));
      return next;
    });
  }, [search, visibleTables]);

  function toggleTable(tableId: number) {
    setExpandedTableIds((current) => {
      const next = new Set(current);
      if (next.has(tableId)) next.delete(tableId);
      else next.add(tableId);
      return next;
    });
  }

  function expandAllVisible() {
    setExpandedTableIds(new Set(visibleTables.map((table) => table.id)));
  }

  if (!loading && connections.length === 0) {
    return (
      <section className={styles.empty}>
        <h1>Chưa có connection hoạt động</h1>

        <p>Hãy thêm và kiểm tra một Target MySQL trước khi đồng bộ schema.</p>
      </section>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div className={styles.headingCopy}>
          <span className={styles.headingIcon}>
            <Database size={22} />
          </span>
          <div>
            <p>Cấu trúc dữ liệu</p>
            <h1>Schema Explorer</h1>
            <span>Khám phá bảng, cột và quan hệ trong database của bạn.</span>
          </div>
        </div>

        <button
          type="button"
          onClick={syncSchema}
          disabled={syncing || connectionId === null}
        >
          <RefreshCw size={16} className={syncing ? styles.spinning : ""} />
          {syncing ? "Đang đồng bộ…" : "Đồng bộ schema"}
        </button>
      </header>

      <section className={styles.toolbar}>
        <label>
          Connection
          <select
            value={connectionId ?? ""}
            onChange={(event) => setConnectionId(Number(event.target.value))}
          >
            {connections.map((connection) => (
              <option key={connection.id} value={connection.id}>
                {connection.name} — {connection.databaseName}
              </option>
            ))}
          </select>
        </label>

        <label>
          Tìm trong schema
          <span className={styles.searchBox}>
            <Search size={16} />
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Tên bảng hoặc cột…"
            />
          </span>
        </label>

        <div className={styles.syncInfo}>
          <span>Lần đồng bộ gần nhất</span>

          <strong>
            {schema?.lastSyncedAt
              ? new Date(schema.lastSyncedAt).toLocaleString("vi-VN")
              : "Chưa có"}
          </strong>
        </div>
      </section>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      {loading ? (
        <div className={styles.loading}><span className={styles.spinner} />Đang đọc cấu trúc database…</div>
      ) : !schema ? (
        <section className={styles.empty}>
          <h2>Schema chưa được đồng bộ</h2>

          <p>
            Nhấn “Đồng bộ schema” để đọc metadata từ database. Dữ liệu cũ chỉ bị
            thay thế khi quá trình thành công.
          </p>
        </section>
      ) : (
        <div className={styles.content}>
          <aside className={styles.schemaSummary}>
            <div><Table2 size={20} /><span><strong>{visibleTables.length}</strong> / {schema.tables.length} bảng</span></div>
            <small title={schema.databaseName}>{schema.databaseName}</small>
            <div className={styles.expandActions}>
              <button type="button" onClick={expandAllVisible}>Mở tất cả</button>
              <button type="button" onClick={() => setExpandedTableIds(new Set())}>Thu gọn</button>
            </div>
          </aside>

          <div className={styles.tableList}>
            {visibleTables.length === 0 ? (
              <p className={styles.noResult}>
                Không tìm thấy bảng hoặc cột phù hợp.
              </p>
            ) : (
              visibleTables.map((table) => (
                <article className={styles.tableCard} key={table.id}>
                  <button
                    type="button"
                    className={styles.tableToggle}
                    aria-expanded={expandedTableIds.has(table.id)}
                    onClick={() => toggleTable(table.id)}
                  >
                    <span className={styles.tableIdentity}>
                      <span className={styles.tableIcon}><Table2 size={17} /></span>
                      <span><strong>{table.name}</strong><small>{table.columns.length} cột</small></span>
                    </span>
                    {expandedTableIds.has(table.id) ? <ChevronDown size={18} /> : <ChevronRight size={18} />}
                  </button>

                  {expandedTableIds.has(table.id) && <div className={styles.tableBody}>
                  <section className={styles.descriptionPanel}>
                    <div className={styles.descriptionHeading}><span>Mô tả bảng</span>{editingTableId !== table.id && <button type="button" disabled={savingId !== null} onClick={() => startEditTable(table.id, table.description)}><Pencil size={14} /> Sửa mô tả</button>}</div>
                    {editingTableId === table.id ? (
                      <div className={styles.descriptionEditor}>
                        <label>
                          <span>Giúp AI hiểu bảng này dùng để lưu thông tin gì</span>
                          <textarea
                            value={tableDescription}
                            onChange={(event) =>
                              setTableDescription(event.target.value)
                            }
                            placeholder="Ví dụ: Lưu thông tin đơn hàng của khách hàng"
                            rows={3}
                          />
                        </label>
                        <div className={styles.editorActions}>
                          <button type="button" className={styles.primaryButton} disabled={savingId === table.id} onClick={() => void saveTableDescription(table.id)}>{savingId === table.id ? "Đang lưu…" : "Lưu mô tả"}</button>
                          <button type="button" disabled={savingId === table.id} onClick={cancelEditTable}>Hủy</button>
                        </div>
                      </div>
                    ) : (
                      <p className={table.description ? styles.descriptionText : styles.descriptionEmpty}>{table.description || "Chưa có mô tả. Thêm mô tả để Gemini hiểu schema chính xác hơn."}</p>
                    )}
                  </section>

                  <div className={styles.columns}>
                    <div className={styles.columnHeader}>
                      <span>Tên cột và mô tả</span>
                      <span>Kiểu dữ liệu</span>
                      <span>Ràng buộc</span>
                    </div>
                    {table.columns.map((column) => (
                      <div className={styles.column} key={column.id}>
                        <div className={styles.columnInfo}>
                          <strong><Columns3 size={14} />{column.name}</strong>
                          {editingColumnId === column.id ? (
                            <div className={styles.columnEditor}>
                              <textarea
                                value={columnDescription}
                                onChange={(event) =>
                                  setColumnDescription(event.target.value)
                                }
                                placeholder="Mô tả ý nghĩa của cột…"
                                rows={2}
                              />
                              <div className={styles.editorActions}>
                                <button type="button" className={styles.primaryButton} disabled={savingId === column.id} onClick={() => void saveColumnDescription(column.id)}>{savingId === column.id ? "Đang lưu…" : "Lưu"}</button>
                                <button type="button" disabled={savingId === column.id} onClick={cancelEditColumn}>Hủy</button>
                              </div>
                            </div>
                          ) : (
                            <span className={styles.columnDescription}>{column.description || "Chưa có mô tả"}<button type="button" aria-label={`Sửa mô tả cột ${column.name}`} disabled={savingId !== null} onClick={() => startEditColumn(column.id, column.description)}><Pencil size={12} /> Sửa</button></span>
                          )}
                        </div>
                        <code>{column.dataType}</code>
                        <span className={styles.badges}>
                          {column.primaryKey && <i>PK</i>}
                          {column.foreignKey && <i>FK</i>}
                          {column.nullable && <i className={styles.muted}>Cho phép NULL</i>}
                          {column.foreignKey && <small>→ {column.referencedTable}.{column.referencedColumn}</small>}
                        </span>
                      </div>
                    ))}
                  </div>
                  </div>}
                </article>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
