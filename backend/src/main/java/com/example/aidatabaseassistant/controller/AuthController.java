package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.security.AuthCookie;
import com.example.aidatabaseassistant.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.example.aidatabaseassistant.service.RateLimitService rateLimitService;
    private final CsrfTokenRepository csrfTokenRepository;

    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    // false o local/docker (chua co HTTPS that). Bat len qua bien moi truong
    // COOKIE_SECURE=true khi deploy that sau 1 domain HTTPS - neu bat khi
    // van con chay http://, browser se AM THAM khong gui cookie nay, khien
    // dang nhap "thanh cong" nhung moi request sau do van bi 401.
    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * Build cookie httpOnly chua JWT.
     *
     * TAI SAO httpOnly:
     * JS phia FE (khien token bi XSS doc trom neu luu o localStorage) se
     * KHONG THE doc duoc cookie nay - trinh duyet tu dong dinh kem vao
     * moi request cung origin, ma khong bao gio expose gia tri qua
     * document.cookie hay bat ky API JS nao.
     */
    private ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from(AuthCookie.ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtExpirationMs / 1000)
                .build();
    }

    private ResponseCookie buildExpiredAccessTokenCookie() {
        return ResponseCookie.from(AuthCookie.ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private void ensureCsrfToken(
            HttpServletRequest request,
            HttpServletResponse response) {

        CsrfToken csrfToken = csrfTokenRepository.loadToken(request);

        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(
                    csrfToken,
                    request,
                    response
            );
        }
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        AuthResponse response = authService.register(request);

        ensureCsrfToken(httpRequest, httpResponse);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        buildAccessTokenCookie(response.getToken()).toString()
                )
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        AuthResponse response = authService.login(request);

        ensureCsrfToken(httpRequest, httpResponse);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        buildAccessTokenCookie(response.getToken()).toString()
                )
                .body(response);
    }

    /**
     * Xoa cookie httpOnly phia server.
     *
     * JWT la stateless nen khong co "session" nao de huy tren server -
     * endpoint nay chi ghi de cookie access_token bang 1 cookie da het han
     * (Max-Age=0), khien trinh duyet xoa no di. permitAll vi ke ca khi
     * cookie da het han/khong hop le, goi logout van phai luon thanh cong
     * (khong co gi de mat).
     */
    @PostMapping("/logout")
    public ResponseEntity<OperationResponse> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildExpiredAccessTokenCookie().toString())
                .body(new OperationResponse("Đã đăng xuất."));
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