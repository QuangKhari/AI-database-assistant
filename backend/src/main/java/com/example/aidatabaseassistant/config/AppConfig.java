package com.example.aidatabaseassistant.config;

import java.util.concurrent.Executor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AppConfig {
    // TRUOC DAY: new RestTemplate() mac dinh KHONG co timeout -> neu
    // Gemini bi treo hoac mang cham, thread xu ly request HTTP se bi
    // block vo thoi han, co the lam can kiet thread pool cua Spring Boot
    // khi nhieu user goi cung luc (tuong tu ly do QueryExecutor da phai
    // set CONNECT_TIMEOUT_MS / SOCKET_TIMEOUT_MS cho JDBC).
    //
    // CONNECT_TIMEOUT: thoi gian toi da cho thiet lap ket noi TCP toi
    // Gemini. READ_TIMEOUT: thoi gian toi da cho tu luc gui request den
    // luc nhan duoc response day du (Gemini sinh SQL/tom tat co the mat
    // vai giay, nen de du hon connect timeout).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
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