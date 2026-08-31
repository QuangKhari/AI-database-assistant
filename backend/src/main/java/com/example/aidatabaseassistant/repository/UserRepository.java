package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    // Dùng cho Admin search: tìm theo username HOẶC email, không phân biệt hoa/thường.
    List<User> findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String email);
}