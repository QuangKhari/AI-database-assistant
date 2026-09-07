package com.example.aidatabaseassistant.security;

import com.example.aidatabaseassistant.config.ErrorResponseFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        var body = ErrorResponseFactory.build(
                HttpStatus.UNAUTHORIZED,
                HttpStatus.UNAUTHORIZED.name(),
                "Yêu cầu đăng nhập để truy cập tài nguyên này",
                request.getRequestURI()
        );

        objectMapper.writeValue(response.getWriter(), body);
    }
}