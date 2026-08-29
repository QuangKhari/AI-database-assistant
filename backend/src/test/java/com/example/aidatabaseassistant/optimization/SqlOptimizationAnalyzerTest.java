package com.example.aidatabaseassistant.optimization;

import com.example.aidatabaseassistant.dto.IndexSuggestionDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlOptimizationAnalyzerTest {

    private final SqlOptimizationAnalyzer analyzer = new SqlOptimizationAnalyzer();

    private Map<String, Object> explainRow(Integer id, String table, String type,
                                           String possibleKeys, String key, Long rows, String extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("select_type", "SIMPLE");
        row.put("table", table);
        row.put("type", type);
        row.put("possible_keys", possibleKeys);
        row.put("key", key);
        row.put("rows", rows);
        row.put("Extra", extra);
        return row;
    }

    @Test
    void analyze_shouldDetectFullTableScan_andSuggestIndex_onUnindexedWhereColumn() {
        String sql = "SELECT * FROM orders WHERE status = 'pending'";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "orders", "ALL", null, null, 5000L, null));
        Map<String, Set<String>> indexed = Map.of("orders", Set.of("id"));

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(1, result.getIssues().size());
        assertEquals("HIGH", result.getIssues().get(0).getSeverity());
        assertTrue(result.getIssues().get(0).getDescription().contains("orders"));

        assertEquals(1, result.getSuggestions().size());
        IndexSuggestionDto suggestion = result.getSuggestions().get(0);
        assertEquals("orders", suggestion.getTable());
        assertTrue(suggestion.getColumns().contains("status"));
        assertTrue(suggestion.getCreateIndexSql().startsWith("CREATE INDEX"));
    }

    @Test
    void analyze_shouldNotSuggestIndex_whenColumnAlreadyIndexed() {
        String sql = "SELECT * FROM orders WHERE status = 'pending'";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "orders", "ALL", null, null, 50L, null));
        Map<String, Set<String>> indexed = Map.of("orders", Set.of("status"));

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(1, result.getIssues().size());
        assertEquals("MEDIUM", result.getIssues().get(0).getSeverity()); // rows < threshold
        assertTrue(result.getSuggestions().isEmpty());
    }

    @Test
    void analyze_shouldDetectFilesortAndTemporary_andSuggestIndexOnGroupOrderColumn() {
        String sql = "SELECT category, COUNT(*) FROM products GROUP BY category ORDER BY category";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "products", "index", null, "PRIMARY", 200L,
                        "Using temporary; Using filesort"));
        Map<String, Set<String>> indexed = Map.of("products", Set.of());

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(2, result.getIssues().size());
        assertTrue(result.getIssues().stream().anyMatch(i -> i.getDescription().contains("filesort")));
        assertTrue(result.getIssues().stream().anyMatch(i -> i.getDescription().contains("bảng tạm")));

        assertEquals(1, result.getSuggestions().size());
        assertTrue(result.getSuggestions().get(0).getColumns().contains("category"));
    }

    @Test
    void analyze_shouldReturnNoIssues_whenQueryAlreadyOptimized() {
        String sql = "SELECT id FROM customers WHERE id = 5";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "customers", "const", "PRIMARY", "PRIMARY", 1L, null));
        Map<String, Set<String>> indexed = Map.of("customers", Set.of("id"));

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertTrue(result.getIssues().isEmpty());
        assertTrue(result.getSuggestions().isEmpty());
    }

    @Test
    void analyze_shouldResolveAliasToRealTableName_inJoinQuery() {
        // EXPLAIN cua MySQL tra ve "table" la ALIAS neu cau lenh co dat alias,
        // khong phai ten bang that - analyzer phai quy doi lai ve ten that.
        String sql = "SELECT o.id FROM orders o JOIN customers c ON o.customer_id = c.id "
                + "WHERE c.city = 'Hanoi'";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "o", "ALL", null, null, 3000L, null),
                explainRow(2, "c", "ALL", null, null, 2000L, null));
        Map<String, Set<String>> indexed = Map.of("customers", Set.of(), "orders", Set.of());

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertTrue(result.getIssues().stream().anyMatch(i -> i.getTable().equals("customers")));
        assertTrue(result.getIssues().stream().anyMatch(i -> i.getTable().equals("orders")));

        Optional<IndexSuggestionDto> customerSuggestion = result.getSuggestions().stream()
                .filter(s -> s.getTable().equals("customers"))
                .findFirst();
        assertTrue(customerSuggestion.isPresent());
    }

    @Test
    void analyze_shouldNotAttributeColumn_whenAmbiguousAndMultipleTables() {
        // Cot khong co tien to bang trong cau co nhieu bang -> khong doan,
        // bo qua an toan thay vi gan nham bang.
        String sql = "SELECT o.id FROM orders o, customers c WHERE status = 'pending'";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "orders", "ALL", null, null, 5000L, null),
                explainRow(2, "customers", "ALL", null, null, 5000L, null));
        Map<String, Set<String>> indexed = Map.of("orders", Set.of(), "customers", Set.of());

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        // Van co issue full scan cho ca 2 bang, nhung KHONG co suggestion vi
        // "status" khong xac dinh duoc thuoc bang nao.
        assertEquals(2, result.getIssues().size());
        assertTrue(result.getSuggestions().isEmpty());
    }

    @Test
    void analyze_shouldNotSuggestIndex_whenColumnAlreadyIndexed_andExplainUsesAlias() {
        // Tai hien dung bug thuc te: EXPLAIN tra ve alias "c" trong cot table,
        // nhung indexedColumnsByTable phai duoc key bang TEN BANG THAT.
        String sql = "SELECT o.id, c.full_name FROM orders o JOIN customers c ON o.customer_id = c.id";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "c", "ALL", "PRIMARY", null, 10L, null),
                explainRow(2, "o", "ref", "customer_id", "customer_id", 2L, "Using index"));

        // "id" DA co index (PRIMARY) tren customers - key phai la ten bang that
        Map<String, Set<String>> indexed = Map.of(
                "customers", Set.of("id"),
                "orders", Set.of("customer_id"));

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(1, result.getIssues().size()); // van con issue full-scan (dung, vi day la bang driving cua JOIN)
        assertTrue(result.getSuggestions().isEmpty()); // nhung KHONG duoc goi y them index cho "id"
    }

    @Test
    void analyze_shouldNotSuggestIndex_onAggregateAlias_inOrderBy() {
        // total_orders la alias cua COUNT(*), KHONG PHAI cot that -> tuyet doi
        // khong duoc goi y CREATE INDEX tren no.
        String sql = "SELECT category, COUNT(*) AS total_orders FROM orders "
                + "GROUP BY category ORDER BY total_orders DESC";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "orders", "index", null, "PRIMARY", 500L,
                        "Using temporary; Using filesort"));
        Map<String, Set<String>> indexed = Map.of("orders", Set.of());

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(2, result.getIssues().size()); // filesort + temporary van duoc bao cao

        // Chi duoc goi y index cho "category" (GROUP BY - cot that), TUYET DOI
        // khong duoc co goi y nao chua "total_orders".
        assertEquals(1, result.getSuggestions().size());
        assertTrue(result.getSuggestions().get(0).getColumns().contains("category"));
        assertFalse(result.getSuggestions().stream()
                .anyMatch(s -> s.getColumns().contains("total_orders")));
    }

    @Test
    void analyze_shouldResolveOrderByAlias_toRealColumnName() {
        // "name" la alias truc tiep cua cot that "full_name" -> phai goi y
        // index tren "full_name", khong phai "name".
        String sql = "SELECT full_name AS name FROM customers ORDER BY name";
        List<Map<String, Object>> explain = List.of(
                explainRow(1, "customers", "index", null, "PRIMARY", 300L, "Using filesort"));
        Map<String, Set<String>> indexed = Map.of("customers", Set.of());

        SqlOptimizationResult result = analyzer.analyze(sql, explain, indexed);

        assertEquals(1, result.getSuggestions().size());
        assertTrue(result.getSuggestions().get(0).getColumns().contains("full_name"));
        assertFalse(result.getSuggestions().get(0).getColumns().contains("name"));
    }
}