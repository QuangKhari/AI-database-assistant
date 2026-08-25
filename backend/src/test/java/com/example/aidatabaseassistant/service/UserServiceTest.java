package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ChangePasswordRequest;
import com.example.aidatabaseassistant.dto.UpdateProfileRequest;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
        user = User.builder().id(1L).username("student").email("old@example.com")
                .passwordHash("old-hash").role(Role.USER).build();
        when(userRepository.findByUsernameIgnoreCase("student")).thenReturn(Optional.of(user));
    }

    @Test
    void updateProfileNormalizesEmailAndDisplayName() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setDisplayName(" Student Name ");
        request.setEmail("NEW@EXAMPLE.COM ");
        when(userRepository.save(user)).thenReturn(user);

        var response = userService.updateProfile("student", request);

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.displayName()).isEqualTo("Student Name");
    }

    @Test
    void changePasswordRequiresCurrentPassword() {
        ChangePasswordRequest request = passwordRequest("wrong", "NewSecure123", "NewSecure123");
        when(passwordEncoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword("student", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mật khẩu hiện tại không đúng");
    }

    @Test
    void changePasswordStoresOnlyEncodedPassword() {
        ChangePasswordRequest request = passwordRequest("Secure123", "NewSecure123", "NewSecure123");
        when(passwordEncoder.matches("Secure123", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewSecure123", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewSecure123")).thenReturn("new-hash");

        userService.changePassword("student", request);

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
    }

    private ChangePasswordRequest passwordRequest(String current, String next, String confirm) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(current);
        request.setNewPassword(next);
        request.setConfirmPassword(confirm);
        return request;
    }
}
