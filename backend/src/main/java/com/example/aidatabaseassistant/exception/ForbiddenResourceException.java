package com.example.aidatabaseassistant.exception;

public class ForbiddenResourceException extends RuntimeException {

    public ForbiddenResourceException(String message) {
        super(message);
    }
}