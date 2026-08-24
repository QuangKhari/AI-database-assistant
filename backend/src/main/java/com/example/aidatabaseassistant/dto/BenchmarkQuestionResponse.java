package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BenchmarkQuestionResponse {
    private Long id;
    private String questionText;
    private String expectedSql;
    private Long connectionId;
}