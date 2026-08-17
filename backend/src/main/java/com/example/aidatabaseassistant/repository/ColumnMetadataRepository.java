package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.ColumnMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ColumnMetadataRepository extends JpaRepository<ColumnMetadata, Long> {
    List<ColumnMetadata> findByTableId(Long tableId);
}