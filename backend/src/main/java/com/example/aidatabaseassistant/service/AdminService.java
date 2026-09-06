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

    /**
     * Tim user theo username hoac email (khong phan biet hoa/thuong).
     * Keyword rong/null -> tra ve toan bo danh sach, giu hanh vi tuong tu
     * getAllUsers() de Frontend khong can xu ly rieng truong hop rong.
     */
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

    /**
     * Khoa mot user - tu day tro di user nay khong the dang nhap nua
     * (CustomUserDetailsService -> accountLocked -> Spring Security tu chan
     * bang LockedException -> 401 "Tài khoản đã bị khóa").
     *
     * Chan admin tu khoa chinh minh: neu khong co guard nay, mot admin duy
     * nhat co the vo tinh (hoac bi lua) tu khoa tai khoan cua minh va mat
     * toan bo quyen truy cap Admin API, khong con cach nao mo lai tru khi
     * sua thang trong DB.
     */
    @Transactional
    public AdminUserResponse lockUser(Long userId, String currentAdminUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        if (user.getUsername().equals(currentAdminUsername)) {
            throw new IllegalArgumentException("Không thể tự khóa tài khoản của chính mình");
        }

        user.setLocked(true);
        userRepository.save(user);
        return toAdminUserResponse(user);
    }

    @Transactional
    public AdminUserResponse unlockUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

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
                .orElseThrow(() ->
                        new IllegalArgumentException("Không tìm thấy user"));

        if (user.getUsername().equals(currentAdminUsername)) {
            throw new IllegalArgumentException(
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

    /*
     * Xoa connection lam thay doi so lieu "totalConnections" (va gian
     * tiep totalConversations/totalQueries do cascade delete). Xoa cache
     * adminStats ngay de dashboard khong hien so lieu cu toi 60 giay.
     * allEntries=true vi cache nay chi co DUY NHAT 1 gia tri (khong co
     * key theo tham so).
     */
    @CacheEvict(cacheNames = CacheConfig.ADMIN_STATS_CACHE, cacheManager = "sharedCacheManager", allEntries = true)
    @Transactional
    public void deleteConnection(Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));
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