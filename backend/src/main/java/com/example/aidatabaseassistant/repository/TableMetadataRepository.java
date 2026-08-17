package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.TableMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TableMetadataRepository extends JpaRepository<TableMetadata, Long> {
    List<TableMetadata> findBySchemaId(Long schemaId);
}