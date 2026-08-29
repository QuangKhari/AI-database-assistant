package com.example.aidatabaseassistant.optimization;

import com.example.aidatabaseassistant.dto.ExplainRowDto;
import com.example.aidatabaseassistant.dto.IndexSuggestionDto;
import com.example.aidatabaseassistant.dto.OptimizationIssueDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Ket qua thuan tuy thuat toan cua SqlOptimizationAnalyzer - CHUA co
 * aiSummary (phan do do SqlOptimizationService bo sung sau khi goi Gemini).
 */
@Getter
@AllArgsConstructor
public class SqlOptimizationResult {
    private List<ExplainRowDto> explainRows;
    private List<OptimizationIssueDto> issues;
    private List<IndexSuggestionDto> suggestions;
}