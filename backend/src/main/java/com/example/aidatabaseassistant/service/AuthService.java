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

    public void forgotPassword(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Email không tồn tại"
                        )
                );

        String rawToken =
                passwordResetTokenService.createToken(user);

        // TODO(backend): chua co email service that su - day la stub tam thoi
        // de demo/test luong reset password khi chua tich hop SMTP/email
        // provider. Log o muc INFO (khong phai print ra console qua
        // System.out) de nhat quan voi phan con lai cua codebase da chuyen
        // sang SLF4J, va de co the tat log nay trong production bang cach
        // ha logging.level xuong WARN neu can.
        log.info("Password reset token generated for user='{}' email='{}' token='{}'",
                user.getUsername(), user.getEmail(), rawToken);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {

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