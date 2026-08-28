package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.TableEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TableEmbeddingRepository extends JpaRepository<TableEmbedding, Long> {

    Optional<TableEmbedding> findBySchemaIdAndTableNameIgnoreCase(Long schemaId, String tableName);

    // Dùng khi retrieval cần load toàn bộ embedding của 1 schema 1 lần
    // (tránh N+1 query khi tính cosine similarity cho từng bảng).
    List<TableEmbedding> findBySchemaId(Long schemaId);
}