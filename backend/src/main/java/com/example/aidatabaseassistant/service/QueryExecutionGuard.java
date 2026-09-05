package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.exception.QueryAlreadyRunningException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Component
public class QueryExecutionGuard {

    private final ConcurrentMap<String, Semaphore> userSlots = new ConcurrentHashMap<>();

    public Lease acquire(String username) {
        Semaphore slot = userSlots.computeIfAbsent(username.toLowerCase(), ignored -> new Semaphore(1));
        if (!slot.tryAcquire()) {
            throw new QueryAlreadyRunningException(
                    "Tài khoản đang có một query khác chạy. Hãy đợi query đó hoàn tất.");
        }
        return slot::release;
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
