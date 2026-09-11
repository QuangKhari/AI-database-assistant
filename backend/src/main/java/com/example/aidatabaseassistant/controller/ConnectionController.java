package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.SuggestedQuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;
    private final SchemaDiscoveryService schemaDiscoveryService;
    private final SuggestedQuestionService suggestedQuestionService;
    private final com.example.aidatabaseassistant.service.RateLimitService rateLimitService;

    // GIỮ LẠI để không phá FE cũ nào còn gọi endpoint này, nhưng FE hiện tại
    // (SchemaExplorerPage) đã dùng /api/schema/connections/{id}/sync (xem
    // SchemaController). Hai endpoint cùng trả 1 shape SchemaResponse để
    // không lệch contract dù dùng endpoint nào.
    @PostMapping("/{id}/schema")
    public ResponseEntity<SchemaResponse> discoverSchema(Authentication authentication, @PathVariable Long id) {
        var schema = schemaDiscoveryService.discoverSchema(authentication.getName(), id);

        var tables = schema.getTables().stream()
                .map(t -> new SchemaResponse.TableInfo(
                        t.getId(),
                        t.getName(),
                        t.getDescription(),
                        t.getColumns().stream()
                                .map(c -> new SchemaResponse.ColumnInfo(
                                        c.getId(), c.getName(), c.getDataType(),
                                        Boolean.TRUE.equals(c.getNullable()),
                                        c.getPrimaryKey(), c.getForeignKey(),
                                        c.getReferencedTable(), c.getReferencedColumn(), c.getDescription()))
                                .toList()
                ))
                .toList();

        return ResponseEntity.ok(new SchemaResponse(
                schema.getId(), schema.getConnection().getId(),
                schema.getDatabaseName(), schema.getLastSyncedAt(), tables));
    }

    @PostMapping("/test")
    public ResponseEntity<ConnectionTestResult> testConnection(
            Authentication authentication,
            @Valid @RequestBody ConnectionRequest request) {

        // Endpoint này mở kết nối TCP thật tới host/port do người dùng nhập
        // (chỉ chặn IP nội bộ/reserved qua SsrfProtection)
        if (!rateLimitService.tryConsume(authentication.getName())) {
            throw new com.example.aidatabaseassistant.exception.RateLimitExceededException(
                    "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút"
            );
        }

        return ResponseEntity.ok(connectionService.testConnection(request));
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> saveConnection(Authentication authentication,
                                                             @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(connectionService.saveConnection(authentication.getName(), request));
    }

    @PostMapping(value = "/excel", consumes = "multipart/form-data")
    public ResponseEntity<ConnectionResponse> uploadExcelConnection(
            Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name) {
        return ResponseEntity.ok(
                connectionService.saveExcelConnection(authentication.getName(), file, name));
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
    public ResponseEntity<ConnectionTestResult> reconnect(Authentication authentication, @PathVariable Long id) {
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