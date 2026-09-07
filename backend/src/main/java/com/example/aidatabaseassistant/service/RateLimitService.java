package com.example.aidatabaseassistant.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class RateLimitService {

    private final ConcurrentMap<String, Bucket> aiBuckets = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();

    public boolean tryConsume(String username) {
        Bucket bucket = aiBuckets.computeIfAbsent(username, k -> newAiBucket());
        return bucket.tryConsume(1);
    }

    public boolean tryConsumeAuthAction(String key) {
        Bucket bucket = authBuckets.computeIfAbsent(key, k -> newAuthBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newAiBucket() {
        Bandwidth limit = Bandwidth.classic(10, Refill.greedy(10, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket newAuthBucket() {
        Bandwidth limit = Bandwidth.classic(5, Refill.greedy(5, Duration.ofMinutes(15)));
        return Bucket.builder().addLimit(limit).build();
    }
}