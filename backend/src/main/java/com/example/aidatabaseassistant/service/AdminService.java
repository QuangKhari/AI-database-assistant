package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.AdminConnectionResponse;
import com.example.aidatabaseassistant.dto.AdminStatsResponse;
import com.example.aidatabaseassistant.dto.AdminUserResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final ConversationRepository conversationRepository;
    private final QueryLogRepository queryLogRepository;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(u -> new AdminUserResponse(
                        u.getId(), u.getUsername(), u.getEmail(),
                        u.getRole().name(), u.getCreatedAt(),
                        u.getDatabaseConnections().size()
                ))
                .toList();
    }

    @Transactional
    public AdminUserResponse updateRole(Long userId, String roleValue) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        Role newRole;
        try {
            newRole = Role.valueOf(roleValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Role không hợp lệ: " + roleValue);
        }

        user.setRole(newRole);
        userRepository.save(user);

        return new AdminUserResponse(
                user.getId(), user.getUsername(), user.getEmail(),
                user.getRole().name(), user.getCreatedAt(),
                user.getDatabaseConnections().size()
        );
    }

    @Transactional(readOnly = true)
    public List<AdminConnectionResponse> getAllConnections() {
        return connectionRepository.findAllWithUser().stream()
                .map(c -> new AdminConnectionResponse(
                        c.getId(), c.getName(), c.getDbType(), c.getHost(),
                        c.getDatabaseName(), c.getUser().getUsername(), c.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public void deleteConnection(Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));
        connectionRepository.delete(connection);
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                userRepository.count(),
                connectionRepository.count(),
                conversationRepository.count(),
                queryLogRepository.count()
        );
    }
}