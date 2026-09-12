package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PreviewResponse {
    private String generatedSql;
    private boolean valid;
    private String errorMessage;

    private boolean blocked;
}