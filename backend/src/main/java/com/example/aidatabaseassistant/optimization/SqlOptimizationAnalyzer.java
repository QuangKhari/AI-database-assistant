package com.example.aidatabaseassistant.optimization;

import com.example.aidatabaseassistant.dto.ExplainRowDto;
import com.example.aidatabaseassistant.dto.IndexSuggestionDto;
import com.example.aidatabaseassistant.dto.OptimizationIssueDto;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phan tich EXPLAIN that cua MySQL + danh sach index that (lay tu
 * DatabaseMetaData) de phat hien:
 *
 * 1. Full table scan (EXPLAIN.type = "ALL")
 * 2. Can sap xep thu cong / bang tam (EXPLAIN.Extra chua "Using filesort"
 *    hoac "Using temporary")
 * 3. Cot dang dung trong WHERE/JOIN (cho van de #1) hoac GROUP BY/ORDER BY
 *    (cho van de #2) ma CHUA co index -> goi y CREATE INDEX
 *
 * DAY LA THUAT TOAN THUAN, KHONG GOI AI - giong het tinh than cua
 * DataInsightAnalyzer va ChartTypeClassifier trong du an. SqlOptimizationService
 * se dung Gemini CHI de viet lai cac phat hien nay thanh van phong tu nhien,
 * TUYET DOI khong de AI tu bia them van de hoac index nao khac.
 *
 * GIOI HAN CO CHU DICH (uu tien AN TOAN - tha bo sot con hon goi y sai):
 * - Chi doc dung cu phap EXPLAIN cua MySQL (id, select_type, table, type,
 *   possible_keys, key, rows, Extra).
 * - Voi cau co nhieu bang (JOIN) ma mot cot trong WHERE/ORDER BY KHONG ghi
 *   ro tien to bang (vi du "WHERE status = 'x'" thay vi "o.status = 'x'"),
 *   KHONG doan bang de tranh gan nham cot cho bang sai; cot do bi bo qua,
 *   khong dua vao goi y index.
 * - Voi UNION / SQL qua phuc tap khong parse duoc bang JSqlParser thanh
 *   PlainSelect don, van tra ve issues tu EXPLAIN nhung KHONG co goi y cot
 *   cu the (an toan hon la doan sai).
 * - Chi tinh la "da co index" khi cot xuat hien trong BAT KY index nao cua
 *   bang do (ke ca la cot khong dau tien trong index composite) - cach tiep
 *   can bao thu, giam goi y trung lap hon la toi uu tuyet doi ve thu tu cot.
 */
@Component
public class SqlOptimizationAnalyzer {

    private static final long LARGE_ROWS_THRESHOLD = 1000L;
    private static final int MAX_INDEX_COLUMNS = 3;

    public SqlOptimizationResult analyze(
            String sql,
            List<Map<String, Object>> rawExplainRows,
            Map<String, Set<String>> indexedColumnsByTable
    ) {
        List<ExplainRowDto> explainRows = toExplainRowDtos(rawExplainRows);
        QueryColumnInfo columnInfo = extractColumnInfo(sql);

        List<OptimizationIssueDto> issues = new ArrayList<>();
        List<IndexSuggestionDto> suggestions = new ArrayList<>();
        Set<String> suggestedKeys = new HashSet<>();

        for (ExplainRowDto row : explainRows) {
            if (row.getTable() == null) {
                // Vi du dong tong hop cua UNION RESULT / <derived...> -
                // khong gan voi 1 bang cu the nao.
                continue;
            }

            String realTable = columnInfo.resolveToRealTable(row.getTable());

            boolean isFullScan = "ALL".equalsIgnoreCase(row.getType());
            String extraLower = row.getExtra() == null
                    ? "" : row.getExtra().toLowerCase(Locale.ROOT);
            boolean usesFilesort = extraLower.contains("using filesort");
            boolean usesTemporary = extraLower.contains("using temporary");
            boolean isLarge = row.getRows() != null && row.getRows() >= LARGE_ROWS_THRESHOLD;

            if (isFullScan) {
                issues.add(new OptimizationIssueDto(
                        isLarge ? "HIGH" : "MEDIUM",
                        realTable,
                        "Bảng '" + realTable + "' đang bị quét toàn bộ (full table scan)"
                                + (row.getRows() != null
                                ? ", ước tính khoảng " + row.getRows() + " dòng."
                                : ".")
                ));

                addSuggestion(
                        realTable,
                        columnInfo.getWhereJoinColumns(realTable),
                        indexedColumnsByTable,
                        "cột đang dùng trong WHERE/JOIN nhưng chưa có index phù hợp",
                        suggestions, suggestedKeys
                );
            }

            if (usesFilesort) {
                issues.add(new OptimizationIssueDto(
                        "MEDIUM", realTable,
                        "Bảng '" + realTable + "' cần sắp xếp thủ công (filesort) khi thực hiện ORDER BY."
                ));

                addSuggestion(
                        realTable,
                        columnInfo.getOrderGroupColumns(realTable),
                        indexedColumnsByTable,
                        "cột dùng trong ORDER BY/GROUP BY chưa có index, có thể loại bỏ filesort",
                        suggestions, suggestedKeys
                );
            }

            if (usesTemporary) {
                issues.add(new OptimizationIssueDto(
                        "MEDIUM", realTable,
                        "Truy vấn cần tạo bảng tạm (temporary table) để xử lý GROUP BY/DISTINCT/ORDER BY "
                                + "trên bảng '" + realTable + "'."
                ));

                addSuggestion(
                        realTable,
                        columnInfo.getOrderGroupColumns(realTable),
                        indexedColumnsByTable,
                        "cột dùng trong GROUP BY/ORDER BY chưa có index, có thể tránh tạo bảng tạm",
                        suggestions, suggestedKeys
                );
            }
        }

        return new SqlOptimizationResult(explainRows, issues, suggestions);
    }

    private void addSuggestion(
            String table,
            Set<String> candidateColumns,
            Map<String, Set<String>> indexedColumnsByTable,
            String reason,
            List<IndexSuggestionDto> suggestions,
            Set<String> suggestedKeys
    ) {
        if (candidateColumns == null || candidateColumns.isEmpty()) {
            return;
        }

        Set<String> indexed = indexedColumnsByTable.getOrDefault(
                table.toLowerCase(Locale.ROOT), Set.of());

        List<String> missing = new ArrayList<>();
        for (String col : candidateColumns) {
            if (!indexed.contains(col.toLowerCase(Locale.ROOT))) {
                missing.add(col);
            }
        }

        if (missing.isEmpty()) {
            return;
        }

        List<String> chosen = missing.size() > MAX_INDEX_COLUMNS
                ? missing.subList(0, MAX_INDEX_COLUMNS)
                : missing;

        String dedupKey = table.toLowerCase(Locale.ROOT) + "::"
                + String.join(",", chosen).toLowerCase(Locale.ROOT);

        if (!suggestedKeys.add(dedupKey)) {
            return;
        }

        String indexName = "idx_" + table + "_" + String.join("_", chosen);
        String ddl = "CREATE INDEX " + indexName + " ON " + table
                + " (" + String.join(", ", chosen) + ");";

        suggestions.add(new IndexSuggestionDto(table, chosen, reason, ddl));
    }

    private List<ExplainRowDto> toExplainRowDtos(List<Map<String, Object>> rawRows) {
        List<ExplainRowDto> result = new ArrayList<>();
        if (rawRows == null) {
            return result;
        }
        for (Map<String, Object> row : rawRows) {
            result.add(new ExplainRowDto(
                    toInteger(field(row, "id")),
                    toStr(field(row, "select_type")),
                    toStr(field(row, "table")),
                    toStr(field(row, "type")),
                    toStr(field(row, "possible_keys")),
                    toStr(field(row, "key")),
                    toLong(field(row, "rows")),
                    toStr(field(row, "Extra"))
            ));
        }
        return result;
    }

    // Doc field khong phan biet hoa/thuong: MySQL Connector/J co the tra ve
    // ten cot voi case khac nhau tuy version driver (vi du "Extra" luon viet
    // hoa chu E, cac cot khac thuong viet thuong).
    private Object field(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Parse SQL bang JSqlParser de biet: alias -> ten bang that, va cot nao
     * (thuoc bang nao) dang duoc dung trong WHERE/JOIN...ON so voi
     * GROUP BY/ORDER BY - hai nhom nay ung voi 2 loai van de khac nhau
     * (full scan vs filesort/bang tam) nen can tach rieng.
     */

    @SuppressWarnings("unchecked")
    private QueryColumnInfo extractColumnInfo(String sql) {
        Map<String, String> aliasToTable = new HashMap<>();
        Map<String, Set<String>> whereJoinColumns = new HashMap<>();
        Map<String, Set<String>> orderGroupColumns = new HashMap<>();

        try {
            net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(sql);

            if (!(statement instanceof PlainSelect plainSelect)) {
                // UNION / cau lenh phuc tap khac PlainSelect don: bo qua
                // goi y cot, van con issues tu EXPLAIN o buoc analyze().
                return new QueryColumnInfo(aliasToTable, whereJoinColumns, orderGroupColumns);
            }

            registerFromItem(plainSelect.getFromItem(), aliasToTable);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    registerFromItem(join.getRightItem(), aliasToTable);
                }
            }

            boolean singleTable = aliasToTable.values().stream().distinct().count() == 1;
            String onlyTable = singleTable ? aliasToTable.values().iterator().next() : null;

            List<Column> whereJoinCols = new ArrayList<>();
            collectColumns(plainSelect.getWhere(), whereJoinCols);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    collectColumns(join.getOnExpression(), whereJoinCols);
                }
            }
            attributeColumns(whereJoinCols, aliasToTable, onlyTable, whereJoinColumns);

            List<Column> orderGroupCols = new ArrayList<>();
            GroupByElement groupBy = plainSelect.getGroupBy();
            if (groupBy != null && groupBy.getGroupByExpressionList() != null) {
                // getGroupByExpressionList() tra ve KIEU RAW ExpressionList (khong co
                // generic <Expression>) trong JSqlParser 4.9 - phai gan qua bien
                // List<Expression> trung gian truoc khi for-each, neu khong compiler
                // se coi phan tu la Object thay vi Expression.
                List<Expression> groupByExpressions = groupBy.getGroupByExpressionList();
                for (Expression expr : groupByExpressions) {
                    collectColumns(expr, orderGroupCols);
                }
            }
            if (plainSelect.getOrderByElements() != null) {
                for (OrderByElement ob : plainSelect.getOrderByElements()) {
                    collectColumns(ob.getExpression(), orderGroupCols);
                }
            }
            attributeColumns(orderGroupCols, aliasToTable, onlyTable, orderGroupColumns);

        } catch (Exception e) {
            // SQL khong parse duoc theo cu phap JSqlParser mong doi (hiem
            // gap vi SQL da qua QueryValidator truoc do) - bo qua goi y cot,
            // an toan hon la nem loi lam vo hieu tinh nang chinh.
            return new QueryColumnInfo(new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        return new QueryColumnInfo(aliasToTable, whereJoinColumns, orderGroupColumns);
    }

    private void attributeColumns(
            List<Column> columns,
            Map<String, String> aliasToTable,
            String onlyTable,
            Map<String, Set<String>> target
    ) {
        for (Column col : columns) {
            String table = resolveColumnTable(col, aliasToTable, onlyTable);
            if (table == null) {
                continue;
            }
            target.computeIfAbsent(table, k -> new java.util.LinkedHashSet<>())
                    .add(col.getColumnName());
        }
    }

    private String resolveColumnTable(Column col, Map<String, String> aliasToTable, String onlyTable) {
        Table qualifier = col.getTable();
        if (qualifier != null && qualifier.getName() != null) {
            String alias = qualifier.getName().toLowerCase(Locale.ROOT);
            return aliasToTable.getOrDefault(alias, qualifier.getName());
        }
        return onlyTable;
    }

    private void registerFromItem(FromItem item, Map<String, String> aliasToTable) {
        if (!(item instanceof Table table)) {
            return;
        }
        String name = table.getName();
        aliasToTable.put(name.toLowerCase(Locale.ROOT), name);
        if (table.getAlias() != null && table.getAlias().getName() != null) {
            aliasToTable.put(table.getAlias().getName().toLowerCase(Locale.ROOT), name);
        }
    }

    private void collectColumns(Expression expr, List<Column> target) {
        if (expr == null) {
            return;
        }
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                target.add(column);
            }
        });
    }

    private Integer toInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String toStr(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * aliasToTable: key la alias/ten bang viet thuong, value la ten bang
     * that (dung de sinh CREATE INDEX). whereJoin/orderGroup: key la ten
     * bang that, value la tap hop ten cot lien quan.
     */
    private static class QueryColumnInfo {
        private final Map<String, String> aliasToTable;
        private final Map<String, Set<String>> whereJoinColumns;
        private final Map<String, Set<String>> orderGroupColumns;

        QueryColumnInfo(Map<String, String> aliasToTable,
                        Map<String, Set<String>> whereJoinColumns,
                        Map<String, Set<String>> orderGroupColumns) {
            this.aliasToTable = aliasToTable;
            this.whereJoinColumns = whereJoinColumns;
            this.orderGroupColumns = orderGroupColumns;
        }

        String resolveToRealTable(String tableOrAlias) {
            if (tableOrAlias == null) return null;
            String real = aliasToTable.get(tableOrAlias.toLowerCase(Locale.ROOT));
            return real != null ? real : tableOrAlias;
        }

        Set<String> getWhereJoinColumns(String realTable) {
            return whereJoinColumns.getOrDefault(realTable, Set.of());
        }

        Set<String> getOrderGroupColumns(String realTable) {
            return orderGroupColumns.getOrDefault(realTable, Set.of());
        }
    }
}