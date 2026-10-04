package com.example.aidatabaseassistant.security;

import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Guard DUY NHAT kiem tra "connection nay co ton tai va co thuoc ve user
 * dang goi request khong"
 *
 * connectionId luon la du lieu do client/Frontend gui len (path variable) -
 * KHONG duoc tin tuong, phai luon di qua guard nay truoc khi dung.
 */
@Component
@RequiredArgsConstructor
public class ConnectionAccessGuard {

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;

    public User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy user"));
    }

    public DatabaseConnection requireOwnedConnection(String username, Long connectionId) {
        User user = requireUser(username);
        return requireOwnedConnection(user, connectionId);
    }

    public DatabaseConnection requireOwnedConnection(User user, Long connectionId) {
        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy connection"));

        requireOwnership(user, connection);
        return connection;
    }

    public void requireOwnership(User user, DatabaseConnection connection) {
        if (!connection.getUser().getId().equals(user.getId())) {
            throw new ForbiddenResourceException("Bạn không có quyền truy cập connection này");
        }
    }
}