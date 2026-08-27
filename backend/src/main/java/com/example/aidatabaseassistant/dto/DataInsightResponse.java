package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class DataInsightResponse {

    private String numericColumn;
    private String dimensionColumn;

    private String highestLabel;
    private double highestValue;
    private String lowestLabel;
    private double lowestValue;

    // Null neu khong phai chuoi thoi gian (vi du danh muc khong co thu tu).
    private Double growthPercent;
    private TrendDirection trend;

    // Null neu la chuoi thoi gian (khai niem "ty trong" khong ap dung cho tang truong).
    private String topShareLabel;
    private Double topSharePercent;

    private List<AnomalyDto> anomalies;

    // Cau van AI dien giai lai tu cac con so tren; co fallback template neu AI loi.
    private String summary;
}