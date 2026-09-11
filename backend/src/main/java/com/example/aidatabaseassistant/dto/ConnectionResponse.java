package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ConnectionResponse {

    public ConnectionResponse(
            Long id, String name, String dbType, String host, Integer port,
            String databaseName, String username, boolean active,
            LocalDateTime lastTestedAt, Boolean lastTestSuccessful,
            LocalDateTime createdAt, LocalDateTime updatedAt
    ) {
        this(id, name, dbType, false, host, port, databaseName, username,
                active, lastTestedAt, lastTestSuccessful, createdAt, updatedAt);
    }

    private Long id;

    private String name;

    private String dbType;

    private boolean sslEnabled;

    private String host;

    private Integer port;

    private String databaseName;

    private String username;

    private boolean active;

    private LocalDateTime lastTestedAt;

    private Boolean lastTestSuccessful;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}