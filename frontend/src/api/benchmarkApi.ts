import { apiRequest } from "./client";
import type { BenchmarkQuestion, BenchmarkRunResponse } from "./types";

export const benchmarkApi = {
  questions: (connectionId: number, language?: "VI" | "EN") =>
    apiRequest<BenchmarkQuestion[]>(
      `/benchmark/questions/${connectionId}${
        language ? `?language=${language}` : ""
      }`,
    ),

  generateExpectedSql: (connectionId: number, questionText: string) =>
    apiRequest<{ sql: string }>(
      `/benchmark/questions/${connectionId}/generate-sql`,
      {
        method: "POST",
        body: JSON.stringify({
          questionText,
        }),
      },
    ),

  addQuestion: (
    connectionId: number,
    payload: {
      language: "VI" | "EN";
      questionText: string;
      expectedSql: string;
    },
  ) =>
    apiRequest<BenchmarkQuestion>(`/benchmark/questions/${connectionId}`, {
      method: "POST",
      body: JSON.stringify(payload),
    }),

  updateQuestion: (
    connectionId: number,
    questionId: number,
    payload: {
      language: "VI" | "EN";
      questionText: string;
      expectedSql: string;
    },
  ) =>
    apiRequest<BenchmarkQuestion>(
      `/benchmark/questions/${connectionId}/${questionId}`,
      {
        method: "PUT",
        body: JSON.stringify(payload),
      },
    ),

  deleteQuestion: (connectionId: number, questionId: number) =>
    apiRequest<void>(`/benchmark/questions/${connectionId}/${questionId}`, {
      method: "DELETE",
    }),

  run: (connectionId: number, language?: "VI" | "EN") =>
    apiRequest<BenchmarkRunResponse>(
      `/benchmark/run/${connectionId}${
        language ? `?language=${language}` : ""
      }`,
      {
        method: "POST",
      },
    ),
};
