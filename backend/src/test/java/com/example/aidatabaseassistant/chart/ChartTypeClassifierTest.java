package com.example.aidatabaseassistant.chart;

import com.example.aidatabaseassistant.dto.ChartType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChartTypeClassifierTest {

    private ChartTypeClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new ChartTypeClassifier();
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void shouldSuggestTable_whenNoRows() {
        ChartClassificationResult result = classifier.classify(List.of("thang", "doanh_thu"), List.of());

        assertEquals(ChartType.TABLE, result.getChartType());
    }

    @Test
    void shouldSuggestTable_whenOnlyOneRow() {
        List<Map<String, Object>> rows = List.of(row("thang", 1, "doanh_thu", 100));

        ChartClassificationResult result = classifier.classify(List.of("thang", "doanh_thu"), rows);

        assertEquals(ChartType.TABLE, result.getChartType());
    }

    @Test
    void shouldSuggestTable_whenNoNumericColumn() {
        List<Map<String, Object>> rows = List.of(
                row("full_name", "Nguyen Van A"),
                row("full_name", "Tran Thi B")
        );

        ChartClassificationResult result = classifier.classify(List.of("full_name"), rows);

        assertEquals(ChartType.TABLE, result.getChartType());
    }

    @Test
    void shouldSuggestLine_whenDimensionColumnIsDateType() {
        List<Map<String, Object>> rows = List.of(
                row("ngay", LocalDate.of(2024, 1, 1), "doanh_thu", new BigDecimal("1000")),
                row("ngay", LocalDate.of(2024, 2, 1), "doanh_thu", new BigDecimal("1500"))
        );

        ChartClassificationResult result = classifier.classify(List.of("ngay", "doanh_thu"), rows);

        assertEquals(ChartType.LINE, result.getChartType());
        assertEquals("ngay", result.getDimensionColumn());
        assertTrue(result.getAlternatives().contains(ChartType.BAR));
    }

    @Test
    void shouldSuggestLine_whenColumnNameLooksLikeMonth_evenIfValueIsPlainInteger() {
        // "thang" (thang ban hang) tra ve Integer thuan (1, 2, 3...) chu
        // khong phai kieu Date/Timestamp - phai nhan dien qua TEN cot.
        List<Map<String, Object>> rows = List.of(
                row("thang", 1, "doanh_thu", 1000),
                row("thang", 2, "doanh_thu", 1500),
                row("thang", 3, "doanh_thu", 1200)
        );

        ChartClassificationResult result = classifier.classify(List.of("thang", "doanh_thu"), rows);

        assertEquals(ChartType.LINE, result.getChartType());
    }

    @Test
    void shouldNotMisdetect_nameColumnAsTimeLike() {
        // "name" chua chuoi con "nam" (nam = year) - phai KHONG bi nham la
        // cot thoi gian chi vi trung substring.
        List<Map<String, Object>> rows = List.of(
                row("name", "Product A", "revenue", 1000),
                row("name", "Product B", "revenue", 2000),
                row("name", "Product C", "revenue", 500),
                row("name", "Product D", "revenue", 700),
                row("name", "Product E", "revenue", 300),
                row("name", "Product F", "revenue", 900),
                row("name", "Product G", "revenue", 100),
                row("name", "Product H", "revenue", 400)
        );

        ChartClassificationResult result = classifier.classify(List.of("name", "revenue"), rows);

        assertNotEquals(ChartType.LINE, result.getChartType());
    }

    @Test
    void shouldSuggestPie_whenSingleNumericColumnAndFewCategories() {
        List<Map<String, Object>> rows = List.of(
                row("khu_vuc", "Mien Bac", "doanh_thu", 1000),
                row("khu_vuc", "Mien Trung", "doanh_thu", 500),
                row("khu_vuc", "Mien Nam", "doanh_thu", 1500)
        );

        ChartClassificationResult result = classifier.classify(List.of("khu_vuc", "doanh_thu"), rows);

        assertEquals(ChartType.PIE, result.getChartType());
        assertTrue(result.getAlternatives().contains(ChartType.DONUT));
    }

    @Test
    void shouldSuggestBar_whenSingleNumericColumnButManyCategories() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rows.add(row("san_pham", "SP" + i, "so_luong_ban", i * 10));
        }

        ChartClassificationResult result = classifier.classify(List.of("san_pham", "so_luong_ban"), rows);

        assertEquals(ChartType.BAR, result.getChartType());
    }

    @Test
    void shouldSuggestBar_whenMultipleNumericColumns() {
        List<Map<String, Object>> rows = List.of(
                row("san_pham", "SP1", "doanh_thu", 1000, "loi_nhuan", 200),
                row("san_pham", "SP2", "doanh_thu", 2000, "loi_nhuan", 400),
                row("san_pham", "SP3", "doanh_thu", 500, "loi_nhuan", 50)
        );

        ChartClassificationResult result = classifier.classify(List.of("san_pham", "doanh_thu", "loi_nhuan"), rows);

        assertEquals(ChartType.BAR, result.getChartType());
        assertEquals(2, result.getSeries().size());
    }

    @Test
    void shouldReplaceNullNumericValues_withZero() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("thang", 1);
        r1.put("doanh_thu", null);
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("thang", 2);
        r2.put("doanh_thu", 500);
        rows.add(r1);
        rows.add(r2);

        ChartClassificationResult result = classifier.classify(List.of("thang", "doanh_thu"), rows);

        assertEquals(0, result.getSeries().get(0).getData().get(0));
    }
}