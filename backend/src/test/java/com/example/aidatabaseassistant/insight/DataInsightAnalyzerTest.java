package com.example.aidatabaseassistant.insight;

import com.example.aidatabaseassistant.chart.ChartTypeClassifier;
import com.example.aidatabaseassistant.dto.AnomalyDto;
import com.example.aidatabaseassistant.dto.TrendDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataInsightAnalyzer la THUAT TOAN THUAN (khong goi AI), nen test nay
 * dung ChartTypeClassifier THAT (khong mock) - giong tinh than cua
 * ChartSuggestionServiceTest - de dam bao ca chuoi tinh toan so lieu
 * (khong phai AI) luon dung 100%.
 */
class DataInsightAnalyzerTest {

    private DataInsightAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new DataInsightAnalyzer(new ChartTypeClassifier());
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void analyze_shouldReturnNull_whenOnlyOneRow() {
        // 1 dong du lieu -> ChartTypeClassifier tra ve TABLE -> khong du co
        // so de dua ra insight dang tin cay.
        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(row("thang", 1, "doanh_thu", 1000));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNull(facts);
    }

    @Test
    void analyze_shouldReturnNull_whenNoNumericColumn() {
        List<String> columns = List.of("full_name", "city");
        List<Map<String, Object>> rows = List.of(
                row("full_name", "Nguyen Van A", "city", "Hà Nội"),
                row("full_name", "Tran Thi B", "city", "Đà Nẵng"));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNull(facts);
    }

    @Test
    void analyze_shouldComputeGrowthAndTrend_whenDimensionIsTimeLike() {
        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("thang", 1, "doanh_thu", 1000),
                row("thang", 2, "doanh_thu", 1200),
                row("thang", 3, "doanh_thu", 1500),
                row("thang", 4, "doanh_thu", 2000));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNotNull(facts);
        assertEquals("doanh_thu", facts.getNumericColumn());
        assertEquals("thang", facts.getDimensionColumn());
        assertEquals("4", facts.getHighestLabel());
        assertEquals(2000.0, facts.getHighestValue());
        assertEquals("1", facts.getLowestLabel());
        assertEquals(1000.0, facts.getLowestValue());
        assertNotNull(facts.getGrowthPercent());
        assertEquals(100.0, facts.getGrowthPercent(), 0.001);
        assertEquals(TrendDirection.INCREASING, facts.getTrend());
        // Chuoi thoi gian thi khong tinh ty trong (topShare).
        assertNull(facts.getTopShareLabel());
        assertNull(facts.getTopSharePercent());
    }

    @Test
    void analyze_shouldReturnNullGrowthAndTrend_whenFirstValueIsZero() {
        // Khong the tinh % tang truong tu 0 (chia cho 0) - phai bo qua
        // thay vi tra ve mot con so gay hieu lam.
        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("thang", 1, "doanh_thu", 0),
                row("thang", 2, "doanh_thu", 50),
                row("thang", 3, "doanh_thu", 100));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNotNull(facts);
        assertNull(facts.getGrowthPercent());
        assertNull(facts.getTrend());
        // Cao/thap nhat van phai tinh binh thuong.
        assertEquals("3", facts.getHighestLabel());
        assertEquals(100.0, facts.getHighestValue());
    }

    @Test
    void analyze_shouldComputeTopShare_whenDimensionIsCategorical() {
        List<String> columns = List.of("khu_vuc", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("khu_vuc", "Miền Bắc", "doanh_thu", 100),
                row("khu_vuc", "Miền Trung", "doanh_thu", 300),
                row("khu_vuc", "Miền Nam", "doanh_thu", 600));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNotNull(facts);
        assertEquals("Miền Nam", facts.getHighestLabel());
        assertEquals(600.0, facts.getHighestValue());
        assertEquals("Miền Bắc", facts.getLowestLabel());
        assertEquals(100.0, facts.getLowestValue());
        // Danh muc (khong phai thoi gian) thi khong tinh growth/trend.
        assertNull(facts.getGrowthPercent());
        assertNull(facts.getTrend());
        assertEquals("Miền Nam", facts.getTopShareLabel());
        assertEquals(60.0, facts.getTopSharePercent(), 0.001);
    }

    @Test
    void analyze_shouldDetectAnomaly_whenOneValueDeviatesSignificantly() {
        List<String> columns = List.of("san_pham", "so_luong");
        List<Map<String, Object>> rows = List.of(
                row("san_pham", "sp1", "so_luong", 10),
                row("san_pham", "sp2", "so_luong", 10),
                row("san_pham", "sp3", "so_luong", 10),
                row("san_pham", "sp4", "so_luong", 10),
                row("san_pham", "sp5", "so_luong", 10),
                row("san_pham", "sp6", "so_luong", 200));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNotNull(facts);
        List<AnomalyDto> anomalies = facts.getAnomalies();
        assertNotNull(anomalies);
        assertFalse(anomalies.isEmpty());
        assertEquals("sp6", anomalies.get(0).getLabel());
        assertEquals(200.0, anomalies.get(0).getValue());
        assertEquals("cao bất thường", anomalies.get(0).getDirection());
    }

    @Test
    void analyze_shouldReturnEmptyAnomalies_whenFewerThanThreeDataPoints() {
        // Can toi thieu vai diem du lieu thi stddev moi co y nghia thong ke.
        List<String> columns = List.of("khu_vuc", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                row("khu_vuc", "A", "doanh_thu", 10),
                row("khu_vuc", "B", "doanh_thu", 1000));

        DataInsightFacts facts = analyzer.analyze(columns, rows);

        assertNotNull(facts);
        assertNotNull(facts.getAnomalies());
        assertTrue(facts.getAnomalies().isEmpty());
    }
}