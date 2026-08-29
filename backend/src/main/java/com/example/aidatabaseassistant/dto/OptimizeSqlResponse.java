package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class OptimizeSqlResponse {

    private String sql;
    private List<ExplainRowDto> explainPlan;
    private List<OptimizationIssueDto> issues;
    private List<IndexSuggestionDto> suggestions;
    private String aiSummary;
}