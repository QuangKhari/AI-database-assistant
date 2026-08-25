package com.example.aidatabaseassistant.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.target-db")
public class TargetDatabaseProperties {

    private static final int HARD_MAX_CONNECTION_TIMEOUT_MS = 30_000;

    private int connectionTimeoutMs = 20_000;
    private int maxConnectionTimeoutMs = 30_000;
    private int socketTimeoutMs = 30_000;
    private int maxConnectionsPerUser = 5;
    private int maxTestsPerMinute = 10;
    private boolean allowPrivateHosts = true;

    @PostConstruct
    void validate() {
        if (maxConnectionTimeoutMs < 1_000 || maxConnectionTimeoutMs > HARD_MAX_CONNECTION_TIMEOUT_MS) {
            throw new IllegalStateException("Target DB connection timeout tối đa phải nằm trong khoảng 1-30 giây");
        }
        if (connectionTimeoutMs < 1_000 || connectionTimeoutMs > maxConnectionTimeoutMs) {
            throw new IllegalStateException("Target DB connection timeout mặc định không được vượt quá giới hạn tối đa");
        }
        if (socketTimeoutMs < connectionTimeoutMs || socketTimeoutMs > HARD_MAX_CONNECTION_TIMEOUT_MS) {
            throw new IllegalStateException("Target DB socket timeout phải từ connection timeout đến 30 giây");
        }
        if (maxConnectionsPerUser < 1 || maxConnectionsPerUser > 20) {
            throw new IllegalStateException("Số connection tối đa mỗi user phải nằm trong khoảng 1-20");
        }
        if (maxTestsPerMinute < 1 || maxTestsPerMinute > 100) {
            throw new IllegalStateException("Giới hạn test connection phải nằm trong khoảng 1-100 lần/phút");
        }
    }
}
