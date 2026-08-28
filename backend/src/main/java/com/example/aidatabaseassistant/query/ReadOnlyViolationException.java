package com.example.aidatabaseassistant.query;

public class ReadOnlyViolationException extends RuntimeException {

    public ReadOnlyViolationException(String message) {
        super(message);
    }

}
