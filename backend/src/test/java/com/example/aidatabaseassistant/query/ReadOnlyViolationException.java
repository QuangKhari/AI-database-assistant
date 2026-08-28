package com.example.aidatabaseassistant.query;

public class ReadOnlyViolationException extends IllegalArgumentException {

    public ReadOnlyViolationException(String message) {
        super(message);
    }
}