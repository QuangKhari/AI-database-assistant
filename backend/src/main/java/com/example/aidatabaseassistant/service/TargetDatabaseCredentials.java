package com.example.aidatabaseassistant.service;

public record TargetDatabaseCredentials(
        String host,
        int port,
        String databaseName,
        String username,
        String password
) {
}
