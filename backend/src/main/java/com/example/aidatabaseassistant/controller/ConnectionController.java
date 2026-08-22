package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.service.ConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.example.aidatabaseassistant.dto.SchemaResponse;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;
    private final SchemaDiscoveryService schemaDiscoveryService;
//    private final com.example.aidatabaseassistant.ai.LLMClient llmClient;
//
//    @GetMapping("/test-ai")
//    public ResponseEntity<String> testAi() {
//        return ResponseEntity.ok(llmClient.generateResponse("Xin chào, bạn là ai?"));
//    }

    @PostMapping("/{id}/schema")
    public ResponseEntity<SchemaResponse> discoverSchema(@PathVariable Long id) {
        var schema = schemaDiscoveryService.discoverSchema(id);

        var tables = schema.getTables().stream()
                .map(t -> new SchemaResponse.TableInfo(
                        t.getName(),
                        t.getDescription(),
                        t.getColumns().stream()
                                .map(c -> new SchemaResponse.ColumnInfo(
                                        c.getName(), c.getDataType(), c.getPrimaryKey(), c.getForeignKey(),
                                        c.getReferencedTable(), c.getReferencedColumn(), c.getDescription()))
                                .toList()
                ))
                .toList();

        return ResponseEntity.ok(new SchemaResponse(schema.getDatabaseName(), schema.getLastSyncedAt(), tables));
    }

    @PostMapping("/test")
    public ResponseEntity<Boolean> testConnection(@Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(connectionService.testConnection(request));
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> saveConnection(Authentication authentication,
                                                             @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(connectionService.saveConnection(authentication.getName(), request));
    }

    @GetMapping
    public ResponseEntity<List<ConnectionResponse>> getConnections(Authentication authentication) {
        return ResponseEntity.ok(connectionService.getConnectionsByUser(authentication.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@PathVariable Long id) {
        connectionService.disconnect(id);
        return ResponseEntity.noContent().build();
    }
}