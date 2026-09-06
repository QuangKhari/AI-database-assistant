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
            @Valid @RequestBody ForgotPasswordRequest request) {

        authService.forgotPassword(request.getEmail());

        return ResponseEntity.ok(
                new OperationResponse(
                        "Nếu email tồn tại, liên kết khôi phục mật khẩu đã được gửi."
                )
        );
    }

    @PostMapping("/reset-password")
    public ResponseEntity<OperationResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {

        authService.resetPassword(
                request.getToken(),
                request.getNewPassword(),
                request.getConfirmPassword()
        );

        return ResponseEntity.ok(
                new OperationResponse(
                        "Đặt lại mật khẩu thành công."
                )
        );
    }
}