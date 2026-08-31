package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.AdminConnectionResponse;
import com.example.aidatabaseassistant.dto.AdminStatsResponse;
import com.example.aidatabaseassistant.dto.AdminUserResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.QueryLogRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private DatabaseConnectionRepository connectionRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private QueryLogRepository queryLogRepository;

    private AdminService adminService;

    private User normalUser;

    @BeforeEach
    void setUp() {

        adminService = new AdminService(
                userRepository,
                connectionRepository,
                conversationRepository,
                queryLogRepository
        );

        normalUser = User.builder()
                .id(2L)
                .username("khai")
                .email("khai@example.com")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .databaseConnections(List.of(
                        DatabaseConnection.builder().id(1L).build(),
                        DatabaseConnection.builder().id(2L).build()
                ))
                .build();
    }

    // ===== getAllUsers =====

    @Test
    void getAllUsers_shouldMapUsersWithConnectionCount() {

        when(userRepository.findAll()).thenReturn(List.of(normalUser));

        List<AdminUserResponse> result = adminService.getAllUsers();

        assertEquals(1, result.size());
        assertEquals("khai", result.get(0).getUsername());
        assertEquals("USER", result.get(0).getRole());
        assertEquals(2, result.get(0).getConnectionCount());
    }

    // ===== updateRole =====

    @Test
    void updateRole_shouldPromoteUserToAdmin() {

        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        AdminUserResponse response = adminService.updateRole(2L, "admin");

        assertEquals("ADMIN", response.getRole());
        assertEquals(Role.ADMIN, normalUser.getRole());
        verify(userRepository).save(normalUser);
    }

    @Test
    void updateRole_shouldDemoteAdminToUser() {

        normalUser.setRole(Role.ADMIN);

        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        AdminUserResponse response = adminService.updateRole(2L, "USER");

        assertEquals("USER", response.getRole());
        assertEquals(Role.USER, normalUser.getRole());
    }

    @Test
    void updateRole_shouldThrow_whenRoleValueInvalid() {

        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.updateRole(2L, "SUPERUSER")
        );

        assertTrue(ex.getMessage().contains("Role không hợp lệ"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateRole_shouldThrow_whenUserNotFound() {

        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> adminService.updateRole(999L, "ADMIN")
        );
    }

    // ===== searchUsers =====

    @Test
    void searchUsers_shouldReturnAllUsers_whenKeywordBlank() {

        when(userRepository.findAll()).thenReturn(List.of(normalUser));

        List<AdminUserResponse> result = adminService.searchUsers("   ");

        assertEquals(1, result.size());
        verify(userRepository, never())
                .findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(any(), any());
    }

    @Test
    void searchUsers_shouldDelegateToRepository_whenKeywordProvided() {

        when(userRepository.findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase("khai", "khai"))
                .thenReturn(List.of(normalUser));

        List<AdminUserResponse> result = adminService.searchUsers("khai");

        assertEquals(1, result.size());
        assertEquals("khai", result.get(0).getUsername());
    }

    // ===== lockUser / unlockUser =====

    @Test
    void lockUser_shouldSetLockedTrue() {

        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        AdminUserResponse response = adminService.lockUser(2L, "admin-account");

        assertTrue(response.isLocked());
        assertTrue(normalUser.isLocked());
        verify(userRepository).save(normalUser);
    }

    @Test
    void lockUser_shouldThrow_whenAdminTriesToLockSelf() {

        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> adminService.lockUser(2L, "khai")
        );

        assertTrue(ex.getMessage().contains("tự khóa"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void unlockUser_shouldSetLockedFalse() {

        normalUser.setLocked(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(normalUser));

        AdminUserResponse response = adminService.unlockUser(2L);

        assertFalse(response.isLocked());
        assertFalse(normalUser.isLocked());
        verify(userRepository).save(normalUser);
    }

    // ===== getAllConnections =====

    @Test
    void getAllConnections_shouldMapOwnerUsername() {

        DatabaseConnection connection = DatabaseConnection.builder()
                .id(10L)
                .user(normalUser)
                .name("Sample DB")
                .dbType("mysql")
                .host("127.0.0.1")
                .databaseName("shop")
                .createdAt(LocalDateTime.now())
                .build();

        when(connectionRepository.findAllWithUser()).thenReturn(List.of(connection));

        List<AdminConnectionResponse> result = adminService.getAllConnections();

        assertEquals(1, result.size());
        assertEquals("khai", result.get(0).getOwnerUsername());
        assertEquals("Sample DB", result.get(0).getName());
    }

    // ===== deleteConnection =====

    @Test
    void deleteConnection_shouldDelete_whenExists() {

        DatabaseConnection connection = DatabaseConnection.builder().id(10L).build();

        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        adminService.deleteConnection(10L);

        verify(connectionRepository).delete(connection);
    }

    @Test
    void deleteConnection_shouldThrow_whenNotFound() {

        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> adminService.deleteConnection(999L)
        );

        verify(connectionRepository, never()).delete(any());
    }

    // ===== getStats =====

    @Test
    void getStats_shouldAggregateCountsFromAllRepositories() {

        when(userRepository.count()).thenReturn(5L);
        when(connectionRepository.count()).thenReturn(13L);
        when(conversationRepository.count()).thenReturn(155L);
        when(queryLogRepository.count()).thenReturn(119L);

        AdminStatsResponse stats = adminService.getStats();

        assertEquals(5L, stats.getTotalUsers());
        assertEquals(13L, stats.getTotalConnections());
        assertEquals(155L, stats.getTotalConversations());
        assertEquals(119L, stats.getTotalQueries());
    }
}