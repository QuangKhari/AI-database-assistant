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
 * dang goi request khong" (Phan 5.2 ke hoach: Authentication -> Resource
 * Ownership -> QueryExecutionGuard -> SQL Validator -> ...).
 *
 * Truoc day 9 service tu viet lai giong het 3 buoc nay (tim user -> tim
 * connection -> so sanh chu so huu), va TAT CA deu nem IllegalArgumentException
 * (400) cho ca truong hop "khong tim thay" (dang le 404) LAN "khong co quyen"
 * (dang le 403). Gop ve 1 noi + dung dung exception type de
 * GlobalExceptionHandler tra ve dung ma HTTP (Phan 3).
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