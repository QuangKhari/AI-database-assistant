package com.example.aidatabaseassistant.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class LLMClient {

    private static final Logger log =
            LoggerFactory.getLogger(LLMClient.class);

    // Goi Gemini toi da 1 lan dau + 2 lan retry (network timeout, 429, 5xx),
    // KHONG retry loi 4xx khac (vi du 400 - prompt sai, retry lai cung sai).
    private static final int MAX_LLM_RETRIES = 2;
    private static final long[] BACKOFF_MS = {500, 1500};

    private final RestTemplate restTemplate;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    @Value("${gemini.embedding.api.url}")
    private String embeddingApiUrl;

    @Value("${gemini.embedding.model:text-embedding-001}")
    private String embeddingModel;

    public LLMClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public String generateResponse(String prompt) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "topP", 0.8,
                        "topK", 20,
                        "maxOutputTokens", 512
                )
        );

        String url = apiUrl;

        log.info(
                "Gọi Gemini API, độ dài prompt: {} ký tự",
                prompt.length()
        );

        log.debug("Prompt gửi Gemini:\n{}", prompt);

        long start = System.currentTimeMillis();

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                callWithRetry("generateResponse",
                        () -> restTemplate.postForEntity(url, entity, Map.class));

        Map<String, Object> responseBody =
                response.getBody();

        if (responseBody == null) {
            throw new RuntimeException(
                    "Gemini API không trả về dữ liệu"
            );
        }

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>)
                        responseBody.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException(
                    "Gemini không có candidate"
            );
        }

        Map<String, Object> content =
                (Map<String, Object>)
                        candidates.get(0).get("content");

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>)
                        content.get("parts");

        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException(
                    "Gemini không trả về nội dung"
            );
        }

        String answer =
                (String) parts.get(0).get("text");

        long elapsedMs =
                System.currentTimeMillis() - start;

        log.info(
                "Gemini API trả lời trong {} ms, độ dài response: {} ký tự",
                elapsedMs,
                answer.length()
        );

        log.debug("Response từ Gemini:\n{}", answer);

        return answer;
    }

    @SuppressWarnings("unchecked")
    public float[] generateEmbedding(String text) {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Nội dung embedding không được rỗng"
            );
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", text)
                        )
                )
        );

        String url = embeddingApiUrl;

        log.info(
                "Gọi Gemini Embedding API, model={}, độ dài text={} ký tự",
                embeddingModel,
                text.length()
        );

        long start = System.currentTimeMillis();

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                callWithRetry("generateEmbedding",
                        () -> restTemplate.postForEntity(embeddingApiUrl, entity, Map.class));

        Map<String, Object> responseBody = response.getBody();

        if (responseBody == null) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về dữ liệu"
            );
        }

        Map<String, Object> embedding =
                (Map<String, Object>) responseBody.get("embedding");

        if (embedding == null) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về trường embedding"
            );
        }

        List<Double> values =
                (List<Double>) embedding.get("values");

        if (values == null || values.isEmpty()) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về vector hợp lệ"
            );
        }

        float[] vector = new float[values.size()];

        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }

        long elapsedMs = System.currentTimeMillis() - start;

        log.info(
                "Gemini Embedding hoàn thành trong {} ms, dimension={}",
                elapsedMs,
                vector.length
        );

        return vector;
    }

    /**
     * Goi Gemini API voi retry cho cac loi CO THE THU LAI DUOC:
     *  - ResourceAccessException: connect/read timeout, mat ket noi mang.
     *  - HttpServerErrorException (5xx): loi phia Gemini, thuong tam thoi.
     *  - HttpStatusCodeException voi status 429: rate limit, cho backoff roi thu lai.
     *
     * KHONG retry cac loi 4xx khac (400 prompt sai, 401/403 sai API key...)
     * vi thu lai se cho ket qua giong het lan truoc, chi ton them thoi gian/API quota.
     *
     * Tong so lan goi toi da = 1 (lan dau) + MAX_LLM_RETRIES (lan thu lai).
     */
    private <T> T callWithRetry(String operationName, Supplier<T> call) {
        RestClientException lastError = null;

        for (int attempt = 0; attempt <= MAX_LLM_RETRIES; attempt++) {

            try {
                return call.get();

            } catch (ResourceAccessException e) {
                lastError = e;
                log.warn("[{}] Gemini API network/timeout error (attempt {}/{}): {}",
                        operationName, attempt + 1, MAX_LLM_RETRIES + 1, e.getMessage());

            } catch (HttpServerErrorException e) {
                lastError = e;
                log.warn("[{}] Gemini API 5xx error (attempt {}/{}): {}",
                        operationName, attempt + 1, MAX_LLM_RETRIES + 1, e.getStatusCode());

            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429) {
                    lastError = e;
                    log.warn("[{}] Gemini API rate limited - 429 (attempt {}/{})",
                            operationName, attempt + 1, MAX_LLM_RETRIES + 1);
                } else {
                    // 4xx khac (400/401/403...) - khong co ich gi khi retry, nem ra ngay.
                    throw e;
                }
            }

            if (attempt < MAX_LLM_RETRIES) {
                sleepQuietly(BACKOFF_MS[attempt]);
            }
        }

        log.error("[{}] Gemini API thất bại sau {} lần thử", operationName, MAX_LLM_RETRIES + 1);
        throw new RuntimeException(
                "Không thể kết nối tới dịch vụ AI, vui lòng thử lại sau ít phút.",
                lastError
        );
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}