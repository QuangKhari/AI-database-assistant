package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserRepository userRepository;
    @Mock DatabaseConnectionRepository connectionRepository;
    private AdminService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AdminService(userRepository, connectionRepository);
        admin = User.builder().id(1L).username("admin").role(Role.ADMIN).build();
    }

    @Test
    void adminCanLockRegularUser() {
        User target = User.builder().id(2L).username("student").role(Role.USER).locked(false).enabled(true).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);

        var result = service.setLocked("admin", 2L, true);

        assertThat(result.locked()).isTrue();
        verify(userRepository).save(target);
    }

    @Test
    void adminCannotChangeAnotherAdminStatus() {
        User otherAdmin = User.builder().id(3L).username("second-admin").role(Role.ADMIN).build();
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));

        assertThatThrownBy(() -> service.setLocked("admin", 3L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tài khoản Admin");
    }
}
