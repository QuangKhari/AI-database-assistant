import type { ApiErrorBody } from "./types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "/api";

const CSRF_COOKIE_NAME = "XSRF-TOKEN";
const CSRF_HEADER_NAME = "X-XSRF-TOKEN";

const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// Cac path KHONG doi hoi dang nhap (xem AuthController.java + SecurityConfig
// permitAll "/api/auth/**") - 1 loi 401 tu day la BINH THUONG (VD: sai mat
// khau luc login), KHONG phai dau hieu phien dang nhap (cookie) het han.
function isPublicAuthPath(path: string): boolean {
  return path.startsWith("/auth/");
}

// Doc gia tri 1 cookie thuong (KHONG httpOnly) tu document.cookie.
// Dung de lay CSRF token ma BE (CookieCsrfTokenRepository.withHttpOnlyFalse())
// co tinh de FE doc duoc va gui lai qua header - day la co che
// "double-submit cookie" tieu chuan cho SPA, khac voi cookie access_token
// (httpOnly, FE khong bao gio doc duoc va cung khong can doc).
function readCookie(name: string): string | null {
  const match = document.cookie
    .split("; ")
    .find((row) => row.startsWith(`${name}=`));

  return match ? decodeURIComponent(match.split("=").slice(1).join("=")) : null;
}

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

// Request MUTATING (POST/PUT/PATCH/DELETE) dau tien trong 1 phien co the bi
// 403 du user da dang nhap hop le: cookie "XSRF-TOKEN" chi duoc BE cap that
// su tren response cua request NAY (xem CsrfCookieFilter ben BE), nen luc
// browser GUI request thi cookie chua ton tai -> FE khong gan duoc header
// X-XSRF-TOKEN -> BE tu choi voi 403 (xem RestAccessDeniedHandler).
//
// Sau response 403 do, cookie XSRF-TOKEN DA co san trong trinh duyet (BE
// van cap no kem theo loi). Nen chi can retry dung 1 LAN voi header CSRF
// moi doc lai - khong can nguoi dung tu bam lai lan 2 nhu truoc.
//
// AN TOAN de retry: request bi chan ngay o tang Spring Security filter,
// CHUA TUNG chay toi controller/service, nen khong co side-effect nao xay
// ra o lan dau (khong insert trung, khong goi Gemini 2 lan...).
function shouldRetryOnCsrfFailure(method?: string): boolean {
  const normalizedMethod = (method ?? "GET").toUpperCase();
  return MUTATING_METHODS.has(normalizedMethod);
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

function parseRetryAfter(response: Response): number | undefined {
  const header = response.headers.get("Retry-After");
  if (!header) return undefined;
  const seconds = Number(header);
  return Number.isFinite(seconds) ? seconds : undefined;
}

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
  _isCsrfRetry = false,
): Promise<T> {
  const headers = new Headers(options.headers);

  // Khi body là FormData (upload file Excel), KHÔNG set Content-Type.
  // Trình duyệt phải tự thêm multipart/form-data; boundary=...
  const isFormData =
    typeof FormData !== "undefined" && options.body instanceof FormData;

  if (options.body && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  applyCsrfHeader(headers, options.method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    // Bat buoc de trinh duyet gui kem cookie access_token/XSRF-TOKEN ke ca
    // khi FE (Vite dev server) va BE khac origin (VD: FE :5173, BE :8081) -
    // mac dinh "same-origin" cua fetch() se KHONG gui cookie trong truong
    // hop khac origin nay.
    credentials: "include",
  });

  const authenticatedRequest = !isPublicAuthPath(path);

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

    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(options.method)
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

// Dùng cho endpoint trả file nhị phân.
// Ví dụ: POST /query/export/excel
export async function apiRequestBlob(
  path: string,
  options: RequestInit = {},
  _isCsrfRetry = false,
): Promise<{ blob: Blob; filename: string }> {
  const headers = new Headers(options.headers);

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  applyCsrfHeader(headers, options.method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });

  const authenticatedRequest = !isPublicAuthPath(path);

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

    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(options.method)
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

  applyCsrfHeader(headers, options.method);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
    credentials: "include",
  });

  const authenticatedRequest = !isPublicAuthPath(path);

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

    if (
      !_isCsrfRetry &&
      response.status === 403 &&
      shouldRetryOnCsrfFailure(options.method)
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
