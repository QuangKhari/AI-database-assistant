import { apiRequest } from "./client";
import type { BenchmarkQuestion, BenchmarkRunResponse } from "./types";

// Khớp BenchmarkController.java (/api/benchmark):
//   GET  /benchmark/questions/{connectionId}?language=VI|EN
//   POST /benchmark/questions/{connectionId}   body: {language, questionText, expectedSql}
//   POST /benchmark/run/{connectionId}
export const benchmarkApi = {
  questions: (connectionId: number, language?: "VI" | "EN") =>
    apiRequest<BenchmarkQuestion[]>(
      `/benchmark/questions/${connectionId}${
        language ? `?language=${language}` : ""
      }`,
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

  run: (connectionId: number) =>
    apiRequest<BenchmarkRunResponse>(`/benchmark/run/${connectionId}`, {
      method: "POST",
    }),
};
