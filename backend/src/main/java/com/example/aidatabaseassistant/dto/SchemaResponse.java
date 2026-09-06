package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class SchemaResponse {
    // MỚI: id của schema + connectionId - FE cần để hiển thị/điều hướng nhất quán.
    private Long id;
    private Long connectionId;
    private String databaseName;
    private LocalDateTime lastSyncedAt;
    private List<TableInfo> tables;

    @Getter
    @AllArgsConstructor
    public static class TableInfo {
        // MỚI: id thật của bảng trong DB (table_metadata.id) - bắt buộc để FE
        // gọi PUT /api/schema/tables/{tableId} khi sửa mô tả bảng.
        private Long id;
        private String name;
        private String description;
        private List<ColumnInfo> columns;
    }

    @Getter
    @AllArgsConstructor
    public static class ColumnInfo {
        // MỚI: id thật của cột (column_metadata.id) - bắt buộc để FE gọi
        // PUT /api/schema/columns/{columnId} khi sửa mô tả cột.
        private Long id;
        private String name;
        private String dataType;
        // MỚI: FE (badge "NULL") cần field này; entity đã có sẵn, DTO trước đây thiếu.
        private boolean nullable;
        private boolean primaryKey;
        private boolean foreignKey;
        private String referencedTable;
        private String referencedColumn;
        private String description;
    }
}