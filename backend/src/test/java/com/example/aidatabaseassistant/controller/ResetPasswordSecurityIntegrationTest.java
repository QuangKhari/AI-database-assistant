package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.service.PasswordResetTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
class ResetPasswordSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordResetTokenService passwordResetTokenService;

    @MockitoBean
    private com.example.aidatabaseassistant.repository.UserRepository userRepository;

    private User user;

    private PasswordResetToken resetToken;

    @BeforeEach
    void setUp() {

        user =
                User.builder()
                        .id(1L)
                        .username("khai")
                        .email("khai@test.com")
                        .passwordHash("old-password-hash")
                        .build();

        resetToken =
                PasswordResetToken.builder()
                        .id(1L)
                        .user(user)
                        .tokenHash("hashed-token")
                        .build();
    }


    // =========================================================
    // TOKEN HẾT HẠN
    // =========================================================

    @Test
    void resetPassword_shouldRejectExpiredTokenViaHttp()
            throws Exception {

        String rawToken =
                "expired-token";

        resetToken.setExpiresAt(
                java.time.LocalDateTime.now().minusMinutes(1)
        );

        when(
                passwordResetTokenService.validateToken(rawToken)
        ).thenThrow(
                new IllegalArgumentException(
                        "Token đã hết hạn"
                )
        );

        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                    {
                                        "token": "expired-token",
                                        "newPassword": "NewPassword123!"
                                    }
                                    """)
                )
                .andExpect(
                        status().isBadRequest()
                );

        verify(
                passwordResetTokenService
        ).validateToken(rawToken);

        verify(
                passwordResetTokenService,
                never()
        ).consumeToken(
                org.mockito.ArgumentMatchers.any()
        );
    }


    // =========================================================
    // TOKEN ĐÃ SỬ DỤNG
    // =========================================================

    @Test
    void resetPassword_shouldRejectUsedTokenViaHttp()
            throws Exception {

        String rawToken =
                "used-token";

        resetToken.setExpiresAt(
                java.time.LocalDateTime.now().plusMinutes(10)
        );

        resetToken.setUsedAt(
                java.time.LocalDateTime.now().minusMinutes(1)
        );

        when(
                passwordResetTokenService.validateToken(rawToken)
        ).thenThrow(
                new IllegalArgumentException(
                        "Token đã được sử dụng"
                )
        );

        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                    {
                                        "token": "used-token",
                                        "newPassword": "NewPassword123!"
                                    }
                                    """)
                )
                .andExpect(
                        status().isBadRequest()
                );

        verify(
                passwordResetTokenService
        ).validateToken(rawToken);

        verify(
                passwordResetTokenService,
                never()
        ).consumeToken(
                org.mockito.ArgumentMatchers.any()
        );
    }
}