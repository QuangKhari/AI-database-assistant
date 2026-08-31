package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.*;
import com.example.aidatabaseassistant.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")   // enforce ở method-security layer (kép với SecurityConfig)
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> getUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @GetMapping("/users/search")
    public ResponseEntity<List<AdminUserResponse>> searchUsers(
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(adminService.searchUsers(keyword));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<AdminUserResponse> updateRole(
            Authentication authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request) {

        return ResponseEntity.ok(
                adminService.updateRole(
                        id,
                        request.getRole(),
                        authentication.getName()
                )
        );
    }

    @PatchMapping("/users/{id}/lock")
    public ResponseEntity<AdminUserResponse> lockUser(Authentication authentication,
                                                      @PathVariable Long id) {
        return ResponseEntity.ok(adminService.lockUser(id, authentication.getName()));
    }

    @PatchMapping("/users/{id}/unlock")
    public ResponseEntity<AdminUserResponse> unlockUser(@PathVariable Long id) {
        return ResponseEntity.ok(adminService.unlockUser(id));
    }

    @GetMapping("/connections")
    public ResponseEntity<List<AdminConnectionResponse>> getAllConnections() {
        return ResponseEntity.ok(adminService.getAllConnections());
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable Long id) {
        adminService.deleteConnection(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }
}