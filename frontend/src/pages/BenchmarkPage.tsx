import { useCallback, useEffect, useState, type FormEvent } from "react";

import { benchmarkApi } from "../api/benchmarkApi";
import { connectionApi } from "../api/connectionApi";

import type {
  BenchmarkQuestion,
  BenchmarkRunResponse,
  DatabaseConnection,
} from "../api/types";

import { useToast } from "../context/ToastContext";
import { useApiError } from "../hook/useApiError";

import styles from "./BenchmarkPage.module.css";

type LanguageFilter = "ALL" | "VI" | "EN";

const MAX_BENCHMARK_QUESTIONS = 30;

export function BenchmarkPage() {
  const { showToast } = useToast();

  // ============================================================
  // CONNECTION
  // ============================================================

  const [connections, setConnections] = useState<DatabaseConnection[]>([]);

  const [connectionId, setConnectionId] = useState<number | null>(null);

  // ============================================================
  // QUESTIONS
  // ============================================================

  const [questions, setQuestions] = useState<BenchmarkQuestion[]>([]);

  const [editingId, setEditingId] = useState<number | null>(null);

  // ============================================================
  // FILTER
  // ============================================================

  /**
   * Tab đang được chọn.
   *
   * ALL -> tất cả
   * VI  -> tiếng Việt
   * EN  -> tiếng Anh
   */
  const [languageFilter, setLanguageFilter] = useState<LanguageFilter>("ALL");

  // ============================================================
  // LOADING / ERROR
  // ============================================================

  const [loading, setLoading] = useState(true);

  const { error, handleError, notifyError, setError } = useApiError();

  // ============================================================
  // FORM
  // ============================================================

  const [showForm, setShowForm] = useState(false);

  const [language, setLanguage] = useState<"VI" | "EN">("VI");

  const [questionText, setQuestionText] = useState("");

  /**
   * SQL mong đợi.
   *
   * Ban đầu rỗng.
   *
   * AI sẽ đề xuất SQL.
   * Người dùng có thể chỉnh sửa.
   * Sau đó mới lưu.
   */
  const [expectedSql, setExpectedSql] = useState("");

  const [saving, setSaving] = useState(false);

  /**
   * Đang gọi AI để tạo SQL đề xuất.
   */
  const [generatingSql, setGeneratingSql] = useState(false);

  // ============================================================
  // BENCHMARK
  // ============================================================

  const [running, setRunning] = useState(false);

  const [runResult, setRunResult] = useState<BenchmarkRunResponse | null>(null);

  // ============================================================
  // DELETE
  // ============================================================

  const [deletingId, setDeletingId] = useState<number | null>(null);

  // ============================================================
  // LOAD CONNECTIONS
  // ============================================================

  useEffect(() => {
    async function loadConnections() {
      try {
        const available = (await connectionApi.list()).filter(
          (item) => item.active,
        );

        setConnections(available);

        setConnectionId(available[0]?.id ?? null);
      } catch (reason) {
        handleError(reason, "Không tải được connections.");
      } finally {
        setLoading(false);
      }
    }

    void loadConnections();
  }, [handleError]);

  // ============================================================
  // LOAD QUESTIONS
  // ============================================================

  const loadQuestions = useCallback(
    async (selectedConnectionId: number, filter: LanguageFilter) => {
      setLoading(true);

      try {
        const result = await benchmarkApi.questions(
          selectedConnectionId,
          filter === "ALL" ? undefined : filter,
        );

        setQuestions(result);
        setError("");
      } catch (reason) {
        handleError(reason, "Không tải được danh sách câu hỏi benchmark.");
      } finally {
        setLoading(false);
      }
    },
    [handleError, setError],
  );

  // ============================================================
  // CONNECTION / FILTER CHANGED
  // ============================================================

  useEffect(() => {
    setRunResult(null);

    if (connectionId !== null) {
      void loadQuestions(connectionId, languageFilter);
    }
  }, [connectionId, languageFilter, loadQuestions]);

  // ============================================================
  // RESET FORM
  // ============================================================

  function resetForm() {
    setLanguage("VI");
    setQuestionText("");
    setExpectedSql("");
    setGeneratingSql(false);
    setSaving(false);

    /*
     * Rất quan trọng:
     *
     * null = form Thêm
     * id   = form Sửa
     */
    setEditingId(null);
  }

  // ============================================================
  // OPEN ADD FORM
  // ============================================================

  function openAddForm() {
    if (questions.length >= MAX_BENCHMARK_QUESTIONS) {
      showToast(
        `Đã đạt giới hạn tối đa ${MAX_BENCHMARK_QUESTIONS} câu hỏi benchmark cho connection này.`,
        "error",
      );

      return;
    }

    resetForm();
    setShowForm(true);
  }

  // ============================================================
  // OPEN EDIT FORM
  // ============================================================

  function openEditForm(question: BenchmarkQuestion) {
    if (running || saving || generatingSql || deletingId !== null) {
      return;
    }

    setEditingId(question.id);

    setLanguage(question.language);

    setQuestionText(question.questionText);

    setExpectedSql(question.expectedSql);

    setShowForm(true);
  }

  // ============================================================
  // CLOSE FORM
  // ============================================================

  function closeForm() {
    if (saving || generatingSql) {
      return;
    }

    resetForm();
    setShowForm(false);
  }

  // ============================================================
  // AI GENERATE EXPECTED SQL
  // ============================================================

  async function generateExpectedSql() {
    if (connectionId === null) {
      showToast("Vui lòng chọn connection.", "error");

      return;
    }

    const cleanQuestion = questionText.trim();

    if (!cleanQuestion) {
      showToast("Vui lòng nhập câu hỏi trước.", "error");

      return;
    }

    setGeneratingSql(true);

    try {
      const result = await benchmarkApi.generateExpectedSql(
        connectionId,
        cleanQuestion,
      );

      const generatedSql = result.sql?.trim() ?? "";

      if (!generatedSql) {
        showToast("AI không tạo được SQL.", "error");

        return;
      }

      /*
       * AI chỉ đề xuất.
       *
       * Người dùng vẫn có thể chỉnh sửa
       * trực tiếp trong textarea.
       */
      setExpectedSql(generatedSql);

      showToast(
        "AI đã tạo SQL đề xuất. Hãy kiểm tra hoặc chỉnh sửa trước khi lưu.",
        "success",
      );
    } catch (reason) {
      notifyError(reason, "Không thể tạo SQL bằng AI.");
    } finally {
      setGeneratingSql(false);
    }
  }

  // ============================================================
  // ADD QUESTION
  // ============================================================

  async function addQuestion(event: FormEvent) {
    event.preventDefault();

    if (connectionId === null) {
      return;
    }

    const cleanQuestion = questionText.trim();

    const cleanSql = expectedSql.trim();

    // ----------------------------------------------------------
    // VALIDATE QUESTION
    // ----------------------------------------------------------

    if (!cleanQuestion) {
      showToast("Vui lòng nhập câu hỏi benchmark.", "error");

      return;
    }

    // ----------------------------------------------------------
    // VALIDATE SQL
    // ----------------------------------------------------------

    if (!cleanSql) {
      showToast("Vui lòng tạo hoặc nhập SQL mong đợi.", "error");

      return;
    }

    // ----------------------------------------------------------
    // LIMIT
    // ----------------------------------------------------------

    if (questions.length >= MAX_BENCHMARK_QUESTIONS) {
      showToast(
        `Đã đạt giới hạn tối đa ${MAX_BENCHMARK_QUESTIONS} câu hỏi benchmark cho connection này.`,
        "error",
      );

      return;
    }

    setSaving(true);

    try {
      await benchmarkApi.addQuestion(connectionId, {
        language,
        questionText: cleanQuestion,
        expectedSql: cleanSql,
      });

      /*
       * Sau khi thêm:
       *
       * editingId = null
       * questionText = ""
       * expectedSql = ""
       * language = VI
       */
      resetForm();

      setShowForm(false);

      showToast("Đã lưu câu hỏi benchmark.", "success");

      /*
       * Reload để đảm bảo danh sách
       * đồng bộ với backend.
       */
      await loadQuestions(connectionId, languageFilter);
    } catch (reason) {
      notifyError(reason, "Không thể thêm câu hỏi benchmark.");
    } finally {
      setSaving(false);
    }
  }

  // ============================================================
  // UPDATE QUESTION
  // ============================================================

  async function updateQuestion(event: FormEvent) {
    event.preventDefault();

    if (connectionId === null || editingId === null) {
      return;
    }

    const cleanQuestion = questionText.trim();

    const cleanSql = expectedSql.trim();

    // ----------------------------------------------------------
    // VALIDATE QUESTION
    // ----------------------------------------------------------

    if (!cleanQuestion) {
      showToast("Vui lòng nhập câu hỏi benchmark.", "error");

      return;
    }

    // ----------------------------------------------------------
    // VALIDATE SQL
    // ----------------------------------------------------------

    if (!cleanSql) {
      showToast("Vui lòng tạo hoặc nhập SQL mong đợi.", "error");

      return;
    }

    setSaving(true);

    try {
      await benchmarkApi.updateQuestion(connectionId, editingId, {
        language,
        questionText: cleanQuestion,
        expectedSql: cleanSql,
      });

      /*
       * Câu hỏi hoặc Expected SQL đã thay đổi.
       *
       * Kết quả benchmark cũ không còn
       * đại diện cho câu hỏi mới.
       */
      setRunResult(null);

      /*
       * Reload lại danh sách.
       *
       * Trường hợp:
       *
       * VI -> EN
       * EN -> VI
       *
       * cũng được xử lý chính xác
       * theo filter hiện tại.
       */
      await loadQuestions(connectionId, languageFilter);

      /*
       * Reset trạng thái Edit.
       */
      resetForm();

      setShowForm(false);

      showToast(
        "Đã cập nhật câu hỏi benchmark. Kết quả benchmark cũ đã được xoá.",
        "success",
      );
    } catch (reason) {
      notifyError(reason, "Không thể cập nhật câu hỏi benchmark.");
    } finally {
      setSaving(false);
    }
  }

  // ============================================================
  // DELETE
  // ============================================================

  async function deleteQuestion(questionId: number) {
    if (connectionId === null) {
      return;
    }

    if (!window.confirm("Xoá câu hỏi benchmark này?")) {
      return;
    }

    setDeletingId(questionId);

    try {
      await benchmarkApi.deleteQuestion(connectionId, questionId);

      setQuestions((current) =>
        current.filter((item) => item.id !== questionId),
      );

      /*
       * Kết quả benchmark cũ không còn
       * phù hợp với danh sách hiện tại.
       */
      setRunResult(null);

      showToast("Đã xoá câu hỏi benchmark.", "success");
    } catch (reason) {
      notifyError(reason, "Không thể xoá câu hỏi benchmark.");
    } finally {
      setDeletingId(null);
    }
  }

  // ============================================================
  // RUN BENCHMARK
  // ============================================================

  async function runBenchmark() {
    if (connectionId === null) {
      return;
    }

    if (questions.length === 0) {
      showToast("Chưa có câu hỏi benchmark nào cho bộ lọc hiện tại.", "error");

      return;
    }

    setRunning(true);
    setRunResult(null);

    try {
      /*
       * ALL:
       *   language = undefined
       *   -> backend chạy tất cả.
       *
       * VI:
       *   language = "VI"
       *   -> backend chỉ chạy VI.
       *
       * EN:
       *   language = "EN"
       *   -> backend chỉ chạy EN.
       */
      const result = await benchmarkApi.run(
        connectionId,
        languageFilter === "ALL" ? undefined : languageFilter,
      );

      setRunResult(result);

      showToast(
        `Hoàn tất: ${result.correctCount}/${result.totalQuestions} câu đúng.`,
        "success",
      );
    } catch (reason) {
      notifyError(reason, "Không thể chạy benchmark.");
    } finally {
      setRunning(false);
    }
  }

  // ============================================================
  // LOADING
  // ============================================================

  if (loading && connections.length === 0) {
    return (
      <div className={styles.page}>
        <div className={styles.loading}>Đang tải...</div>
      </div>
    );
  }

  // ============================================================
  // UI
  // ============================================================

  return (
    <div className={styles.page}>
      {/* ======================================================
          HEADER
      ====================================================== */}

      <div className={styles.header}>
        <div>
          <h1>Benchmark</h1>

          <p>
            Đánh giá độ chính xác của AI khi chuyển câu hỏi tự nhiên thành SQL.
          </p>
        </div>

        <div className={styles.headerActions}>
          {/* CONNECTION */}

          <select
            value={connectionId ?? ""}
            onChange={(event) => {
              const value = Number(event.target.value);

              setConnectionId(Number.isFinite(value) ? value : null);
            }}
            disabled={running || saving || generatingSql}
          >
            <option value="">Chọn connection</option>

            {connections.map((connection) => (
              <option key={connection.id} value={connection.id}>
                {connection.name}
              </option>
            ))}
          </select>

          {/* ADD */}

          <button
            type="button"
            onClick={openAddForm}
            disabled={
              connectionId === null ||
              running ||
              saving ||
              generatingSql ||
              questions.length >= MAX_BENCHMARK_QUESTIONS
            }
          >
            + Thêm câu hỏi
          </button>

          {/* RUN */}

          <button
            type="button"
            onClick={runBenchmark}
            disabled={
              connectionId === null ||
              running ||
              saving ||
              generatingSql ||
              questions.length === 0
            }
          >
            {running ? "⏳ Đang chạy..." : "▶ Chạy Benchmark"}
          </button>
        </div>
      </div>

      {/* ======================================================
          ERROR
      ====================================================== */}

      {error && <div className={styles.error}>{error}</div>}

      {/* ======================================================
          LANGUAGE FILTER
      ====================================================== */}

      <div className={styles.toolbar}>
        <div className={styles.tabs}>
          <button
            type="button"
            className={languageFilter === "ALL" ? styles.activeTab : ""}
            onClick={() => setLanguageFilter("ALL")}
            disabled={running}
          >
            Tất cả
          </button>

          <button
            type="button"
            className={languageFilter === "VI" ? styles.activeTab : ""}
            onClick={() => setLanguageFilter("VI")}
            disabled={running}
          >
            Tiếng Việt
          </button>

          <button
            type="button"
            className={languageFilter === "EN" ? styles.activeTab : ""}
            onClick={() => setLanguageFilter("EN")}
            disabled={running}
          >
            English
          </button>
        </div>

        <div className={styles.questionCount}>
          {questions.length}/{MAX_BENCHMARK_QUESTIONS} câu hỏi
        </div>
      </div>

      {/* ======================================================
          ADD / EDIT FORM
      ====================================================== */}

      {showForm && (
        <div className={styles.formCard}>
          {/* FORM HEADER */}

          <div className={styles.formHeader}>
            <div>
              <h2>
                {editingId === null
                  ? "Thêm câu hỏi benchmark"
                  : "Chỉnh sửa câu hỏi benchmark"}
              </h2>

              <p>
                {editingId === null
                  ? "AI đề xuất SQL → người dùng kiểm tra/chỉnh sửa → lưu làm SQL mong đợi."
                  : "Chỉnh sửa câu hỏi hoặc SQL mong đợi. Sau khi lưu, kết quả benchmark cũ sẽ được xoá."}
              </p>
            </div>

            <button
              type="button"
              onClick={closeForm}
              disabled={saving || generatingSql}
            >
              ✕
            </button>
          </div>

          {/* FORM */}

          <form
            onSubmit={editingId === null ? addQuestion : updateQuestion}
            className={styles.form}
          >
            {/* LANGUAGE */}

            <div className={styles.field}>
              <label htmlFor="benchmark-language">Ngôn ngữ</label>

              <select
                id="benchmark-language"
                value={language}
                onChange={(event) =>
                  setLanguage(event.target.value as "VI" | "EN")
                }
                disabled={saving || generatingSql}
              >
                <option value="VI">Tiếng Việt</option>

                <option value="EN">English</option>
              </select>
            </div>

            {/* QUESTION */}

            <div className={styles.field}>
              <label htmlFor="benchmark-question">Câu hỏi</label>

              <textarea
                id="benchmark-question"
                value={questionText}
                onChange={(event) => setQuestionText(event.target.value)}
                placeholder={
                  language === "VI"
                    ? "Ví dụ: Liệt kê 10 khách hàng có tổng giá trị đơn hàng cao nhất"
                    : "Example: List the 10 customers with the highest total order value"
                }
                rows={4}
                disabled={saving || generatingSql}
              />
            </div>

            {/* EXPECTED SQL */}

            <div className={styles.sqlSection}>
              <div className={styles.sqlHeader}>
                <div>
                  <label htmlFor="expected-sql">SQL mong đợi</label>

                  <p>
                    AI đề xuất SQL để bạn kiểm tra. Bạn có thể chỉnh sửa trực
                    tiếp trước khi lưu.
                  </p>
                </div>

                <button
                  type="button"
                  onClick={generateExpectedSql}
                  disabled={
                    connectionId === null ||
                    saving ||
                    generatingSql ||
                    !questionText.trim()
                  }
                >
                  {generatingSql
                    ? "⏳ AI đang tạo SQL..."
                    : "✨ AI tạo SQL mong đợi"}
                </button>
              </div>

              <textarea
                id="expected-sql"
                className={styles.expectedSqlInput}
                value={expectedSql}
                onChange={(event) => setExpectedSql(event.target.value)}
                placeholder="SQL mong đợi ..."
                rows={10}
                spellCheck={false}
                disabled={saving || generatingSql}
              />

              <div className={styles.sqlHint}>
                ⚠️ Đây là SQL chuẩn dùng để đánh giá kết quả AI khi chạy
                Benchmark.
              </div>
            </div>

            {/* FORM ACTIONS */}

            <div className={styles.formActions}>
              <button
                type="button"
                onClick={closeForm}
                disabled={saving || generatingSql}
              >
                Huỷ
              </button>

              <button
                type="submit"
                disabled={
                  saving ||
                  generatingSql ||
                  !questionText.trim() ||
                  !expectedSql.trim()
                }
              >
                {saving
                  ? "⏳ Đang lưu..."
                  : editingId === null
                    ? "✓ Lưu câu hỏi"
                    : "✓ Lưu thay đổi"}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* ======================================================
          EMPTY
      ====================================================== */}

      {!loading && questions.length === 0 && (
        <div className={styles.empty}>
          <div>
            <h2>Chưa có câu hỏi benchmark</h2>

            <p>Không có câu hỏi phù hợp với bộ lọc hiện tại.</p>

            <button
              type="button"
              onClick={openAddForm}
              disabled={connectionId === null}
            >
              + Thêm câu hỏi
            </button>
          </div>
        </div>
      )}

      {/* ======================================================
          QUESTION LIST
      ====================================================== */}

      {questions.length > 0 && (
        <div className={styles.questionList}>
          {questions.map((question, index) => (
            <div key={question.id} className={styles.questionCard}>
              {/* HEADER */}

              <div className={styles.questionHeader}>
                <div className={styles.questionMeta}>
                  <span className={styles.questionNumber}>#{index + 1}</span>

                  <span className={styles.languageBadge}>
                    {question.language === "EN" ? "🇬🇧 EN" : "🇻🇳 VI"}
                  </span>
                </div>

                {/* ACTIONS */}

                <div className={styles.questionActions}>
                  {/* EDIT */}

                  <button
                    type="button"
                    className={styles.editButton}
                    onClick={() => openEditForm(question)}
                    disabled={
                      running || saving || generatingSql || deletingId !== null
                    }
                  >
                    ✎ Sửa
                  </button>

                  {/* DELETE */}

                  <button
                    type="button"
                    onClick={() => deleteQuestion(question.id)}
                    disabled={
                      deletingId === question.id ||
                      running ||
                      saving ||
                      generatingSql
                    }
                  >
                    {deletingId === question.id ? "⏳" : "🗑 Xoá"}
                  </button>
                </div>
              </div>

              {/* QUESTION */}

              <div className={styles.questionText}>{question.questionText}</div>

              {/* EXPECTED SQL */}

              <div className={styles.expectedSql}>
                <div className={styles.expectedSqlHeader}>
                  <strong>SQL mong đợi</strong>

                  <span>Ground truth</span>
                </div>

                <pre>
                  <code>{question.expectedSql}</code>
                </pre>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* ======================================================
          BENCHMARK RESULT
      ====================================================== */}

      {runResult && (
        <div className={styles.resultSection}>
          <div className={styles.resultHeader}>
            <div>
              <h2>
                Kết quả Benchmark
                {languageFilter === "VI"
                  ? " — Tiếng Việt"
                  : languageFilter === "EN"
                    ? " — English"
                    : " — Tất cả"}
              </h2>

              <p>AI được gọi lại độc lập để sinh SQL cho từng câu hỏi.</p>
            </div>
          </div>

          {/* SUMMARY */}

          <div className={styles.resultSummary}>
            <div className={styles.resultStat}>
              <span>Độ chính xác</span>

              <strong>{runResult.accuracy.toFixed(1)}%</strong>
            </div>

            <div className={styles.resultStat}>
              <span>Đúng</span>

              <strong>{runResult.correctCount}</strong>
            </div>

            <div className={styles.resultStat}>
              <span>Tổng số câu</span>

              <strong>{runResult.totalQuestions}</strong>
            </div>
          </div>

          {/* DETAILS */}

          <div className={styles.resultDetails}>
            {runResult.details?.map((detail, index) => (
              <div
                key={index}
                className={
                  detail.correct
                    ? styles.resultItemCorrect
                    : styles.resultItemWrong
                }
              >
                {/* DETAIL HEADER */}

                <div className={styles.resultItemHeader}>
                  <span>#{index + 1}</span>

                  <strong>{detail.correct ? "✓ Đúng" : "✕ Sai"}</strong>

                  <span>{detail.latencyMs} ms</span>
                </div>

                {/* QUESTION */}

                <div className={styles.resultQuestion}>
                  {detail.questionText}
                </div>

                {/* GENERATED SQL */}

                <div className={styles.resultSqlBlock}>
                  <div>
                    <strong>SQL AI sinh ra</strong>
                  </div>

                  <pre>
                    <code>
                      {detail.generatedSql || "AI không tạo được SQL"}
                    </code>
                  </pre>
                </div>

                {/* EXPECTED SQL */}

                <div className={styles.resultSqlBlock}>
                  <div>
                    <strong>SQL mong đợi</strong>
                  </div>

                  <pre>
                    <code>{detail.expectedSql}</code>
                  </pre>
                </div>

                {/* ERROR */}

                {detail.errorMessage && (
                  <div className={styles.resultError}>
                    <strong>Lỗi:</strong> {detail.errorMessage}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
