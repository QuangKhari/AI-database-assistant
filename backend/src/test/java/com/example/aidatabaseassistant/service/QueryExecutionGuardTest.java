package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.exception.QueryAlreadyRunningException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryExecutionGuardTest {

    @Test
    void allowsOnlyOneQueryPerUserUntilLeaseIsReleased() {
        QueryExecutionGuard guard = new QueryExecutionGuard();
        QueryExecutionGuard.Lease lease = guard.acquire("Student");

        assertThatThrownBy(() -> guard.acquire("student"))
                .isInstanceOf(QueryAlreadyRunningException.class);

        lease.close();
        assertThatCode(() -> guard.acquire("STUDENT").close()).doesNotThrowAnyException();
    }
}
