package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.AdminStatsResponse;
import com.example.aidatabaseassistant.dto.AdminUserPageResponse;
import com.example.aidatabaseassistant.dto.AdminUserResponse;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                userRepository.countByRole(Role.USER),
                userRepository.countByRoleAndLockedFalseAndEnabledTrue(Role.USER),
                userRepository.countByRoleAndLockedTrue(Role.USER),
                connectionRepository.countByActiveTrue());
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse getUsers(String search, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> users = userRepository.searchByRole(Role.USER, search == null ? "" : search.trim(), pageable);
        return new AdminUserPageResponse(
                users.getContent().stream().map(this::toResponse).toList(),
                users.getNumber(), users.getSize(), users.getTotalElements(), users.getTotalPages());
    }

    @Transactional
    public AdminUserResponse setLocked(String adminUsername, Long userId, boolean locked) {
        User admin = userRepository.findByUsernameIgnoreCase(adminUsername)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản Admin"));
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));
        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Admin không thể tự khóa tài khoản của mình");
        }
        if (target.getRole() != Role.USER) {
            throw new IllegalArgumentException("Không được thay đổi trạng thái của tài khoản Admin");
        }
        target.setLocked(locked);
        return toResponse(userRepository.save(target));
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole().name(), Boolean.TRUE.equals(user.getEnabled()), Boolean.TRUE.equals(user.getLocked()),
                connectionRepository.countByUserId(user.getId()), user.getCreatedAt());
    }
}
