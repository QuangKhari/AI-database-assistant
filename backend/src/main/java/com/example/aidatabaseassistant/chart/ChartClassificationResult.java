package com.example.aidatabaseassistant.chart;

import com.example.aidatabaseassistant.dto.ChartSeriesDto;
import com.example.aidatabaseassistant.dto.ChartType;
import lombok.Getter;

import java.util.List;

@Getter
public class ChartClassificationResult {

    private final ChartType chartType;
    private final List<ChartType> alternatives;
    private final String dimensionColumn;
    private final List<String> xAxisLabels;
    private final List<ChartSeriesDto> series;
    private final String reasonHint;

    public ChartClassificationResult(ChartType chartType, List<ChartType> alternatives, String dimensionColumn,
                                     List<String> xAxisLabels, List<ChartSeriesDto> series, String reasonHint) {
        this.chartType = chartType;
        this.alternatives = alternatives;
        this.dimensionColumn = dimensionColumn;
        this.xAxisLabels = xAxisLabels;
        this.series = series;
        this.reasonHint = reasonHint;
    }

    public static ChartClassificationResult table(String reasonHint) {
        return new ChartClassificationResult(ChartType.TABLE, List.of(), null, List.of(), List.of(), reasonHint);
    }
}