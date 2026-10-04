package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.AuthResponse;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.RegisterRequest;
import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final PasswordResetTokenService passwordResetTokenService;
    private final EmailService emailService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username đã tồn tại");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email đã tồn tại");
        }

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();

        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getUsername());
        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("Sai thông tin đăng nhập"));

        String token = jwtUtil.generateToken(user.getUsername());
        return new AuthResponse(token, user.getUsername(), user.getRole().name());
    }

    /**
     * Tao reset-password token cho user ung voi email (neu ton tai).
     *
     * QUAN TRONG: KHONG nem exception khi email khong ton tai. Neu nem loi rieng
     * cho truong hop "email khong ton tai" (khac voi truong hop thanh cong), ke
     * tan cong co the do tung email de biet email nao da dang ky trong he thong
     * (user enumeration) - day la loi bao mat pho bien trong OWASP ASVS. Vi vay
     * ca hai truong hop (email ton tai / khong ton tai) deu tra ve cung mot ket
     * qua thanh cong tu controller.
     */
    public void forgotPassword(String email) {

        userRepository.findByEmail(email).ifPresentOrElse(
                user -> {
                    String rawToken = passwordResetTokenService.createToken(user);
                    log.debug("Password reset token cho user={}: {}", user.getUsername(), rawToken);

                    // EmailService.sendPasswordResetEmail() tu nuot moi loi SMTP
                    // ben trong no (xem Javadoc cua method do) - o day khong can,
                    // va cung KHONG duoc, boc them try/catch nao khac lam thay doi
                    // hanh vi thanh cong/that bai cua nhanh nay.
                    emailService.sendPasswordResetEmail(
                            user.getEmail(),
                            user.getUsername(),
                            rawToken,
                            passwordResetTokenService.getTokenExpirationMinutes()
                    );
                },
                () -> log.debug("Yêu cầu forgot-password cho email không tồn tại: {}", email)
        );
    }

    @Transactional
    public void resetPassword(
            String rawToken,
            String newPassword,
            String confirmPassword
    ) {

        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException(
                    "Mật khẩu xác nhận không khớp"
            );
        }

        PasswordResetToken resetToken =
                passwordResetTokenService.validateToken(rawToken);

        User user = resetToken.getUser();

        String newPasswordHash =
                passwordEncoder.encode(newPassword);

        user.setPasswordHash(newPasswordHash);

        userRepository.save(user);

        passwordResetTokenService.consumeToken(resetToken);
    }
}