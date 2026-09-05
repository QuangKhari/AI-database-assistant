package com.example.aidatabaseassistant.exception;

public class QueryAlreadyRunningException extends RuntimeException {

    public QueryAlreadyRunningException(String message) {
        super(message);
    }
}
