package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.exception.TargetDatabaseConnectionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionProtectionTest {

    @Test
    void limitsConnectionTestsPerUser() {
        TargetDatabaseProperties properties = new TargetDatabaseProperties();
        properties.setMaxTestsPerMinute(2);
        ConnectionTestRateLimiter limiter = new ConnectionTestRateLimiter(properties);

        limiter.check("Student");
        limiter.check("student");

        assertThatThrownBy(() -> limiter.check("STUDENT"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void productionPolicyBlocksLocalAndPrivateTargetHosts() {
        TargetDatabaseProperties properties = new TargetDatabaseProperties();
        properties.setAllowPrivateHosts(false);
        TargetHostValidator validator = new TargetHostValidator(properties);

        assertThatThrownBy(() -> validator.validate("127.0.0.1"))
                .isInstanceOf(TargetDatabaseConnectionException.class)
                .extracting("code")
                .isEqualTo("PRIVATE_HOST_BLOCKED");
    }
}
