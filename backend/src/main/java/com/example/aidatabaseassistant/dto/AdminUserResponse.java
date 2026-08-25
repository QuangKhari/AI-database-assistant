package com.example.aidatabaseassistant.dto;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String role,
        boolean enabled,
        boolean locked,
        long connectionCount,
        LocalDateTime createdAt
) {}
