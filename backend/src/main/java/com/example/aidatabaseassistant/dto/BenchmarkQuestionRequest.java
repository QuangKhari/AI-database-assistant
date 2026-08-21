package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenchmarkQuestionRequest {
    @NotBlank
    private String questionText;

    @NotBlank
    private String expectedSql;
}