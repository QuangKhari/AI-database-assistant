package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.dto.AuthResponse;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.RegisterRequest;
import com.example.aidatabaseassistant.entity.PasswordResetToken;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private PasswordResetTokenService passwordResetTokenService;

    @Mock
    private EmailService emailService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                jwtUtil,
                authenticationManager,
                passwordResetTokenService,
                emailService
        );
    }

    // =========================================================
    // REGISTER
    // =========================================================

    @Test
    void register_shouldCreateUserAndReturnToken_whenUsernameAndEmailAreFree() {

        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai"))
                .thenReturn(false);

        when(userRepository.existsByEmail("khai@example.com"))
                .thenReturn(false);

        when(passwordEncoder.encode("plainPassword"))
                .thenReturn("hashedPassword");

        when(jwtUtil.generateToken("khai"))
                .thenReturn("fake-jwt-token");

        AuthResponse response = authService.register(request);

        // Check response
        assertNotNull(response);
        assertEquals("fake-jwt-token", response.getToken());
        assertEquals("khai", response.getUsername());
        assertEquals(Role.USER.name(), response.getRole());

        // Check repository.save()
        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);

        verify(userRepository)
                .save(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertEquals("khai", savedUser.getUsername());
        assertEquals("khai@example.com", savedUser.getEmail());
        assertEquals("hashedPassword", savedUser.getPasswordHash());
        assertEquals(Role.USER, savedUser.getRole());

        // Password must be encoded
        verify(passwordEncoder)
                .encode("plainPassword");

        // JWT must be generated
        verify(jwtUtil)
                .generateToken("khai");
    }

    @Test
    void register_shouldThrow_whenUsernameAlreadyExists() {

        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai"))
                .thenReturn(true);

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> authService.register(request)
                );

        assertTrue(
                ex.getMessage().contains("Username")
        );

        verify(userRepository, never())
                .save(any());

        verify(passwordEncoder, never())
                .encode(any());

        verify(jwtUtil, never())
                .generateToken(any());
    }

    @Test
    void register_shouldThrow_whenEmailAlreadyExists() {

        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai"))
                .thenReturn(false);

        when(userRepository.existsByEmail("khai@example.com"))
                .thenReturn(true);

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> authService.register(request)
                );

        assertTrue(
                ex.getMessage().contains("Email")
        );

        verify(userRepository, never())
                .save(any());

        verify(passwordEncoder, never())
                .encode(any());

        verify(jwtUtil, never())
                .generateToken(any());
    }

    // =========================================================
    // LOGIN
    // =========================================================

    @Test
    void login_shouldReturnToken_whenCredentialsAreValid() {

        LoginRequest request = new LoginRequest();
        request.setUsername("khai");
        request.setPassword("plainPassword");

        User user = User.builder()
                .id(1L)
                .username("khai")
                .email("khai@example.com")
                .passwordHash("hashedPassword")
                .role(Role.USER)
                .build();

        when(userRepository.findByUsername("khai"))
                .thenReturn(Optional.of(user));

        when(jwtUtil.generateToken("khai"))
                .thenReturn("fake-jwt-token");

        AuthResponse response =
                authService.login(request);

        // Check response
        assertNotNull(response);
        assertEquals(
                "fake-jwt-token",
                response.getToken()
        );

        assertEquals(
                "khai",
                response.getUsername()
        );

        assertEquals(
                Role.USER.name(),
                response.getRole()
        );

        // Authentication must happen
        verify(authenticationManager)
                .authenticate(any());

        // User must be loaded
        verify(userRepository)
                .findByUsername("khai");

        // JWT must be generated
        verify(jwtUtil)
                .generateToken("khai");
    }

    @Test
    void login_shouldPropagateException_whenCredentialsAreInvalid() {

        LoginRequest request = new LoginRequest();
        request.setUsername("khai");
        request.setPassword("wrongPassword");

        doThrow(
                new BadCredentialsException("Bad credentials")
        )
                .when(authenticationManager)
                .authenticate(any());

        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(request)
        );

        // Authentication failed,
        // so DB lookup must not happen
        verify(userRepository, never())
                .findByUsername(any());

        // JWT must not be generated
        verify(jwtUtil, never())
                .generateToken(any());
    }

    @Test
    void login_shouldThrow_whenAuthenticatedButUserMissingFromDb() {

        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("plainPassword");

        when(userRepository.findByUsername("ghost"))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        verify(authenticationManager)
                .authenticate(any());

        verify(userRepository)
                .findByUsername("ghost");

        verify(jwtUtil, never())
                .generateToken(any());
    }

    // =========================================================
    // FORGOT PASSWORD
    // =========================================================

    @Test
    void forgotPassword_shouldCreateResetToken_whenEmailExists() {

        User user = User.builder()
                .id(8L)
                .username("lock_test_user")
                .email("lock_test_user@example.com")
                .passwordHash("hashedPassword")
                .role(Role.USER)
                .build();

        when(
                userRepository.findByEmail(
                        "lock_test_user@example.com"
                )
        )
                .thenReturn(Optional.of(user));

        when(
                passwordResetTokenService.createToken(user)
        )
                .thenReturn("fake-reset-token");

        authService.forgotPassword(
                "lock_test_user@example.com"
        );

        verify(userRepository)
                .findByEmail(
                        "lock_test_user@example.com"
                );

        verify(passwordResetTokenService)
                .createToken(user);

        verify(emailService)
                .sendPasswordResetEmail(
                        eq("lock_test_user@example.com"),
                        eq("lock_test_user"),
                        eq("fake-reset-token"),
                        anyInt()
                );
    }

    @Test
    void forgotPassword_shouldNotThrow_whenEmailDoesNotExist_toPreventUserEnumeration() {

        when(userRepository.findByEmail("notfound@example.com"))
                .thenReturn(Optional.empty());

        // Khong duoc nem loi rieng cho truong hop email khong ton tai, neu khong
        // ke tan cong co the do email nao da dang ky (user enumeration).
        assertDoesNotThrow(() -> authService.forgotPassword("notfound@example.com"));

        verify(passwordResetTokenService, never())
                .createToken(any());

        verify(emailService, never())
                .sendPasswordResetEmail(any(), any(), any(), anyInt());
    }

    // =========================================================
    // RESET PASSWORD
    // =========================================================

    @Test
    void resetPassword_shouldUpdatePasswordAndConsumeToken_whenTokenIsValid() {

        User user = User.builder()
                .id(8L)
                .username("lock_test_user")
                .email("lock_test_user@example.com")
                .passwordHash("old-hash")
                .role(Role.USER)
                .build();

        PasswordResetToken resetToken =
                PasswordResetToken.builder()
                        .id(2L)
                        .user(user)
                        .tokenHash("fake-hash")
                        .expiresAt(
                                LocalDateTime.now()
                                        .plusMinutes(20)
                        )
                        .build();

        when(
                passwordResetTokenService.validateToken(
                        "valid-reset-token"
                )
        )
                .thenReturn(resetToken);

        when(
                passwordEncoder.encode(
                        "NewPassword@123"
                )
        )
                .thenReturn("new-hashed-password");

        authService.resetPassword(
                "valid-reset-token",
                "NewPassword@123",
                "NewPassword@123"
        );

        // Password must be changed
        assertEquals(
                "new-hashed-password",
                user.getPasswordHash()
        );

        // Password must be encoded
        verify(passwordEncoder)
                .encode("NewPassword@123");

        // User must be saved
        verify(userRepository)
                .save(user);

        // Reset token must be consumed
        verify(passwordResetTokenService)
                .consumeToken(resetToken);
    }

    @Test
    void resetPassword_shouldNotUpdatePassword_whenTokenIsInvalid() {

        when(
                passwordResetTokenService.validateToken(
                        "invalid-token"
                )
        )
                .thenThrow(
                        new IllegalArgumentException(
                                "Token không hợp lệ"
                        )
                );

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> authService.resetPassword(
                                "invalid-token",
                                "NewPassword@123",
                                "NewPassword@123"
                        )
                );

        assertEquals(
                "Token không hợp lệ",
                ex.getMessage()
        );

        // No password encoding
        verify(passwordEncoder, never())
                .encode(any());

        // No database update
        verify(userRepository, never())
                .save(any());

        // Token must not be consumed
        verify(passwordResetTokenService, never())
                .consumeToken(any());
    }

    @Test
    void resetPassword_shouldRejectUsedToken() {

        User user = User.builder()
                .id(8L)
                .username("lock_test_user")
                .email("lock_test_user@example.com")
                .passwordHash("old-hash")
                .role(Role.USER)
                .build();

        PasswordResetToken resetToken =
                PasswordResetToken.builder()
                        .id(2L)
                        .user(user)
                        .tokenHash("fake-hash")
                        .expiresAt(
                                LocalDateTime.now()
                                        .plusMinutes(20)
                        )
                        .usedAt(
                                LocalDateTime.now()
                        )
                        .build();

        when(
                passwordResetTokenService.validateToken(
                        "used-token"
                )
        )
                .thenThrow(
                        new IllegalArgumentException(
                                "Token đã được sử dụng"
                        )
                );

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> authService.resetPassword(
                                "used-token",
                                "NewPassword@123",
                                "NewPassword@123"
                        )
                );

        assertEquals(
                "Token đã được sử dụng",
                ex.getMessage()
        );

        // Password must not be encoded
        verify(passwordEncoder, never())
                .encode(any());

        // User must not be updated
        verify(userRepository, never())
                .save(any());

        // Token must not be consumed again
        verify(passwordResetTokenService, never())
                .consumeToken(any());
    }
}