package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class OptimizationIssueDto {

    // "HIGH" | "MEDIUM" | "LOW"
    private String severity;

    private String table;

    private String description;
}