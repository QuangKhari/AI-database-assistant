package com.example.aidatabaseassistant.dto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AdminConnectionResponse {
    private Long id;
    private String name;
    private String dbType;
    private String host;
    private String databaseName;
    private String ownerUsername;
    private LocalDateTime createdAt;
}