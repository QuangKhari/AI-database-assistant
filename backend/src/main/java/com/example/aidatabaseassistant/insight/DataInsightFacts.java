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

    // Nhan (label) cua diem DAU va CUOI chuoi du lieu ma growthPercent duoc
    // tinh tren do - BAT BUOC phai co trong prompt gui cho AI, neu khong AI
    // se TU SUY DOAN khoang thoi gian (vi du nham voi nhan cua highest/
    // lowest), day la loai "bia so lieu" tinh vi da phat hien qua test thuc te.
    private String periodStartLabel;
    private String periodEndLabel;

    private String topShareLabel;
    private Double topSharePercent;

    private List<AnomalyDto> anomalies;
}