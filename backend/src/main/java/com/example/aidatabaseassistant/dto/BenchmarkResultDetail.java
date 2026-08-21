package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BenchmarkResultDetail {
    private String questionText;
    private String generatedSql;
    private String expectedSql;
    private boolean correct;
    private String errorMessage;
}