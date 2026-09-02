package com.example.aidatabaseassistant.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gán 1 correlation ID (request ID) cho mỗi HTTP request, đưa vào MDC để
 * mọi dòng log trong lúc xử lý request đó (kể cả log bên trong
 * JwtAuthenticationFilter, service, exception handler...) đều tự động in
 * kèm ID này - hữu ích khi nhiều request chạy song song và cần lọc log
 * theo đúng 1 request cụ thể.
 *
 * QUAN TRỌNG VỀ THỨ TỰ: filter này PHẢI chạy TRƯỚC toàn bộ Spring Security
 * filter chain (kể cả JwtAuthenticationFilter), nếu không log bên trong
 * JWT filter sẽ không có correlationId. @Order(HIGHEST_PRECEDENCE) đảm bảo
 * Spring Boot đăng ký filter này sớm nhất trong servlet container, sớm
 * hơn cả DelegatingFilterProxy của Spring Security. Vì vậy KHÔNG cần (và
 * không nên) add filter này vào SecurityConfig.securityFilterChain() -
 * làm vậy sẽ khiến nó chạy 2 lần.
 *
 * Nếu client gửi sẵn header "X-Correlation-Id" (ví dụ frontend/API
 * gateway đã sinh sẵn để trace xuyên nhiều service), filter sẽ dùng lại
 * giá trị đó thay vì tự sinh mới, đồng thời luôn trả lại header này trong
 * response để client/log phía frontend đối chiếu được.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER_NAME);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(MDC_KEY, correlationId);
            response.setHeader(HEADER_NAME, correlationId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}