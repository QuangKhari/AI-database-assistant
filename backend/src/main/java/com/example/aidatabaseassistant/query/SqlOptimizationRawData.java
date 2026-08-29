package com.example.aidatabaseassistant.query;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Du lieu THAT lay truc tiep tu JDBC (EXPLAIN + DatabaseMetaData) - dua
 * cho SqlOptimizationAnalyzer xu ly. "error" khac null nghia la khong the
 * chay EXPLAIN (vi du sai ket noi, SQL loi khi EXPLAIN dù da qua validate).
 */
@Getter
@AllArgsConstructor
public class SqlOptimizationRawData {
    private List<Map<String, Object>> explainRows;
    private Map<String, Set<String>> indexedColumnsByTable; // key: ten bang viet thuong
    private String error;
}