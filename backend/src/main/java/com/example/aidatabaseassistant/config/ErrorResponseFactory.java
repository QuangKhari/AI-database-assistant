package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.security.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorResponseFactory {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ErrorResponseFactory() {
    }

    public static Map<String, Object> build(
            HttpStatus status,
            String code,
            String message,
            String path,
            Map<String, String> fieldErrors) {

        Map<String, Object> body = new LinkedHashMap<>();

        // Serialize timestamp thành String để response có thể được
        // xử lý bởi cả Spring Boot ObjectMapper và ObjectMapper mặc định
        // trong integration/unit tests.
        body.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", path);

        // Trùng với CorrelationIdFilter.MDC_KEY - filter này chạy TRƯỚC
        // cả Spring Security nên MDC luôn có giá trị, kể cả khi lỗi xảy ra
        // ngay trong AuthenticationEntryPoint/AccessDeniedHandler.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }

        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("fieldErrors", fieldErrors);
        }

        return body;
    }

    public static Map<String, Object> build(
            HttpStatus status,
            String code,
            String message,
            String path) {

        return build(
                status,
                code,
                message,
                path,
                null
        );
    }
}