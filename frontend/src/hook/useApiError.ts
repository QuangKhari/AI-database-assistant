import { useCallback, useState } from "react";
import { useToast } from "../context/ToastContext";
import { formatErrorWithSupportCode, parseApiError } from "../api/errorUtils";

/**
 * Hook dung chung cho toan bo page co pattern:
 *   const [error, setError] = useState("")
 *   catch (reason) { setError(reason instanceof ApiError ? reason.message : "...") }
 *
 * Vua set state "error" (hien inline tren form/page, dung cho 400/404/403/
 * 409) vua hien Toast (dung cho 429/500 - loi thoang qua, khong gan voi 1
 * o input cu the) - dung Toast co san (context/ToastContext.tsx), khong
 * tao co che thong bao moi (Phan 3.6: "Khong lap lai logic parse error o
 * tung page").
 */
export function useApiError(fallbackMessage?: string) {
  const { showToast } = useToast();
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleError = useCallback(
    (reason: unknown, contextFallback?: string) => {
      const parsed = parseApiError(reason, contextFallback ?? fallbackMessage);

      setFieldErrors(parsed.fieldErrors ?? {});

      // 429/500+: loi tam thoi, khong phai loi FORM -> Toast + khong giu
      // banner error co dinh tren page (tranh gay hieu lam "du lieu sai").
      if (parsed.isRetryable) {
        showToast(formatErrorWithSupportCode(parsed), "error");
        setError("");
        return parsed;
      }

      // 401 da duoc xu ly global qua "auth:unauthorized" (AuthContext) nen
      // khong can lam gi them o day - tranh hien 2 thong bao chong nhau.
      if (parsed.status === 401) {
        return parsed;
      }

      setError(parsed.message);
      return parsed;
    },
    [fallbackMessage, showToast],
  );

  const clearError = useCallback(() => {
    setError("");
    setFieldErrors({});
  }, []);

  return { error, fieldErrors, handleError, clearError, setError };
}
