package com.example.aidatabaseassistant.service;

@FunctionalInterface
public interface QueryProgressListener {

    void onProgress(String stage, String message);

    QueryProgressListener NOOP = (stage, message) -> { };
}