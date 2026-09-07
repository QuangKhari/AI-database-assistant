import { ApiError } from "./client";

/**
 * Thong bao mac dinh theo tung status code khi Backend khong tra ve message
 * cu the, hoac khi loi khong phai ApiError (vi du: mat mang, CORS, JSON
 * parse fail...). Dung chung cho toan bo Frontend (Phan 3.6 ke hoach: "Tao
 * mot ham/utility parse API error", "Khong lap lai logic parse error o
 * tung page").
 */
const DEFAULT_MESSAGE_BY_STATUS: Record<number, string> = {
  400: "Yêu cầu không hợp lệ. Vui lòng kiểm tra lại dữ liệu.",
  401: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  403: "Bạn không có quyền thực hiện thao tác này.",
  404: "Không tìm thấy dữ liệu được yêu cầu.",
  409: "Yêu cầu bị xung đột với trạng thái hiện tại. Vui lòng tải lại trang.",
  429: "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ít phút.",
  500: "Đã có lỗi xảy ra phía máy chủ. Vui lòng thử lại sau.",
};

const FALLBACK_MESSAGE = "Không thể kết nối máy chủ. Vui lòng thử lại.";

export interface ParsedApiError {
  message: string;
  status?: number;
  code?: string;
  fieldErrors?: Record<string, string>;
  correlationId?: string;
  retryAfterSeconds?: number;
  isRetryable: boolean;
}
export function parseApiError(
  error: unknown,
  fallbackMessage: string = FALLBACK_MESSAGE,
): ParsedApiError {
  if (error instanceof ApiError) {
    const message =
      error.message ||
      DEFAULT_MESSAGE_BY_STATUS[error.status] ||
      fallbackMessage;

    return {
      message,
      status: error.status,
      code: error.code,
      fieldErrors: error.fieldErrors,
      correlationId: error.correlationId,
      retryAfterSeconds: error.retryAfterSeconds,
      isRetryable: error.status === 429 || error.status >= 500,
    };
  }

  return {
    message: fallbackMessage,
    isRetryable: false,
  };
}

export function getErrorMessage(
  error: unknown,
  fallbackMessage: string = FALLBACK_MESSAGE,
): string {
  return parseApiError(error, fallbackMessage).message;
}

export function formatErrorWithSupportCode(parsed: ParsedApiError): string {
  if (parsed.status === 429 && parsed.retryAfterSeconds) {
    return `${parsed.message} (Thử lại sau ${parsed.retryAfterSeconds} giây.)`;
  }

  if (parsed.status && parsed.status >= 500 && parsed.correlationId) {
    return `${parsed.message} (Mã hỗ trợ: ${parsed.correlationId})`;
  }

  return parsed.message;
}
