package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PasswordResetRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final int maxRequests;

    public PasswordResetRateLimiter(
            @Value("${app.password-reset.max-requests-per-hour:3}") int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public void check(String email, String remoteAddress) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        register("email:" + normalizedEmail);
        register("ip:" + remoteAddress);
    }

    private void register(String key) {
        Instant cutoff = Instant.now().minus(WINDOW);
        Deque<Instant> queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) {
                queue.removeFirst();
            }
            if (queue.size() >= maxRequests) {
                throw new RateLimitExceededException(
                        "Bạn đã yêu cầu đặt lại mật khẩu quá nhiều lần. Vui lòng thử lại sau."
                );
            }
            queue.addLast(Instant.now());
        }
    }
}
