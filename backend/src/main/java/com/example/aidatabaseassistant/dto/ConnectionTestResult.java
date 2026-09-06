package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ConnectionTestResult {

    private boolean successful;

    private boolean readOnlyVerified;

    private String code;

    private String message;

    private long durationMs;

    private String serverVersion;
}