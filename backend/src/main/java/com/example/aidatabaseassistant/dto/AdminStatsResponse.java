package com.example.aidatabaseassistant.dto;

public record AdminStatsResponse(
        long totalUsers,
        long activeUsers,
        long lockedUsers,
        long activeConnections
) {}
