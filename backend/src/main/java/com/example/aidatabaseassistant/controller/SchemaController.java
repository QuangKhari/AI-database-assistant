package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.DescriptionRequest;
import com.example.aidatabaseassistant.service.SchemaMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaMetadataService schemaMetadataService;

    @PutMapping("/tables/{tableId}")
    public ResponseEntity<Void> updateTableDescription(Authentication authentication,
                                                       @PathVariable Long tableId,
                                                       @Valid @RequestBody DescriptionRequest request) {
        schemaMetadataService.updateTableDescription(authentication.getName(), tableId, request.getDescription());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/columns/{columnId}")
    public ResponseEntity<Void> updateColumnDescription(Authentication authentication,
                                                        @PathVariable Long columnId,
                                                        @Valid @RequestBody DescriptionRequest request) {
        schemaMetadataService.updateColumnDescription(authentication.getName(), columnId, request.getDescription());
        return ResponseEntity.noContent().build();
    }
}