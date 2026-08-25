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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil, authenticationManager);
    }

    @Test
    void register_shouldCreateUserAndReturnToken_whenUsernameAndEmailAreFree() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai")).thenReturn(false);
        when(userRepository.existsByEmail("khai@example.com")).thenReturn(false);
        when(passwordEncoder.encode("plainPassword")).thenReturn("hashedPassword");
        when(jwtUtil.generateToken("khai")).thenReturn("fake-jwt-token");

        AuthResponse response = authService.register(request);

        assertEquals("fake-jwt-token", response.getToken());
        assertEquals("khai", response.getUsername());
        assertEquals(Role.USER.name(), response.getRole());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertEquals("khai", savedUser.getUsername());
        assertEquals("khai@example.com", savedUser.getEmail());
        assertEquals("hashedPassword", savedUser.getPasswordHash());
        assertEquals(Role.USER, savedUser.getRole());
    }

    @Test
    void register_shouldThrow_whenUsernameAlreadyExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(request)
        );

        assertTrue(ex.getMessage().contains("Username"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_shouldThrow_whenEmailAlreadyExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("khai");
        request.setEmail("khai@example.com");
        request.setPassword("plainPassword");

        when(userRepository.existsByUsername("khai")).thenReturn(false);
        when(userRepository.existsByEmail("khai@example.com")).thenReturn(true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> authService.register(request)
        );

        assertTrue(ex.getMessage().contains("Email"));
        verify(userRepository, never()).save(any());
    }

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

        when(userRepository.findByUsername("khai")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("khai")).thenReturn("fake-jwt-token");

        AuthResponse response = authService.login(request);

        assertEquals("fake-jwt-token", response.getToken());
        assertEquals("khai", response.getUsername());
        assertEquals(Role.USER.name(), response.getRole());

        verify(authenticationManager).authenticate(any());
    }

    @Test
    void login_shouldPropagateException_whenCredentialsAreInvalid() {
        LoginRequest request = new LoginRequest();
        request.setUsername("khai");
        request.setPassword("wrongPassword");

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any());

        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(request)
        );

        verify(userRepository, never()).findByUsername(any());
        verify(jwtUtil, never()).generateToken(any());
    }

    @Test
    void login_shouldThrow_whenAuthenticatedButUserMissingFromDb() {
        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("plainPassword");

        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> authService.login(request)
        );

        verify(jwtUtil, never()).generateToken(any());
    }
}