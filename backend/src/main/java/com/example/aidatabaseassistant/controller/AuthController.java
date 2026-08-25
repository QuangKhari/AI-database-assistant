package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.AuthResponse;
import com.example.aidatabaseassistant.dto.ForgotPasswordRequest;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.OperationResponse;
import com.example.aidatabaseassistant.dto.RegisterRequest;
import com.example.aidatabaseassistant.dto.ResetPasswordRequest;
import com.example.aidatabaseassistant.service.AuthService;
import com.example.aidatabaseassistant.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<OperationResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        passwordResetService.requestReset(request.getEmail(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(new OperationResponse(
                "Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi."
        ));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<OperationResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.ok(new OperationResponse("Đặt lại mật khẩu thành công."));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
