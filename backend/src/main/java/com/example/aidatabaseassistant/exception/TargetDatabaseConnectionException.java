package com.example.aidatabaseassistant.exception;

import lombok.Getter;

@Getter
public class TargetDatabaseConnectionException extends RuntimeException {
    private final String code;

    public TargetDatabaseConnectionException(String code, String message) {
        super(message);
        this.code = code;
    }
}
