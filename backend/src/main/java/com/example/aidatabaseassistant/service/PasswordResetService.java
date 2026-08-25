package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ResetPasswordRequest;
import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.PasswordResetTokenRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final PasswordResetRateLimiter rateLimiter;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${app.password-reset.expiration-minutes:30}")
    private long expirationMinutes;

    @Transactional
    public void requestReset(String email, String remoteAddress) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        rateLimiter.check(normalizedEmail, remoteAddress);

        userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            tokenRepository.deleteByUserId(user.getId());

            String rawToken = generateToken();
            PasswordResetToken resetToken = PasswordResetToken.builder()
                    .user(user)
                    .tokenHash(hash(rawToken))
                    .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                    .build();
            tokenRepository.save(resetToken);
            sendResetEmail(user, rawToken);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp");
        }

        PasswordResetToken resetToken = tokenRepository.findByTokenHash(hash(request.getToken()))
                .orElseThrow(() -> new IllegalArgumentException("Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn"));

        if (!resetToken.isUsableAt(LocalDateTime.now())) {
            throw new IllegalArgumentException("Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsedAt(LocalDateTime.now());
        tokenRepository.save(resetToken);
        tokenRepository.deleteByUserId(user.getId());
    }

    private void sendResetEmail(User user, String rawToken) {
        String resetUrl = frontendBaseUrl + "/reset-password?token=" + rawToken;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(user.getEmail());
        message.setSubject("Đặt lại mật khẩu AI Database Assistant");
        message.setText("Xin chào " + displayName(user) + ",\n\n"
                + "Mở liên kết sau để đặt lại mật khẩu:\n" + resetUrl + "\n\n"
                + "Liên kết có hiệu lực trong " + expirationMinutes + " phút và chỉ dùng được một lần.\n"
                + "Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email.");
        mailSender.send(message);
    }

    private String displayName(User user) {
        return user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? user.getUsername()
                : user.getDisplayName();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }
}
