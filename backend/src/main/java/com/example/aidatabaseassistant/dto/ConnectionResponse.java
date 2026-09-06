package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ConnectionResponse {

    private Long id;

    private String name;

    private String dbType;

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