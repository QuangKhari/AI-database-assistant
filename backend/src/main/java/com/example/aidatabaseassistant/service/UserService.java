package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ChangePasswordRequest;
import com.example.aidatabaseassistant.dto.UpdateProfileRequest;
import com.example.aidatabaseassistant.dto.UserProfileResponse;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String username) {
        return toResponse(findUser(username));
    }

    @Transactional
    public UserProfileResponse updateProfile(String username, UpdateProfileRequest request) {
        User user = findUser(username);
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        userRepository.findByEmailIgnoreCase(email)
                .filter(existing -> !existing.getId().equals(user.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Email đã được sử dụng");
                });

        user.setEmail(email);
        user.setDisplayName(normalizeDisplayName(request.getDisplayName()));
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void changePassword(String username, ChangePasswordRequest request) {
        User user = findUser(username);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu hiện tại không đúng");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    private User findUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản"));
    }

    private UserProfileResponse toResponse(User user) {
        return new UserProfileResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole().name(), Boolean.TRUE.equals(user.getEnabled()),
                Boolean.TRUE.equals(user.getLocked()), user.getCreatedAt(), user.getUpdatedAt()
        );
    }

    private String normalizeDisplayName(String displayName) {
        return displayName == null || displayName.isBlank() ? null : displayName.trim();
    }
}
