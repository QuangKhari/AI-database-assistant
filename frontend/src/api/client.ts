import type { ApiErrorBody } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api";

const CSRF_COOKIE_NAME = "XSRF-TOKEN";
const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// Các path không đòi hỏi đăng nhập.
// AuthController + SecurityConfig permitAll "/api/auth/**".
function isPublicAuthPath(path: string): boolean {
  return path.startsWith("/auth/");
}

// Đọc giá trị cookie thường (KHÔNG HttpOnly) từ document.cookie.
// Dùng để lấy CSRF token mà BE cấp qua
// CookieCsrfTokenRepository.withHttpOnlyFalse().
function readCookie(name: string): string | null {
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${name}=`));

  return match ? decodeURIComponent(match.split("=").slice(1).join("=")) : null;
}

// Gắn CSRF token vào header cho các request mutating.
function applyCsrfHeader(headers: Headers, method?: string): void {
  const normalizedMethod = (method ?? "GET").toUpperCase();

  if (!MUTATING_METHODS.has(normalizedMethod)) {
    return;
  }

  const csrfToken = readCookie(CSRF_COOKIE_NAME);

  if (csrfToken) {
    headers.set(CSRF_HEADER_NAME, csrfToken);
  }
}

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

// Chủ động khởi tạo CSRF token trước request mutating.
//
// Nếu browser chưa có XSRF-TOKEN:
//   GET /api/auth/csrf
//        ↓
//   BE tạo XSRF-TOKEN
//        ↓
//   Browser lưu cookie
//
// Sau đó request POST/PUT/PATCH/DELETE sẽ gửi
// X-XSRF-TOKEN ngay từ lần đầu.
async function ensureCsrfToken(): Promise<void> {
  if (readCookie(CSRF_COOKIE_NAME)) {
    return;
  }

  const response = await fetch(`${API_BASE_URL}/auth/csrf`, {
    method: "GET",
    credentials: "include",
  });

  if (!response.ok) {
    throw new ApiError({
      status: response.status,
      code: "CSRF_INIT_ERROR",
      message: "Không thể khởi tạo phiên bảo mật. Vui lòng thử lại.",
    });
  }

  // Không cần parse body vì endpoint /csrf trả 204.
}

// Xác định request có được retry khi gặp CSRF 403 hay không.
//
// Đây chỉ là fallback.
// Flow bình thường hiện tại là ensureCsrfToken() chạy trước.
function shouldRetryOnCsrfFailure(method?: string): boolean {
  const normalizedMethod = (method ?? "GET").toUpperCase();

  return MUTATING_METHODS.has(normalizedMethod);
}

function parseRetryAfter(response: Response): number | undefined {
  const header = response.headers.get("Retry-After");

  if (!header) {
    return undefined;
  }

  const seconds = Number(header);

  return Number.isFinite(seconds) ? seconds : undefined;
}

/**
 * Request API thông thường.
 *
 * Hỗ trợ:
 * - JSON
 * - FormData
 * - credentials include
 * - CSRF bootstrap
 * - CSRF retry fallback
 * - 401 authentication event
 */
export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
  _isCsrfRetry = false,
): Promise<T> {
  const headers = new Headers(options.headers);

  // Khi body là FormData (upload file Excel),
  // KHÔNG set Content-Type.
  //
  // Browser phải tự thêm:
  // multipart/form-data; boundary=...
  const isFormData =
    typeof FormData !== "undefined" && options.body instanceof FormData;

  if (options.body && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const method = (options.method ?? "GET").toUpperCase();

  const authenticatedRequest = !isPublicAuthPath(path);

  // Chủ động tạo XSRF-TOKEN trước request mutating.
  //
  // Không áp dụng cho /auth/** vì các endpoint này là public
  // và SecurityConfig đang ignore CSRF cho /api/auth/**.
  if (
    MUTATING_METHODS.has(method) &&
    authenticatedRequest &&
    !readCookie(CSRF_COOKIE_NAME)
  ) {
    await ensureCsrfToken();
  }

  // Đọc token SAU khi ensureCsrfToken().
  const csrfTokenBeforeRequest = readCookie(CSRF_COOKIE_NAME);

  applyCsrfHeader(headers, method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });

  if (response.status === 401 && authenticatedRequest) {
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

    // Fallback:
    // Nếu vì lý do nào đó token vẫn chưa có trước request,
    // nhưng BE vừa cấp token trong response 403,
    // retry đúng 1 lần.
    const csrfTokenAfterResponse = readCookie(CSRF_COOKIE_NAME);

    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(method) &&
      !csrfTokenBeforeRequest &&
      !!csrfTokenAfterResponse
    ) {
      return apiRequest<T>(path, options, true);
    }

    throw new ApiError(
      errorBody,
      parseRetryAfter(response),
      authenticatedRequest,
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

/**
 * Dùng cho endpoint trả file nhị phân.
 *
 * Ví dụ:
 * POST /query/export/excel
 */
export async function apiRequestBlob(
  path: string,
  options: RequestInit = {},
  _isCsrfRetry = false,
): Promise<{ blob: Blob; filename: string }> {
  const headers = new Headers(options.headers);

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const method = (options.method ?? "GET").toUpperCase();

  const authenticatedRequest = !isPublicAuthPath(path);

  // Chủ động khởi tạo CSRF trước request mutating.
  if (
    MUTATING_METHODS.has(method) &&
    authenticatedRequest &&
    !readCookie(CSRF_COOKIE_NAME)
  ) {
    await ensureCsrfToken();
  }

  const csrfTokenBeforeRequest = readCookie(CSRF_COOKIE_NAME);

  applyCsrfHeader(headers, method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });

  if (response.status === 401 && authenticatedRequest) {
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
        message: "Không thể xuất file. Vui lòng thử lại.",
      };
    }

    const csrfTokenAfterResponse = readCookie(CSRF_COOKIE_NAME);

    // CSRF retry fallback.
    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(method) &&
      !csrfTokenBeforeRequest &&
      !!csrfTokenAfterResponse
    ) {
      return apiRequestBlob(path, options, true);
    }

    throw new ApiError(
      errorBody,
      parseRetryAfter(response),
      authenticatedRequest,
    );
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
  _isCsrfRetry = false,
): Promise<void> {
  const headers = new Headers(options.headers);

  // Request body của execute/stream là JSON.
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  // Báo cho BE rằng FE mong muốn nhận SSE.
  headers.set("Accept", "text/event-stream");

  const method = (options.method ?? "GET").toUpperCase();

  const authenticatedRequest = !isPublicAuthPath(path);

  // Chủ động khởi tạo CSRF trước request mutating.
  if (
    MUTATING_METHODS.has(method) &&
    authenticatedRequest &&
    !readCookie(CSRF_COOKIE_NAME)
  ) {
    await ensureCsrfToken();
  }

  const csrfTokenBeforeRequest = readCookie(CSRF_COOKIE_NAME);

  applyCsrfHeader(headers, method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });

  // Giữ behavior authentication giống apiRequest().
  if (response.status === 401 && authenticatedRequest) {
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

    const csrfTokenAfterResponse = readCookie(CSRF_COOKIE_NAME);

    // CSRF retry fallback.
    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(method) &&
      !csrfTokenBeforeRequest &&
      !!csrfTokenAfterResponse
    ) {
      return apiRequestSse(path, options, onEvent, true);
    }

    throw new ApiError(
      errorBody,
      parseRetryAfter(response),
      authenticatedRequest,
    );
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

  // Lưu phần dữ liệu chưa tạo thành
  // một SSE event hoàn chỉnh.
  let buffer = "";

  /**
   * Parse một SSE event hoàn chỉnh.
   *
   * Ví dụ:
   *
   * event: STATUS
   * data: {"message":"Đang xử lý"}
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

  // Nếu stream kết thúc mà event cuối không có "\n\n",
  // vẫn cố gắng xử lý event đó.
  if (buffer.trim()) {
    processEvent(buffer);
  }
}
