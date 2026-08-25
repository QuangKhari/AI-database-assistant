package com.example.aidatabaseassistant.dto;

import java.time.LocalDateTime;

public record UserProfileResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String role,
        boolean enabled,
        boolean locked,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
