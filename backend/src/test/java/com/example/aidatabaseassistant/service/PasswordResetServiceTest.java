package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ResetPasswordRequest;
import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.PasswordResetTokenRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JavaMailSender mailSender;
    @Mock PasswordResetRateLimiter rateLimiter;
    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository, passwordEncoder, mailSender, rateLimiter);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(service, "expirationMinutes", 30L);
        user = User.builder().id(1L).username("student").email("student@example.com")
                .passwordHash("old-hash").role(Role.USER).build();
    }

    @Test
    void requestResetStoresHashButEmailsRawToken() {
        when(userRepository.findByEmailIgnoreCase("student@example.com")).thenReturn(Optional.of(user));

        service.requestReset("STUDENT@EXAMPLE.COM", "127.0.0.1");

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        String storedHash = tokenCaptor.getValue().getTokenHash();
        String body = messageCaptor.getValue().getText();
        assertThat(storedHash).hasSize(64);
        assertThat(body).contains("http://localhost:5173/reset-password?token=");
        assertThat(body).doesNotContain(storedHash);
    }

    @Test
    void unknownEmailDoesNotRevealAccountOrSendMail() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        service.requestReset("missing@example.com", "127.0.0.1");

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void resetRejectsExpiredToken() {
        ResetPasswordRequest request = resetRequest("raw-token", "NewSecure123", "NewSecure123");
        PasswordResetToken expired = PasswordResetToken.builder().user(user)
                .tokenHash("hash").expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.resetPassword(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không hợp lệ hoặc đã hết hạn");
    }

    private ResetPasswordRequest resetRequest(String token, String password, String confirmation) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken(token);
        request.setNewPassword(password);
        request.setConfirmPassword(confirmation);
        return request;
    }
}
