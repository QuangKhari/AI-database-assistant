package com.example.aidatabaseassistant.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException e) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Email/tên đăng nhập hoặc mật khẩu không đúng.", null);
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> handleLocked(LockedException e) {
        return buildResponse(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED",
                "Tài khoản đã bị khóa.", null);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, Object>> handleDisabled(DisabledException e) {
        return buildResponse(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED",
                "Tài khoản hiện không hoạt động.", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Không thể xác thực tài khoản.", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return buildResponse(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Bạn không có quyền thực hiện thao tác này.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "Không hợp lệ" : error.getDefaultMessage(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Dữ liệu gửi lên không hợp lệ.", fieldErrors);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException e) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", e.getMessage(), null);
    }

    @ExceptionHandler(TargetDatabaseConnectionException.class)
    public ResponseEntity<Map<String, Object>> handleTargetDatabaseConnection(
            TargetDatabaseConnectionException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Hệ thống đang gặp sự cố. Vui lòng thử lại sau.", null);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status).body(body);
    }
}
