package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TableMetadataRepository extends JpaRepository<TableMetadata, Long> {
    List<TableMetadata> findBySchemaId(Long schemaId);

    Optional<TableMetadata> findByIdAndSchemaConnectionUserUsernameIgnoreCase(Long id, String username);
}
