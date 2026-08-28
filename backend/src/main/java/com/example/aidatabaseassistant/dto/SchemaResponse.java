package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class SchemaResponse {
    private Long id;
    private Long connectionId;
    private String databaseName;
    private LocalDateTime lastSyncedAt;
    private List<TableInfo> tables;

    @Getter
    @AllArgsConstructor
    public static class TableInfo {
        private Long id;
        private String name;
        private String description;
        private List<ColumnInfo> columns;
    }

    @Getter
    @AllArgsConstructor
    public static class ColumnInfo {
        private Long id;
        private String name;
        private String dataType;
        private boolean nullable;
        private boolean primaryKey;
        private boolean foreignKey;
        private String referencedTable;
        private String referencedColumn;
        private String description;
    }
}
