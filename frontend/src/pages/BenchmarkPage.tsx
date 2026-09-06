import { useCallback, useEffect, useState } from "react";

import { benchmarkApi } from "../api/benchmarkApi";
import { ApiError } from "../api/client";
import { connectionApi } from "../api/connectionApi";
import type {
  BenchmarkQuestion,
  BenchmarkRunResponse,
  DatabaseConnection,
} from "../api/types";
import { useToast } from "../context/ToastContext";

import styles from "./BenchmarkPage.module.css";

type LanguageFilter = "ALL" | "VI" | "EN";

export function BenchmarkPage() {
  const { showToast } = useToast();

  const [connections, setConnections] = useState<DatabaseConnection[]>([]);
  const [connectionId, setConnectionId] = useState<number | null>(null);

  const [questions, setQuestions] = useState<BenchmarkQuestion[]>([]);
  const [languageFilter, setLanguageFilter] = useState<LanguageFilter>("ALL");

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [showForm, setShowForm] = useState(false);
  const [language, setLanguage] = useState<"VI" | "EN">("VI");
  const [questionText, setQuestionText] = useState("");
  const [expectedSql, setExpectedSql] = useState("");
  const [saving, setSaving] = useState(false);

  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState<BenchmarkRunResponse | null>(null);

  // Danh sách connection (chỉ connection đang active mới chạy được benchmark
  // vì cần thực thi SQL thật để so sánh với expectedSql).
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
          reason instanceof ApiError
            ? reason.message
            : "Không tải được connections.",
        );
      } finally {
        setLoading(false);
      }
    }
    void loadConnections();
  }, []);

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
        setError(
          reason instanceof ApiError
            ? reason.message
            : "Không tải được danh sách câu hỏi benchmark.",
        );
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  useEffect(() => {
    setRunResult(null);
    if (connectionId !== null) {
      void loadQuestions(connectionId, languageFilter);
    }
  }, [connectionId, languageFilter, loadQuestions]);

  async function addQuestion(event: React.FormEvent) {
    event.preventDefault();
    if (connectionId === null) return;

    const cleanQuestion = questionText.trim();
    const cleanSql = expectedSql.trim();

    if (!cleanQuestion || !cleanSql) {
      showToast("Vui lòng nhập đủ câu hỏi và SQL mong đợi.", "error");
      return;
    }

    setSaving(true);
    try {
      await benchmarkApi.addQuestion(connectionId, {
        language,
        questionText: cleanQuestion,
        expectedSql: cleanSql,
      });
      setQuestionText("");
      setExpectedSql("");
      setShowForm(false);
      showToast("Đã thêm câu hỏi benchmark.", "success");
      await loadQuestions(connectionId, languageFilter);
    } catch (reason) {
      showToast(
        reason instanceof ApiError
          ? reason.message
          : "Không thể thêm câu hỏi benchmark.",
        "error",
      );
    } finally {
      setSaving(false);
    }
  }

  async function runBenchmark() {
    if (connectionId === null) return;
    if (questions.length === 0) {
      showToast("Chưa có câu hỏi benchmark nào cho connection này.", "error");
      return;
    }

    setRunning(true);
    setRunResult(null);
    try {
      const result = await benchmarkApi.run(connectionId);
      setRunResult(result);
      showToast(
        `Hoàn tất: ${result.correctCount}/${result.totalQuestions} câu đúng.`,
        "success",
      );
    } catch (reason) {
      showToast(
        reason instanceof ApiError
          ? reason.message
          : "Không thể chạy benchmark.",
        "error",
      );
    } finally {
      setRunning(false);
    }
  }

  if (!loading && connections.length === 0) {
    return (
      <section className={styles.empty}>
        <h1>Chưa có connection hoạt động</h1>
        <p>
          Hãy tạo và đồng bộ ít nhất một connection trước khi chạy benchmark
          NL2SQL.
        </p>
      </section>
    );
  }

  return (
    <div className={styles.page}>
      <header className={styles.heading}>
        <div>
          <p>Đánh giá độ chính xác</p>
          <h1>Benchmark NL2SQL</h1>
          <span>
            So sánh SQL do AI sinh ra với SQL mong đợi trên một bộ câu hỏi cố
            định
          </span>
        </div>
        <label>
          Database
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
      </header>

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      <div className={styles.toolbar}>
        <div className={styles.tabs}>
          {(["ALL", "VI", "EN"] as LanguageFilter[]).map((option) => (
            <button
              type="button"
              key={option}
              className={
                languageFilter === option ? styles.tabActive : styles.tab
              }
              onClick={() => setLanguageFilter(option)}
            >
              {option === "ALL" ? "Tất cả" : option}
            </button>
          ))}
        </div>

        <div className={styles.toolbarActions}>
          <button
            type="button"
            onClick={() => setShowForm((current) => !current)}
          >
            {showForm ? "Đóng" : "+ Thêm câu hỏi"}
          </button>
          <button
            type="button"
            className={styles.primary}
            onClick={() => void runBenchmark()}
            disabled={running || questions.length === 0}
          >
            {running
              ? "Đang chạy…"
              : `▶ Chạy Benchmark (${questions.length} câu)`}
          </button>
        </div>
      </div>

      {showForm && (
        <form className={styles.form} onSubmit={addQuestion}>
          <label>
            Ngôn ngữ
            <select
              value={language}
              onChange={(event) =>
                setLanguage(event.target.value as "VI" | "EN")
              }
            >
              <option value="VI">Tiếng Việt</option>
              <option value="EN">English</option>
            </select>
          </label>
          <label>
            Câu hỏi
            <textarea
              value={questionText}
              onChange={(event) => setQuestionText(event.target.value)}
              rows={2}
              placeholder="Ví dụ: Liệt kê 10 khách hàng có tổng đơn hàng cao nhất"
              disabled={saving}
            />
          </label>
          <label>
            SQL mong đợi (expected SQL)
            <textarea
              value={expectedSql}
              onChange={(event) => setExpectedSql(event.target.value)}
              rows={3}
              placeholder="SELECT ..."
              disabled={saving}
              className={styles.mono}
            />
          </label>
          <button type="submit" disabled={saving}>
            {saving ? "Đang lưu…" : "Lưu câu hỏi"}
          </button>
        </form>
      )}

      <section className={styles.panel}>
        <div className={styles.panelHeader}>
          <strong>Bộ câu hỏi benchmark</strong>
          <span>{questions.length} câu</span>
        </div>

        {loading ? (
          <p className={styles.loadingText}>Đang tải…</p>
        ) : questions.length === 0 ? (
          <p className={styles.noResult}>
            Chưa có câu hỏi nào. Nhấn "+ Thêm câu hỏi" để bắt đầu — nên có ít
            nhất 20 câu, bao gồm cả câu hỏi mới không nằm trong few-shot
            examples, để số liệu đánh giá đáng tin cậy.
          </p>
        ) : (
          <ul className={styles.questionList}>
            {questions.map((item) => (
              <li key={item.id}>
                <div className={styles.questionRow}>
                  <span className={styles.badge}>{item.language}</span>
                  <p>{item.questionText}</p>
                </div>
                <pre>
                  <code>{item.expectedSql}</code>
                </pre>
              </li>
            ))}
          </ul>
        )}
      </section>

      {runResult && (
        <section className={styles.panel}>
          <div className={styles.panelHeader}>
            <strong>Kết quả chạy gần nhất</strong>
          </div>

          <div className={styles.stats}>
            <div>
              <span>Độ chính xác</span>
              <strong>{(runResult.accuracy * 100).toFixed(1)}%</strong>
            </div>
            <div>
              <span>Đúng / Tổng</span>
              <strong>
                {runResult.correctCount}/{runResult.totalQuestions}
              </strong>
            </div>
          </div>

          <ul className={styles.detailList}>
            {runResult.details.map((detail, index) => (
              <li
                key={`${detail.questionText}-${index}`}
                className={detail.correct ? styles.detailOk : styles.detailFail}
              >
                <div className={styles.detailHeader}>
                  <span
                    className={
                      detail.correct ? styles.badgeOk : styles.badgeFail
                    }
                  >
                    {detail.correct ? "✓ Đúng" : "✗ Sai"}
                  </span>
                  <p>{detail.questionText}</p>
                  <small>{detail.latencyMs} ms</small>
                </div>

                <div className={styles.sqlCompare}>
                  <div>
                    <span>SQL mong đợi</span>
                    <pre>
                      <code>{detail.expectedSql}</code>
                    </pre>
                  </div>
                  <div>
                    <span>SQL AI sinh ra</span>
                    <pre>
                      <code>{detail.generatedSql ?? "(không có)"}</code>
                    </pre>
                  </div>
                </div>

                {detail.errorMessage && (
                  <p className={styles.detailError}>{detail.errorMessage}</p>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
