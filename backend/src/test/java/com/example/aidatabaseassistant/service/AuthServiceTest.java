package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.dto.AuthResponse;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.RegisterRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock AuthenticationManager authenticationManager;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil, authenticationManager);
    }

    @Test
    void registerNormalizesDataAndCreatesSafeUserDefaults() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(" student_01 ");
        request.setDisplayName(" Student One ");
        request.setEmail("STUDENT@EXAMPLE.COM ");
        request.setPassword("Secure123");
        when(passwordEncoder.encode("Secure123")).thenReturn("bcrypt-hash");
        when(jwtUtil.generateToken(any())).thenReturn("signed.jwt.token");
        when(jwtUtil.getExpirationSeconds()).thenReturn(86400L);

        AuthResponse response = authService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getUsername()).isEqualTo("student_01");
        assertThat(saved.getEmail()).isEqualTo("student@example.com");
        assertThat(saved.getDisplayName()).isEqualTo("Student One");
        assertThat(saved.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getLocked()).isFalse();
        assertThat(response.getAccessToken()).isEqualTo("signed.jwt.token");
    }

    @Test
    void registerRejectsExistingEmail() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("student");
        request.setEmail("student@example.com");
        request.setPassword("Secure123");
        when(userRepository.existsByEmailIgnoreCase("student@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email đã tồn tại");
    }

    @Test
    void loginAcceptsEmailOrUsernameIdentifier() {
        LoginRequest request = new LoginRequest();
        request.setIdentifier("student@example.com");
        request.setPassword("Secure123");
        User user = User.builder().id(7L).username("student").email("student@example.com")
                .passwordHash("hash").role(Role.USER).build();
        when(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("student@example.com", "student@example.com"))
                .thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(any())).thenReturn("signed.jwt.token");
        when(jwtUtil.getExpirationSeconds()).thenReturn(86400L);

        AuthResponse response = authService.login(request);

        verify(authenticationManager).authenticate(any());
        assertThat(response.getUser().getUsername()).isEqualTo("student");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
    }
}
