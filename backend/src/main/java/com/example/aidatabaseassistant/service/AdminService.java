package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.CacheConfig;
import com.example.aidatabaseassistant.dto.AdminConnectionResponse;
import com.example.aidatabaseassistant.dto.AdminStatsResponse;
import com.example.aidatabaseassistant.dto.AdminUserResponse;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.exception.ConflictException;

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
                .map(this::toAdminUserResponse)
                .toList();
    }
    @Transactional(readOnly = true)
    public List<AdminUserResponse> searchUsers(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return getAllUsers();
        }
        String trimmed = keyword.trim();
        return userRepository
                .findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(trimmed, trimmed)
                .stream()
                .map(this::toAdminUserResponse)
                .toList();
    }
    @Transactional
    public AdminUserResponse lockUser(Long userId, String currentAdminUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy user"));

        if (user.getUsername().equals(currentAdminUsername)) {
            throw new ConflictException("Không thể tự khóa tài khoản của chính mình");
        }

        user.setLocked(true);
        userRepository.save(user);
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy user"));

        user.setLocked(false);
        userRepository.save(user);
        return toAdminUserResponse(user);
    }

    private AdminUserResponse toAdminUserResponse(User u) {
        return new AdminUserResponse(
                u.getId(), u.getUsername(), u.getEmail(),
                u.getRole().name(), u.getCreatedAt(),
                u.getDatabaseConnections().size(),
                u.isLocked()
        );
    }

    @Transactional
    public AdminUserResponse updateRole(
            Long userId,
            String roleValue,
            String currentAdminUsername) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy user"));

        if (user.getUsername().equals(currentAdminUsername)) {
            throw new ConflictException(
                    "Không thể thay đổi role của chính mình"
            );
        }

        Role newRole;

        try {
            newRole = Role.valueOf(roleValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Role không hợp lệ: " + roleValue
            );
        }

        user.setRole(newRole);
        userRepository.save(user);

        return toAdminUserResponse(user);
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
    @CacheEvict(cacheNames = CacheConfig.ADMIN_STATS_CACHE, cacheManager = "sharedCacheManager", allEntries = true)
    @Transactional
    public void deleteConnection(Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy connection"));
        connectionRepository.delete(connection);
    }

    /*
     * CACHE: getStats() chay 4 lenh COUNT(*), dashboard hay bi goi lai
     * lien tuc (polling/F5). Cache ngan han 60s la du. cacheManager =
     * "sharedCacheManager": DTO thuan, an toan dung Redis khi scale-out.
     */
    @Cacheable(cacheNames = CacheConfig.ADMIN_STATS_CACHE, cacheManager = "sharedCacheManager")
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