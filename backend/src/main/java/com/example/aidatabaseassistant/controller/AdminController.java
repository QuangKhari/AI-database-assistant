package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.AdminStatsResponse;
import com.example.aidatabaseassistant.dto.AdminUserPageResponse;
import com.example.aidatabaseassistant.dto.AdminUserResponse;
import com.example.aidatabaseassistant.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/users")
    public ResponseEntity<AdminUserPageResponse> getUsers(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminService.getUsers(search, page, size));
    }

    @PatchMapping("/users/{id}/lock")
    public ResponseEntity<AdminUserResponse> lockUser(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(adminService.setLocked(authentication.getName(), id, true));
    }

    @PatchMapping("/users/{id}/unlock")
    public ResponseEntity<AdminUserResponse> unlockUser(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(adminService.setLocked(authentication.getName(), id, false));
    }
}
