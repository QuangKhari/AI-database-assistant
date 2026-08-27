package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TableMetadataRepository extends JpaRepository<TableMetadata, Long> {

    List<TableMetadata> findBySchemaId(Long schemaId);

    @Query("SELECT DISTINCT t FROM TableMetadata t " +
            "LEFT JOIN FETCH t.columns " +
            "WHERE t.schema.id = :schemaId")
    List<TableMetadata> findBySchemaIdWithColumns(@Param("schemaId") Long schemaId);
}