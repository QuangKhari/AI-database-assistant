package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DatabaseConnectionRepository extends JpaRepository<DatabaseConnection, Long> {
    List<DatabaseConnection> findByUserId(Long userId);
    List<DatabaseConnection> findAllByUserUsernameIgnoreCaseOrderByCreatedAtDesc(String username);
    Optional<DatabaseConnection> findByIdAndUserUsernameIgnoreCase(Long id, String username);
    long countByUserIdAndActiveTrue(Long userId);
    long countByUserUsernameIgnoreCaseAndActiveTrue(String username);
    long countByUserId(Long userId);
    long countByActiveTrue();
}
