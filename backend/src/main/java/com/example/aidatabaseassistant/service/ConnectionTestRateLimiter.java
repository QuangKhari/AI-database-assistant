package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.config.TargetDatabaseProperties;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConnectionTestRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final int maxTestsPerMinute;

    public ConnectionTestRateLimiter(TargetDatabaseProperties properties) {
        this.maxTestsPerMinute = properties.getMaxTestsPerMinute();
    }

    public void check(String username) {
        String key = username.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) {
                queue.removeFirst();
            }
            if (queue.size() >= maxTestsPerMinute) {
                throw new RateLimitExceededException(
                        "Bạn đã kiểm tra connection quá nhiều lần. Vui lòng đợi một phút rồi thử lại."
                );
            }
            queue.addLast(now);
        }
    }
}
