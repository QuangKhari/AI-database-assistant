package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DatabaseSchemaRepository extends JpaRepository<DatabaseSchema, Long> {

    @Query("SELECT DISTINCT s FROM DatabaseSchema s " +
            "LEFT JOIN FETCH s.tables t " +
            "WHERE s.connection.id = :connectionId")
    Optional<DatabaseSchema> findByConnectionId(@Param("connectionId") Long connectionId);
}