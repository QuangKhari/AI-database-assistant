package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.exception.ConflictException;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                e.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(
            ResourceNotFoundException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                e.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(ForbiddenResourceException.class)
    public ResponseEntity<Map<String, Object>> handleForbiddenResource(
            ForbiddenResourceException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                e.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(
            ConflictException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.CONFLICT,
                "CONFLICT",
                e.getMessage(),
                request,
                null
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Sai tên đăng nhập hoặc mật khẩu",
                request,
                null
        );
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> handleLockedAccount(
            LockedException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "Tài khoản đã bị khóa",
                request,
                null
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException e,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null
                            ? fieldError.getDefaultMessage()
                            : "Giá trị không hợp lệ"
            );
        }

        String message = fieldErrors.values()
                .stream()
                .findFirst()
                .orElse("Dữ liệu không hợp lệ");

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                message,
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(
            RateLimitExceededException e,
            HttpServletRequest request) {

        Map<String, Object> body = ErrorResponseFactory.build(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                e.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        HttpHeaders.RETRY_AFTER,
                        String.valueOf(e.getRetryAfterSeconds())
                )
                .body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>>
    handleMissingServletRequestParameter(
            MissingServletRequestParameterException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Thiếu tham số bắt buộc: " + e.getParameterName(),
                request,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>>
    handleMissingServletRequestPart(
            MissingServletRequestPartException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "BAD_REQUEST",
                "Thiếu file bắt buộc: " + e.getRequestPartName(),
                request,
                null
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>>
    handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException e,
            HttpServletRequest request) {

        return buildResponse(
                HttpStatus.CONTENT_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "File tải lên vượt quá dung lượng cho phép (tối đa 25MB)",
                request,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(
            Exception e,
            HttpServletRequest request) {

        log.error(
                "Unexpected server error at {}",
                request.getRequestURI(),
                e
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Đã có lỗi xảy ra. Vui lòng thử lại sau.",
                request,
                null
        );
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors) {

        Map<String, Object> body =
                ErrorResponseFactory.build(
                        status,
                        code,
                        message,
                        request.getRequestURI(),
                        fieldErrors
                );

        return ResponseEntity
                .status(status)
                .body(body);
    }
}