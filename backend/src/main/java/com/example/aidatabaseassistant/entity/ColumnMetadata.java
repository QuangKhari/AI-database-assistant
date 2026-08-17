package com.example.aidatabaseassistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "column_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ColumnMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    private TableMetadata table;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "data_type", nullable = false, length = 50)
    private String dataType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean nullable = true;

    @Column(name = "is_primary_key", nullable = false)
    @Builder.Default
    private Boolean primaryKey = false;

    @Column(name = "is_foreign_key", nullable = false)
    @Builder.Default
    private Boolean foreignKey = false;

    @Column(columnDefinition = "TEXT")
    private String description;
}