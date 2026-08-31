package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.PasswordResetTokenRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.PasswordResetTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    // =========================================================
    // HTTP
    // =========================================================

    @Autowired
    private MockMvc mockMvc;


    // =========================================================
    // REAL SERVICE
    // =========================================================

    /*
     * PasswordResetTokenService là service thật.
     *
     * Mục tiêu:
     *
     * HTTP
     *   ↓
     * AuthController
     *   ↓
     * AuthService
     *   ↓
     * PasswordResetTokenService THẬT
     *   ↓
     * validateToken()
     *
     * Như vậy test không chỉ kiểm tra unit logic
     * mà kiểm tra flow HTTP thật.
     */
    @Autowired
    private PasswordResetTokenService passwordResetTokenService;


    // =========================================================
    // MOCK REPOSITORIES
    // =========================================================

    @MockitoBean
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    private UserRepository userRepository;


    // =========================================================
    // TEST DATA
    // =========================================================

    private User user;


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUp() {

        user =
                User.builder()
                        .id(1L)
                        .username("khai")
                        .email("khai@test.com")
                        .passwordHash("old-password-hash")
                        .role(Role.USER)
                        .build();


        /*
         * Khi AuthService reset password thành công,
         * userRepository.save(user) phải hoạt động bình thường.
         */
        when(
                userRepository.save(any(User.class))
        ).thenAnswer(
                invocation -> invocation.getArgument(0)
        );


        /*
         * Khi PasswordResetTokenService lưu token,
         * trả lại chính object được truyền vào.
         */
        when(
                passwordResetTokenRepository.save(
                        any(PasswordResetToken.class)
                )
        ).thenAnswer(
                invocation -> invocation.getArgument(0)
        );
    }


    // =========================================================
    // TEST 1
    // TOKEN EXPIRED
    // =========================================================

    @Test
    void resetPassword_shouldRejectExpiredTokenThroughHttp()
            throws Exception {

        // -----------------------------------------------------
        // 1. Tạo token bằng PasswordResetTokenService THẬT
        // -----------------------------------------------------

        String rawToken =
                passwordResetTokenService.createToken(user);


        // -----------------------------------------------------
        // 2. Bắt chính xác PasswordResetToken
        //    mà service vừa save vào repository
        // -----------------------------------------------------

        ArgumentCaptor<PasswordResetToken> captor =
                ArgumentCaptor.forClass(
                        PasswordResetToken.class
                );

        verify(
                passwordResetTokenRepository
        ).save(
                captor.capture()
        );

        PasswordResetToken resetToken =
                captor.getValue();


        // -----------------------------------------------------
        // 3. Kiểm tra token thực sự được tạo
        // -----------------------------------------------------

        assertNotNull(rawToken);

        assertNotNull(
                resetToken.getTokenHash()
        );

        assertNotNull(
                resetToken.getExpiresAt()
        );


        // -----------------------------------------------------
        // 4. Làm token hết hạn
        // -----------------------------------------------------

        resetToken.setExpiresAt(
                LocalDateTime.now().minusMinutes(1)
        );


        // -----------------------------------------------------
        // 5. Khi AuthService validate token,
        //    repository trả lại token đã hết hạn
        // -----------------------------------------------------

        when(
                passwordResetTokenRepository.findByTokenHash(
                        anyString()
                )
        ).thenReturn(
                Optional.of(resetToken)
        );


        // -----------------------------------------------------
        // 6. Gửi HTTP thật
        // -----------------------------------------------------

        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                            "token": "%s",
                                            "newPassword": "NewPassword123!"
                                        }
                                        """.formatted(rawToken))
                )
                .andExpect(
                        status().is4xxClientError()
                );


        // -----------------------------------------------------
        // 7. Password KHÔNG được thay đổi
        // -----------------------------------------------------

        verify(
                userRepository,
                never()
        ).save(any(User.class));


        // -----------------------------------------------------
        // 8. Token cũng không được consume
        // -----------------------------------------------------

        assert resetToken.getUsedAt() == null;
    }


    // =========================================================
    // TEST 2
    // TOKEN REUSE
    // =========================================================

    @Test
    void resetPassword_shouldRejectReusedTokenThroughHttp()
            throws Exception {

        // -----------------------------------------------------
        // 1. Tạo token thật
        // -----------------------------------------------------

        String rawToken =
                passwordResetTokenService.createToken(user);


        // -----------------------------------------------------
        // 2. Bắt token thật đã được save
        // -----------------------------------------------------

        ArgumentCaptor<PasswordResetToken> captor =
                ArgumentCaptor.forClass(
                        PasswordResetToken.class
                );

        verify(
                passwordResetTokenRepository
        ).save(
                captor.capture()
        );

        PasswordResetToken resetToken =
                captor.getValue();


        // -----------------------------------------------------
        // 3. Đảm bảo token còn hạn
        // -----------------------------------------------------

        resetToken.setExpiresAt(
                LocalDateTime.now().plusMinutes(30)
        );


        // -----------------------------------------------------
        // 4. Repository trả token khi validate
        // -----------------------------------------------------

        when(
                passwordResetTokenRepository.findByTokenHash(
                        anyString()
                )
        ).thenReturn(
                Optional.of(resetToken)
        );


        // -----------------------------------------------------
        // 5. LẦN 1
        // Reset password thành công
        // -----------------------------------------------------

        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                            "token": "%s",
                                            "newPassword": "NewPassword123!"
                                        }
                                        """.formatted(rawToken))
                )
                .andExpect(
                        status().isOk()
                );


        // -----------------------------------------------------
        // 6. Xác nhận user đã được save
        // -----------------------------------------------------

        verify(
                userRepository,
                times(1)
        ).save(
                any(User.class)
        );


        // -----------------------------------------------------
        // 7. Xác nhận token đã bị consume
        // -----------------------------------------------------

        assertNotNull(
                resetToken.getUsedAt()
        );


        verify(
                passwordResetTokenRepository,
                atLeastOnce()
        ).save(resetToken);


        // -----------------------------------------------------
        // 8. LẦN 2
        // Sử dụng lại token cũ
        // -----------------------------------------------------

        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                            "token": "%s",
                                            "newPassword": "AnotherPassword123!"
                                        }
                                        """.formatted(rawToken))
                )
                .andExpect(
                        status().is4xxClientError()
                );


        // -----------------------------------------------------
        // 9. User KHÔNG được save lần thứ 2
        // -----------------------------------------------------

        verify(
                userRepository,
                times(1)
        ).save(
                any(User.class)
        );
    }
}