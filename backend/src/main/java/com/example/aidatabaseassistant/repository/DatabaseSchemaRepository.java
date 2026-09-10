package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DatabaseSchemaRepository
        extends JpaRepository<DatabaseSchema, Long> {

    /**
     * Query hiện tại.
     *
     * Giữ nguyên để không ảnh hưởng các chức năng khác
     * đang sử dụng method này.
     */
    @Query("""
            SELECT DISTINCT s
            FROM DatabaseSchema s
            LEFT JOIN FETCH s.tables t
            WHERE s.connection.id = :connectionId
            """)
    Optional<DatabaseSchema> findByConnectionId(
            @Param("connectionId") Long connectionId
    );

    @Query("""
            SELECT DISTINCT s
            FROM DatabaseSchema s
            LEFT JOIN FETCH s.connection
            LEFT JOIN FETCH s.tables
            WHERE s.id = :schemaId
            """)
    Optional<DatabaseSchema> findByIdForRag(
            @Param("schemaId") Long schemaId
    );
}