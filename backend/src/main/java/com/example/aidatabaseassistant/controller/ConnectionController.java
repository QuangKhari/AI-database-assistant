package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.SuggestedQuestionService;
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
    private final SuggestedQuestionService suggestedQuestionService;
    private final com.example.aidatabaseassistant.service.RateLimitService rateLimitService;
//    private final com.example.aidatabaseassistant.ai.LLMClient llmClient;
//
//    @GetMapping("/test-ai")
//    public ResponseEntity<String> testAi() {
//        return ResponseEntity.ok(llmClient.generateResponse("Xin chào, bạn là ai?"));
//    }

    @PostMapping("/{id}/schema")
    public ResponseEntity<SchemaResponse> discoverSchema(Authentication authentication, @PathVariable Long id) {
        var schema = schemaDiscoveryService.discoverSchema(authentication.getName(), id);

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

    @GetMapping("/{id}")
    public ResponseEntity<ConnectionResponse> getConnection(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.getConnection(authentication.getName(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConnectionResponse> updateConnection(Authentication authentication,
                                                               @PathVariable Long id,
                                                               @Valid @RequestBody ConnectionUpdateRequest request) {
        return ResponseEntity.ok(connectionService.updateConnection(authentication.getName(), id, request));
    }

    @PostMapping("/{id}/reconnect")
    public ResponseEntity<Boolean> reconnect(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.reconnect(authentication.getName(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(Authentication authentication, @PathVariable Long id) {
        connectionService.disconnect(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/suggested-questions")
    public ResponseEntity<com.example.aidatabaseassistant.dto.SuggestedQuestionsResponse> getSuggestedQuestions(
            Authentication authentication,
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh) {

        // Dong bo voi cac endpoint goi Gemini khac trong QueryController -
        // truoc day endpoint nay khong co rate limit, cho phep spam
        // refresh=true de dot quota/chi phi Gemini khong gioi han.
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(
                suggestedQuestionService.getSuggestions(authentication.getName(), id, refresh)
        );
    }
}