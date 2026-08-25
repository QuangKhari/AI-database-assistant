package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.AuthResponse;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.RegisterRequest;
import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username đã tồn tại");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email đã tồn tại");
        }

        User user = User.builder()
                .username(username)
                .displayName(normalizeDisplayName(request.getDisplayName()))
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .enabled(true)
                .locked(false)
                .build();

        userRepository.save(user);
        return createAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String identifier = request.getIdentifier().trim();
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(identifier, request.getPassword())
        );

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(identifier, identifier)
                .orElseThrow(() -> new IllegalArgumentException("Sai thông tin đăng nhập"));

        return createAuthResponse(user);
    }

    private AuthResponse createAuthResponse(User user) {
        String token = jwtUtil.generateToken(user.getUsername());
        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getRole().name(),
                user.getCreatedAt()
        );
        return new AuthResponse(token, "Bearer", jwtUtil.getExpirationSeconds(), userInfo);
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return displayName.trim();
    }
}
