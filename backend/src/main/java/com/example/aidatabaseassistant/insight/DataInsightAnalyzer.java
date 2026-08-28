package com.example.aidatabaseassistant.insight;

import com.example.aidatabaseassistant.chart.ChartClassificationResult;
import com.example.aidatabaseassistant.chart.ChartTypeClassifier;
import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.ChartSeriesDto;
import com.example.aidatabaseassistant.dto.ChartType;
import com.example.aidatabaseassistant.dto.TrendDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tinh toan CAC CON SO thuc te (tang truong %, cao/thap nhat, xu huong, ty
 * trong, bat thuong) bang THUAT TOAN THUAN - khong bao gio de AI tu tinh
 * toan so lieu, vi AI co the "bia" so sai (hallucination), rat nguy hiem
 * cho mot cong cu phan tich du lieu. AI (o DataInsightService) chi duoc
 * dung de VIET LAI cac con so DA CO SAN nay thanh cau van tu nhien.
 *
 * Tai su dung ChartTypeClassifier de xac dinh cot dimension/numeric - vua
 * tranh trung lap logic, vua tranh lap lai bug tung gap (cot dang so
 * nhung ten mang tinh thoi gian, vi du "thang" = 1, 2, 3...).
 */
@Component
@RequiredArgsConstructor
public class DataInsightAnalyzer {

    // Nguong z-score de coi 1 gia tri la BAT THUONG (outlier) so voi phan con lai.
    private static final double ANOMALY_Z_SCORE_THRESHOLD = 2.0;
    private static final int MAX_ANOMALIES = 3;
    // Nguong % thay doi toi thieu de coi la "tang/giam" thay vi "on dinh".
    private static final double STABLE_THRESHOLD_PERCENT = 1.0;

    private final ChartTypeClassifier classifier;

    public DataInsightFacts analyze(List<String> columns, List<Map<String, Object>> rows) {
        ChartClassificationResult classification = classifier.classify(columns, rows);

        // TABLE nghia la khong tim duoc cap dimension + numeric ro rang -> khong
        // du co so de dua ra insight dang tin cay, tra ve null de QueryService
        // fallback ve summary cu (theo dung quyet dinh thiet ke).
        if (classification.getChartType() == ChartType.TABLE || classification.getSeries().isEmpty()) {
            return null;
        }

        // Chi phan tich 1 cot so lieu DAU TIEN - giu tinh than "depth over
        // breadth": 1 insight duy nhat nhung chac chan dung, thay vi co gang
        // tong hop nhieu cot cung luc roi lam loang/sai lech ket luan.
        ChartSeriesDto primarySeries = classification.getSeries().get(0);
        List<Double> values = toDoubleValues(primarySeries.getData());
        List<String> labels = classification.getXAxisLabels();

        int highestIndex = indexOfMax(values);
        int lowestIndex = indexOfMin(values);

        DataInsightFacts facts = new DataInsightFacts();
        facts.setNumericColumn(primarySeries.getName());
        facts.setDimensionColumn(classification.getDimensionColumn());
        facts.setHighestLabel(labels.get(highestIndex));
        facts.setHighestValue(values.get(highestIndex));
        facts.setLowestLabel(labels.get(lowestIndex));
        facts.setLowestValue(values.get(lowestIndex));
        facts.setAnomalies(detectAnomalies(labels, values));

        boolean timeOrdered = classification.getChartType() == ChartType.LINE;
        if (timeOrdered) {
            facts.setGrowthPercent(computeGrowthPercent(values));
            facts.setTrend(computeTrend(values));
            // Ghi lai CHINH XAC nhan (label) cua diem dau/cuoi chuoi - de
            // AI viet summary KHONG phai tu doan khoang thoi gian cua
            // growthPercent (tranh nham lan voi nhan cua highest/lowest).
            if (facts.getGrowthPercent() != null) {
                facts.setPeriodStartLabel(labels.get(0));
                facts.setPeriodEndLabel(labels.get(labels.size() - 1));
            }
        } else {
            Double sharePercent = computeTopShare(values, highestIndex);
            if (sharePercent != null) {
                facts.setTopShareLabel(labels.get(highestIndex));
                facts.setTopSharePercent(sharePercent);
            }
        }

        return facts;
    }

    private List<Double> toDoubleValues(List<Object> data) {
        List<Double> values = new ArrayList<>();
        for (Object value : data) {
            values.add(value instanceof Number number ? number.doubleValue() : 0.0);
        }
        return values;
    }

    private int indexOfMax(List<Double> values) {
        int idx = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(idx)) {
                idx = i;
            }
        }
        return idx;
    }

    private int indexOfMin(List<Double> values) {
        int idx = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) < values.get(idx)) {
                idx = i;
            }
        }
        return idx;
    }

    private Double computeGrowthPercent(List<Double> values) {
        double first = values.get(0);
        double last = values.get(values.size() - 1);
        if (first == 0) {
            // Khong the tinh % tang truong tu 0 (chia cho 0) - bo qua thay vi
            // tra ve mot con so gay hieu lam (vi du Infinity).
            return null;
        }
        return ((last - first) / Math.abs(first)) * 100.0;
    }

    private TrendDirection computeTrend(List<Double> values) {
        Double growth = computeGrowthPercent(values);
        if (growth == null) {
            return null;
        }
        if (growth > STABLE_THRESHOLD_PERCENT) {
            return TrendDirection.INCREASING;
        }
        if (growth < -STABLE_THRESHOLD_PERCENT) {
            return TrendDirection.DECREASING;
        }
        return TrendDirection.STABLE;
    }

    /**
     * Ty trong cua danh muc CAO NHAT tren TONG - chi tinh khi tong khac 0 va
     * TAT CA gia tri deu khong am (neu co gia tri am, khai niem "ty trong %"
     * khong con ro rang nen bo qua de tranh dua ra con so gay hieu lam).
     */
    private Double computeTopShare(List<Double> values, int highestIndex) {
        double total = 0;
        for (double v : values) {
            if (v < 0) {
                return null;
            }
            total += v;
        }
        if (total == 0) {
            return null;
        }
        return (values.get(highestIndex) / total) * 100.0;
    }

    private List<AnomalyDto> detectAnomalies(List<String> labels, List<Double> values) {
        List<AnomalyDto> anomalies = new ArrayList<>();
        if (values.size() < 3) {
            // Can toi thieu vai diem du lieu thi do lech chuan (stddev) moi
            // co y nghia thong ke, tranh bao "bat thuong" tren tap qua nho.
            return anomalies;
        }

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0) {
            // Tat ca gia tri bang nhau -> khong the co "bat thuong".
            return anomalies;
        }

        for (int i = 0; i < values.size() && anomalies.size() < MAX_ANOMALIES; i++) {
            double zScore = (values.get(i) - mean) / stdDev;
            if (Math.abs(zScore) >= ANOMALY_Z_SCORE_THRESHOLD) {
                String direction = zScore > 0 ? "cao bất thường" : "thấp bất thường";
                anomalies.add(new AnomalyDto(labels.get(i), values.get(i), direction));
            }
        }
        return anomalies;
    }
}