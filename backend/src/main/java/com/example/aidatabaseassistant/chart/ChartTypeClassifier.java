package com.example.aidatabaseassistant.chart;

import com.example.aidatabaseassistant.dto.ChartSeriesDto;
import com.example.aidatabaseassistant.dto.ChartType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ChartTypeClassifier {

    private static final int PIE_MAX_CATEGORIES = 6;

    private static final Set<String> TIME_KEYWORDS = Set.of(
            "thang", "month", "nam", "year", "ngay", "day", "date",
            "quy", "quarter", "tuan", "week", "time"
    );

    public ChartClassificationResult classify(List<String> columns, List<Map<String, Object>> rows) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) {
            return ChartClassificationResult.table("Không có dữ liệu để vẽ biểu đồ");
        }
        if (rows.size() == 1) {
            return ChartClassificationResult.table(
                    "chỉ có 1 dòng dữ liệu, chưa đủ để thể hiện xu hướng hoặc so sánh");
        }

        List<String> numericColumns = new ArrayList<>();
        String dimensionColumn = null;
        String numericTimeLikeColumn = null; // cot vua la SO vua co TEN goi y thoi gian (vi du "thang" = 1,2,3)

        for (String column : columns) {
            if (isNumericColumn(column, rows)) {
                if (numericTimeLikeColumn == null && matchesTimeKeyword(column)) {
                    // Khong add vao numericColumns ngay - de quyet dinh o duoi,
                    // vi day co the la MOC THOI GIAN (dimension) chu khong
                    // phai SO LIEU (measure), du kieu du lieu la so nguyen.
                    numericTimeLikeColumn = column;
                } else {
                    numericColumns.add(column);
                }
            } else if (dimensionColumn == null) {
                // Chi lay cot KHONG PHAI so DAU TIEN lam truc X - giu bieu do
                // don gian, dung tinh than "depth over breadth" thay vi co
                // gang ho tro nhieu chieu du lieu (multi-dimension) cung luc.
                dimensionColumn = column;
            }
        }

        if (numericTimeLikeColumn != null) {
            if (dimensionColumn == null) {
                // Chua co dimension "tu nhien" nao (dang text/date) - uu tien
                // dung cot SO mang ten thoi gian nay lam truc X, vi ve ngu
                // nghia day la moc thoi gian chu khong phai so lieu can cong/
                // trung binh (vi du "thang" khong the SUM lai duoc).
                dimensionColumn = numericTimeLikeColumn;
            } else {
                // Da co dimension khac (vi du cot text) dung truoc trong ket
                // qua - luc nay coi no la so lieu binh thuong, khong hy sinh
                // dimension da co.
                numericColumns.add(numericTimeLikeColumn);
            }
        }

        if (numericColumns.isEmpty()) {
            return ChartClassificationResult.table("không tìm thấy cột số liệu (measure) nào để vẽ biểu đồ");
        }
        if (dimensionColumn == null) {
            return ChartClassificationResult.table(
                    "không tìm thấy cột danh mục hoặc thời gian nào để làm trục X");
        }

        List<String> xAxisLabels = extractLabels(dimensionColumn, rows);
        long cardinality = xAxisLabels.stream().distinct().count();

        List<ChartSeriesDto> series = new ArrayList<>();
        for (String numericColumn : numericColumns) {
            series.add(new ChartSeriesDto(numericColumn, extractNumericValues(numericColumn, rows)));
        }

        boolean timeLike = isTimeLikeColumn(dimensionColumn, rows);

        ChartType chartType;
        List<ChartType> alternatives;
        String hint;

        if (timeLike) {
            chartType = ChartType.LINE;
            alternatives = List.of(ChartType.BAR);
            hint = "cột '" + dimensionColumn + "' mang tính thời gian nên phù hợp thể hiện xu hướng theo trục thời gian";
        } else if (numericColumns.size() == 1 && cardinality <= PIE_MAX_CATEGORIES) {
            chartType = ChartType.PIE;
            alternatives = List.of(ChartType.DONUT, ChartType.BAR);
            hint = "chỉ có " + cardinality + " danh mục và 1 cột số liệu nên phù hợp thể hiện tỷ trọng";
        } else if (numericColumns.size() == 1) {
            chartType = ChartType.BAR;
            alternatives = List.of(ChartType.LINE);
            hint = "có " + cardinality + " danh mục (khá nhiều) nên biểu đồ cột dễ so sánh hơn biểu đồ tròn";
        } else {
            chartType = ChartType.BAR;
            alternatives = List.of(ChartType.LINE);
            hint = "có " + numericColumns.size() + " cột số liệu cần so sánh cùng lúc theo từng danh mục";
        }

        return new ChartClassificationResult(chartType, alternatives, dimensionColumn, xAxisLabels, series, hint);
    }

    private boolean isNumericColumn(String column, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value != null) {
                return value instanceof Number;
            }
        }
        return false;
    }

    private boolean isTimeLikeColumn(String column, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value == null) {
                continue;
            }
            return value instanceof Date || value instanceof Temporal || matchesTimeKeyword(column);
        }
        return matchesTimeKeyword(column);
    }

    private boolean matchesTimeKeyword(String column) {
        Set<String> tokens = tokenize(column);
        return tokens.stream().anyMatch(TIME_KEYWORDS::contains);
    }

    private List<String> extractLabels(String column, List<Map<String, Object>> rows) {
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            labels.add(value == null ? "N/A" : value.toString());
        }
        return labels;
    }

    private List<Object> extractNumericValues(String column, List<Map<String, Object>> rows) {
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            values.add(value == null ? 0 : value);
        }
        return values;
    }

    private Set<String> tokenize(String column) {
        String noAccent = Normalizer.normalize(column, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String spaced = noAccent.replaceAll("([a-z])([A-Z])", "$1 $2");
        String[] parts = spaced.toLowerCase(Locale.ROOT).split("[^a-zA-Z0-9]+");
        return new HashSet<>(Arrays.asList(parts));
    }
}