package com.example.aidatabaseassistant.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.query")
public class QueryExecutionProperties {

    private static final int HARD_MAX_TIMEOUT_SECONDS = 30;
    private static final int HARD_MAX_ROWS = 500;

    private int defaultTimeoutSeconds = 20;
    private int maxTimeoutSeconds = 30;
    private int maxRows = 500;

    @PostConstruct
    void validate() {
        if (maxTimeoutSeconds < 1 || maxTimeoutSeconds > HARD_MAX_TIMEOUT_SECONDS) {
            throw new IllegalStateException("Query timeout tối đa phải nằm trong khoảng 1-30 giây");
        }
        if (defaultTimeoutSeconds < 1 || defaultTimeoutSeconds > maxTimeoutSeconds) {
            throw new IllegalStateException("Query timeout mặc định không được vượt quá giới hạn tối đa");
        }
        if (maxRows < 1 || maxRows > HARD_MAX_ROWS) {
            throw new IllegalStateException("Số dòng kết quả tối đa phải nằm trong khoảng 1-500");
        }
    }
}
