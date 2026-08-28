package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatabaseSchemaRepository extends JpaRepository<DatabaseSchema, Long> {
    @EntityGraph(attributePaths = {"tables", "tables.columns"})
    Optional<DatabaseSchema> findByConnectionId(Long connectionId);
}
