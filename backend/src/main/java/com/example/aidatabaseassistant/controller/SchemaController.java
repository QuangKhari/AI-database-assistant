package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.DescriptionRequest;
import com.example.aidatabaseassistant.dto.SchemaResponse;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import com.example.aidatabaseassistant.service.SchemaMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaMetadataService schemaMetadataService;
    private final SchemaDiscoveryService schemaDiscoveryService;

    @GetMapping("/connections/{connectionId}")
    public ResponseEntity<SchemaResponse> getSchema(
            Authentication authentication,
            @PathVariable Long connectionId) {

        DatabaseSchema schema = schemaMetadataService.getSchema(
                authentication.getName(),
                connectionId
        );

        return ResponseEntity.ok(toResponse(schema));
    }

    @PostMapping("/connections/{connectionId}/sync")
    public ResponseEntity<SchemaResponse> syncSchema(
            Authentication authentication,
            @PathVariable Long connectionId) {

        DatabaseSchema schema = schemaDiscoveryService.discoverSchema(
                authentication.getName(),
                connectionId
        );

        return ResponseEntity.ok(toResponse(schema));
    }

    @PutMapping("/tables/{tableId}")
    public ResponseEntity<Void> updateTableDescription(
            Authentication authentication,
            @PathVariable Long tableId,
            @Valid @RequestBody DescriptionRequest request) {

        schemaMetadataService.updateTableDescription(
                authentication.getName(),
                tableId,
                request.getDescription()
        );

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/columns/{columnId}")
    public ResponseEntity<Void> updateColumnDescription(
            Authentication authentication,
            @PathVariable Long columnId,
            @Valid @RequestBody DescriptionRequest request) {

        schemaMetadataService.updateColumnDescription(
                authentication.getName(),
                columnId,
                request.getDescription()
        );

        return ResponseEntity.noContent().build();
    }

    private SchemaResponse toResponse(DatabaseSchema schema) {

        List<SchemaResponse.TableInfo> tables =
                schema.getTables()
                        .stream()
                        .map(this::toTableInfo)
                        .toList();

        return new SchemaResponse(
                schema.getId(),
                schema.getConnection().getId(),
                schema.getConnection().getDatabaseName(),
                schema.getLastSyncedAt(),
                tables
        );
    }

    private SchemaResponse.TableInfo toTableInfo(TableMetadata table) {

        List<SchemaResponse.ColumnInfo> columns =
                table.getColumns()
                        .stream()
                        .map(this::toColumnInfo)
                        .toList();

        return new SchemaResponse.TableInfo(
                table.getId(),
                table.getName(),
                table.getDescription(),
                columns
        );
    }

    private SchemaResponse.ColumnInfo toColumnInfo(ColumnMetadata column) {

        return new SchemaResponse.ColumnInfo(
                column.getId(),
                column.getName(),
                column.getDataType(),
                Boolean.TRUE.equals(column.getNullable()),
                column.getPrimaryKey(),
                column.getForeignKey(),
                column.getReferencedTable(),
                column.getReferencedColumn(),
                column.getDescription()
        );
    }
}