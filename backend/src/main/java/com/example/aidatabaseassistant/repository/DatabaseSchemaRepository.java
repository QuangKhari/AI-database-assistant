package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatabaseSchemaRepository extends JpaRepository<DatabaseSchema, Long> {
    Optional<DatabaseSchema> findByConnectionId(Long connectionId);
}