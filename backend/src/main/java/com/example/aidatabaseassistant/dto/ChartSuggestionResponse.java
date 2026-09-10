package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChartSuggestionResponse {
    private ChartType chartType;
    private List<ChartType> alternativeChartTypes;
    private String xAxisColumn;
    private List<String> xAxisLabels;
    private List<ChartSeriesDto> series;
    private String reason;
}