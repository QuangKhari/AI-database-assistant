import type { ApiErrorBody } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api";

const TOKEN_KEY = "aidb_access_token";

export class ApiError extends Error {
  status: number;
  code: string;
  fieldErrors?: Record<string, string>;
  correlationId?: string;
  retryAfterSeconds?: number;
  wasAuthenticatedRequest: boolean;

  constructor(
    body: ApiErrorBody,
    retryAfterSeconds?: number,
    wasAuthenticatedRequest = false,
  ) {
    super(body.message);
    this.name = "ApiError";
    this.status = body.status;
    this.code = body.code;
    this.fieldErrors = body.fieldErrors;
    this.correlationId = body.correlationId;
    this.retryAfterSeconds = retryAfterSeconds;
    this.wasAuthenticatedRequest = wasAuthenticatedRequest;
  }
}

function parseRetryAfter(response: Response): number | undefined {
  const header = response.headers.get("Retry-After");
  if (!header) return undefined;
  const seconds = Number(header);
  return Number.isFinite(seconds) ? seconds : undefined;
}

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function storeToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);

  // Khi body là FormData (upload file Excel), KHÔNG set Content-Type.
  // Trình duyệt phải tự thêm multipart/form-data; boundary=...
  const isFormData =
    typeof FormData !== "undefined" && options.body instanceof FormData;

  if (options.body && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const token = getStoredToken();

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (response.status === 401 && token) {
    removeToken();
    window.dispatchEvent(new Event("auth:unauthorized"));
  }

  if (!response.ok) {
    let errorBody: ApiErrorBody;

    try {
      errorBody = (await response.json()) as ApiErrorBody;
    } catch {
      errorBody = {
        status: response.status,
        code: "HTTP_ERROR",
        message: "Không thể xử lý yêu cầu. Vui lòng thử lại.",
      };
    }

    throw new ApiError(errorBody, parseRetryAfter(response), Boolean(token));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

// Dùng cho endpoint trả file nhị phân.
// Ví dụ: POST /query/export/excel
export async function apiRequestBlob(
  path: string,
  options: RequestInit = {},
): Promise<{ blob: Blob; filename: string }> {
  const headers = new Headers(options.headers);

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const token = getStoredToken();

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  if (!response.ok) {
    let errorBody: ApiErrorBody;

    try {
      errorBody = (await response.json()) as ApiErrorBody;
    } catch {
      errorBody = {
        status: response.status,
        code: "HTTP_ERROR",
        message: "Không thể xuất file. Vui lòng thử lại.",
      };
    }

    throw new ApiError(errorBody, parseRetryAfter(response), Boolean(token));
  }

  const disposition = response.headers.get("Content-Disposition") ?? "";

  const match = /filename="?([^"]+)"?/.exec(disposition);

  const filename = match?.[1] ?? "query-result.xlsx";

  const blob = await response.blob();

  return {
    blob,
    filename,
  };
}

/**
 * Một SSE event gồm:
 *
 * event: result
 * data: {...}
 */
export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Gọi API trả về Server-Sent Events (SSE).
 *
 * Không dùng apiRequest() cho endpoint này vì apiRequest()
 * luôn parse response bằng response.json().
 *
 * SSE cần đọc response.body theo từng chunk.
 */
export async function apiRequestSse(
  path: string,
  options: RequestInit = {},
  onEvent: (event: SseEvent) => void,
): Promise<void> {
  const headers = new Headers(options.headers);

  // Request body của execute/stream là JSON.
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  // Báo cho BE rằng FE mong muốn nhận SSE.
  headers.set("Accept", "text/event-stream");

  const token = getStoredToken();

  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  // Giữ behavior authentication giống apiRequest().
  if (response.status === 401 && token) {
    removeToken();
    window.dispatchEvent(new Event("auth:unauthorized"));
  }

  // HTTP error trước khi stream bắt đầu.
  if (!response.ok) {
    let errorBody: ApiErrorBody;

    try {
      errorBody = (await response.json()) as ApiErrorBody;
    } catch {
      errorBody = {
        status: response.status,
        code: "HTTP_ERROR",
        message: "Không thể xử lý yêu cầu. Vui lòng thử lại.",
      };
    }

    throw new ApiError(errorBody, parseRetryAfter(response), Boolean(token));
  }

  // Browser phải cung cấp ReadableStream.
  if (!response.body) {
    throw new ApiError({
      status: response.status,
      code: "STREAM_ERROR",
      message: "Máy chủ không trả về dữ liệu streaming.",
    });
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();

  // Lưu phần dữ liệu chưa tạo thành một SSE event hoàn chỉnh.
  let buffer = "";

  /**
   * Parse một SSE event hoàn chỉnh.
   *
   * Ví dụ:
   *
   * event: STATUS
   * data: {"message":"Đang xử lý"}
   *
   */
  const processEvent = (rawEvent: string) => {
    const lines = rawEvent.split(/\r?\n/);

    let event = "message";

    const dataLines: string[] = [];

    for (const line of lines) {
      if (line.startsWith("event:")) {
        event = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        const value = line.slice(5);

        // SSE cho phép có một khoảng trắng sau "data:".
        dataLines.push(value.startsWith(" ") ? value.slice(1) : value);
      }
    }

    // Event không có data thì bỏ qua.
    if (dataLines.length === 0) {
      return;
    }

    onEvent({
      event,
      data: dataLines.join("\n"),
    });
  };

  /**
   * Đọc stream liên tục.
   */
  while (true) {
    const { value, done } = await reader.read();

    if (done) {
      break;
    }

    // Một chunk network KHÔNG nhất thiết tương ứng
    // với một SSE event hoàn chỉnh.
    buffer += decoder.decode(value, {
      stream: true,
    });

    // SSE event được ngăn cách bằng một dòng trống.
    const events = buffer.split(/\r?\n\r?\n/);

    // Event cuối có thể vẫn chưa hoàn chỉnh.
    buffer = events.pop() ?? "";

    for (const event of events) {
      if (event.trim()) {
        processEvent(event);
      }
    }
  }

  // Flush phần còn lại của TextDecoder.
  buffer += decoder.decode();

  // Nếu stream kết thúc mà event cuối không có
  // "\n\n", vẫn cố gắng xử lý event đó.
  if (buffer.trim()) {
    processEvent(buffer);
  }
}
