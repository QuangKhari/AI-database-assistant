package com.example.aidatabaseassistant.config;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    /*
     * RestTemplate chính:
     *
     * Dùng cho:
     * - SQL generation
     * - Embedding
     *
     * Các request này vẫn được phép retry khi:
     * - network timeout
     * - 429
     * - 5xx
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /*
     * RestTemplate riêng cho các tính năng AI bổ sung:
     *
     * - Summary
     *
     * Đây KHÔNG phải luồng chính.
     * Nếu Gemini chậm thì không được phép làm /execute chậm thêm
     * hàng chục giây.
     */
    private static final Duration OPTIONAL_AI_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private static final Duration OPTIONAL_AI_READ_TIMEOUT = Duration.ofSeconds(8);

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    @Bean(name = "optionalAiRestTemplate")
    public RestTemplate optionalAiRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(OPTIONAL_AI_CONNECT_TIMEOUT)
                .readTimeout(OPTIONAL_AI_READ_TIMEOUT)
                .build();
    }

    @Bean
    public Executor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("sse-query-");
        executor.initialize();

        return executor;
    }
}