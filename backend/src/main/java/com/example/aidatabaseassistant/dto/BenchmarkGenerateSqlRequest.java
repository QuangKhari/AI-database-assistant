package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BenchmarkGenerateSqlRequest {

    @NotBlank
    private String questionText;
}