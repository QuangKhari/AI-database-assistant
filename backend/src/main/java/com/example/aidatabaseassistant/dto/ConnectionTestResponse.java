package com.example.aidatabaseassistant.dto;

public record ConnectionTestResponse(
        boolean successful,
        boolean readOnlyVerified,
        String code,
        String message,
        long durationMs,
        String serverVersion
) {
}
