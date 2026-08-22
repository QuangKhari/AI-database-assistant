package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.DescriptionRequest;
import com.example.aidatabaseassistant.service.SchemaMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaMetadataService schemaMetadataService;

    @PutMapping("/tables/{tableId}")
    public ResponseEntity<Void> updateTableDescription(@PathVariable Long tableId,
                                                       @Valid @RequestBody DescriptionRequest request) {
        schemaMetadataService.updateTableDescription(tableId, request.getDescription());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/columns/{columnId}")
    public ResponseEntity<Void> updateColumnDescription(@PathVariable Long columnId,
                                                        @Valid @RequestBody DescriptionRequest request) {
        schemaMetadataService.updateColumnDescription(columnId, request.getDescription());
        return ResponseEntity.noContent().build();
    }
}