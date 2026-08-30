package com.example.aidatabaseassistant.dto;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminStatsResponse {
    private long totalUsers;
    private long totalConnections;
    private long totalConversations;
    private long totalQueries;
}