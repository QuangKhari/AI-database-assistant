package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.example.aidatabaseassistant.service.RateLimitService rateLimitService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<OperationResponse> forgotPassword(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @Valid @RequestBody ForgotPasswordRequest request) {
        if (!rateLimitService.tryConsumeAuthAction(clientIp(httpRequest))) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã yêu cầu quá nhiều lần, vui lòng thử lại sau", 900);
        }

        authService.forgotPassword(request.getEmail());

        return ResponseEntity.ok(
                new OperationResponse("Nếu email tồn tại, liên kết khôi phục mật khẩu đã được gửi.")
        );
    }

    @PostMapping("/reset-password")
    public ResponseEntity<OperationResponse> resetPassword(
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @Valid @RequestBody ResetPasswordRequest request) {
        if (!rateLimitService.tryConsumeAuthAction(clientIp(httpRequest))) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã yêu cầu quá nhiều lần, vui lòng thử lại sau", 900);
        }

        authService.resetPassword(
                request.getToken(), request.getNewPassword(), request.getConfirmPassword());

        return ResponseEntity.ok(new OperationResponse("Đặt lại mật khẩu thành công."));
    }
    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}