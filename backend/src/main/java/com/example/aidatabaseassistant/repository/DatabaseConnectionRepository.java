package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatabaseConnectionRepository extends JpaRepository<DatabaseConnection, Long> {
    List<DatabaseConnection> findByUserId(Long userId);
}