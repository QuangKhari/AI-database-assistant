package com.example.aidatabaseassistant.insight;

import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.TrendDirection;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Ket qua noi bo cua DataInsightAnalyzer - CHUA phai DTO API (xem
 * DataInsightResponse). Moi con so trong day deu do thuat toan tinh CHINH
 * XAC, khong phai AI uoc luong.
 */
@Getter
@Setter
public class DataInsightFacts {
    private String numericColumn;
    private String dimensionColumn;

    private String highestLabel;
    private double highestValue;
    private String lowestLabel;
    private double lowestValue;

    private Double growthPercent;
    private TrendDirection trend;

    private String topShareLabel;
    private Double topSharePercent;

    private List<AnomalyDto> anomalies;
}