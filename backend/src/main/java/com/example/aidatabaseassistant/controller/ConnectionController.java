package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionTestResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.service.ConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private final ConnectionService connectionService;

    @PostMapping("/test")
    public ResponseEntity<ConnectionTestResponse> testConnection(
            Authentication authentication, @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.ok(connectionService.testConnection(authentication.getName(), request));
    }

    @PostMapping
    public ResponseEntity<ConnectionResponse> saveConnection(Authentication authentication,
                                                             @Valid @RequestBody ConnectionRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(connectionService.saveConnection(authentication.getName(), request));
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
    public ResponseEntity<ConnectionTestResponse> reconnect(
            Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(connectionService.reconnect(authentication.getName(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(Authentication authentication, @PathVariable Long id) {
        connectionService.disconnect(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
