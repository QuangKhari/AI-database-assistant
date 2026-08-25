package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AdminBootstrapInitializer implements ApplicationRunner {

    private final AdminBootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || userRepository.existsByRole(Role.ADMIN)) return;

        String username = require(properties.getUsername(), "ADMIN_BOOTSTRAP_USERNAME");
        String email = require(properties.getEmail(), "ADMIN_BOOTSTRAP_EMAIL").toLowerCase(Locale.ROOT);
        String password = require(properties.getPassword(), "ADMIN_BOOTSTRAP_PASSWORD");
        if (password.length() < 8) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD phải có ít nhất 8 ký tự");
        }
        if (userRepository.existsByUsernameIgnoreCase(username) || userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("Username hoặc email bootstrap Admin đã được tài khoản khác sử dụng");
        }

        userRepository.save(User.builder()
                .username(username)
                .email(email)
                .displayName(properties.getDisplayName() == null || properties.getDisplayName().isBlank()
                        ? "Quản trị viên" : properties.getDisplayName().trim())
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .enabled(true)
                .locked(false)
                .build());
    }

    private String require(String value, String variableName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " là bắt buộc khi bật bootstrap Admin");
        }
        return value.trim();
    }
}
