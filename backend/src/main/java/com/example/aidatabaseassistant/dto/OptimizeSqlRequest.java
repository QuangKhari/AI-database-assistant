package com.example.aidatabaseassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OptimizeSqlRequest {

    @NotBlank
    private String sql;

    // Bat buoc (khac ExplainSqlRequest): can connection that de mo JDBC
    // chay EXPLAIN va doc index that tu MySQL, khong the tuy chon.
    @NotNull
    private Long databaseConnectionId;
}