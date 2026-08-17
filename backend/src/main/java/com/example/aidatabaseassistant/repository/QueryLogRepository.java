package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.QueryLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryLogRepository extends JpaRepository<QueryLog, Long> {
    List<QueryLog> findByMessageIdOrderByAttemptNumberAsc(Long messageId);
}