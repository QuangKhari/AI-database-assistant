package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.DescriptionRequest;
import com.example.aidatabaseassistant.dto.SchemaResponse;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import com.example.aidatabaseassistant.service.SchemaMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schema")
@RequiredArgsConstructor
public class SchemaController {

    private final SchemaMetadataService schemaMetadataService;
    private final SchemaDiscoveryService schemaDiscoveryService;

    @GetMapping("/connections/{connectionId}")
    public ResponseEntity<SchemaResponse> getSchema(Authentication authentication,
                                                    @PathVariable Long connectionId) {
        return ResponseEntity.ok(schemaDiscoveryService.getSchema(authentication.getName(), connectionId));
    }

    @PostMapping("/connections/{connectionId}/sync")
    public ResponseEntity<SchemaResponse> syncSchema(Authentication authentication,
                                                     @PathVariable Long connectionId) {
        return ResponseEntity.ok(schemaDiscoveryService.discoverSchema(authentication.getName(), connectionId));
    }

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
