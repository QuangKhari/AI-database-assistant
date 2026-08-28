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

    // Nhan (label) diem dau/cuoi chuoi ma growthPercent duoc tinh tren do -
    // luon di kem growthPercent (cung null hoac cung co gia tri), de FE/AI
    // khong bao gio phai tu doan khoang thoi gian cua con so tang truong.
    private String periodStartLabel;
    private String periodEndLabel;

    // Null neu la chuoi thoi gian (khai niem "ty trong" khong ap dung cho tang truong).
    private String topShareLabel;
    private Double topSharePercent;

    private List<AnomalyDto> anomalies;

    // Cau van AI dien giai lai tu cac con so tren; co fallback template neu AI loi.
    private String summary;
}