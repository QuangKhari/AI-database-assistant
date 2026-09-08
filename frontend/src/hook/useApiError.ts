import { useCallback, useState } from "react";
import { useToast } from "../context/ToastContext";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";

interface UseApiErrorOptions {
  suppressUnauthorized?: boolean;
}

export function useApiError(
  fallbackMessage?: string,
  options: UseApiErrorOptions = {},
) {
  const { suppressUnauthorized = true } = options;
  const { showToast } = useToast();
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleError = useCallback(
    (reason: unknown, contextFallback?: string) => {
      const parsed = parseApiError(reason, contextFallback ?? fallbackMessage);

      setFieldErrors(parsed.fieldErrors ?? {});

      if (parsed.isRetryable) {
        showToast(formatErrorWithSupportCode(parsed), "error");
        setError("");
        return parsed;
      }

      if (parsed.status === 401 && suppressUnauthorized) {
        return parsed;
      }

      setError(parsed.message);
      return parsed;
    },
    [fallbackMessage, showToast, suppressUnauthorized],
  );
  const notifyError = useCallback(
    (reason: unknown, contextFallback?: string) => {
      const parsed = parseApiError(reason, contextFallback ?? fallbackMessage);
      showToast(formatErrorWithSupportCode(parsed), "error");
      return parsed;
    },
    [fallbackMessage, showToast],
  );

  const clearError = useCallback(() => {
    setError("");
    setFieldErrors({});
  }, []);

  return {
    error,
    fieldErrors,
    handleError,
    notifyError,
    clearError,
    setError,
  };
}
