package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

    private static final int TOKEN_EXPIRATION_MINUTES = 30;

    private final PasswordResetTokenRepository passwordResetTokenRepository;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Cho phep noi khac (EmailService) hien thi dung thoi han token trong
     * noi dung email, khong phai hardcode lai magic number o 2 noi.
     */
    public int getTokenExpirationMinutes() {
        return TOKEN_EXPIRATION_MINUTES;
    }

    /**
     * Tạo reset token mới cho user.
     *
     * Raw token chỉ được trả về cho caller.
     * Database chỉ lưu SHA-256 hash của token.
     */
    @Transactional
    public String createToken(User user) {

        invalidateExistingTokens(user.getId());

        String rawToken = generateRawToken();

        String tokenHash = hashToken(rawToken);

        LocalDateTime expiresAt =
                LocalDateTime.now()
                        .plusMinutes(TOKEN_EXPIRATION_MINUTES);

        PasswordResetToken resetToken =
                PasswordResetToken.builder()
                        .user(user)
                        .tokenHash(tokenHash)
                        .expiresAt(expiresAt)
                        .build();

        passwordResetTokenRepository.save(resetToken);

        return rawToken;
    }

    /**
     * Tìm token theo raw token mà client gửi lên.
     */
    @Transactional(readOnly = true)
    public PasswordResetToken validateToken(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Token không được để trống"
            );
        }

        String tokenHash = hashToken(rawToken);

        PasswordResetToken resetToken =
                passwordResetTokenRepository
                        .findByTokenHash(tokenHash)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Token không hợp lệ"
                                )
                        );

        if (resetToken.isUsed()) {
            throw new IllegalArgumentException(
                    "Token đã được sử dụng"
            );
        }

        if (resetToken.isExpired()) {
            throw new IllegalArgumentException(
                    "Token đã hết hạn"
            );
        }

        return resetToken;
    }

    /**
     * Đánh dấu token đã được sử dụng.
     */
    @Transactional
    public void consumeToken(PasswordResetToken resetToken) {

        if (resetToken.isUsed()) {
            throw new IllegalArgumentException(
                    "Token đã được sử dụng"
            );
        }

        resetToken.setUsedAt(LocalDateTime.now());

        passwordResetTokenRepository.save(resetToken);
    }

    /**
     * Vô hiệu hóa các token reset password cũ
     * của cùng một user.
     */
    private void invalidateExistingTokens(Long userId) {

        List<PasswordResetToken> existingTokens =
                passwordResetTokenRepository
                        .findByUser_IdAndUsedAtIsNull(userId);

        LocalDateTime now = LocalDateTime.now();

        for (PasswordResetToken token : existingTokens) {
            token.setUsedAt(now);
        }

        if (!existingTokens.isEmpty()) {
            passwordResetTokenRepository.saveAll(existingTokens);
        }
    }

    /**
     * Tạo raw token ngẫu nhiên.
     */
    private String generateRawToken() {

        byte[] randomBytes = new byte[32];

        secureRandom.nextBytes(randomBytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
    }

    /**
     * Hash token bằng SHA-256.
     */
    private String hashToken(String rawToken) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(
                            rawToken.getBytes(StandardCharsets.UTF_8)
                    );

            StringBuilder hexString =
                    new StringBuilder();

            for (byte b : hash) {
                hexString.append(
                        String.format("%02x", b)
                );
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(
                    "Không hỗ trợ SHA-256",
                    e
            );
        }
    }
}