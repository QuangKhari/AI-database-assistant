package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class QueryResultDto {
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private long executionTimeMs;
    private int rowCount;
    private String error;
}