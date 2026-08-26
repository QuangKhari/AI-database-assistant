package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ExplainSqlResponse {
    private String sql;
    private String summary;
    private List<SqlExplanationStep> steps;
}