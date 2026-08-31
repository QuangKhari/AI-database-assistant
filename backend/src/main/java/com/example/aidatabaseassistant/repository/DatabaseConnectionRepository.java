package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DatabaseConnectionRepository extends JpaRepository<DatabaseConnection, Long> {
    List<DatabaseConnection> findByUserId(Long userId);

    long countByUserId(Long userId);

    @Query("SELECT c FROM DatabaseConnection c JOIN FETCH c.user")
    List<DatabaseConnection> findAllWithUser();
}