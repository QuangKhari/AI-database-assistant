package com.example.aidatabaseassistant.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /*
     * 1 lần gọi đầu + 2 lần retry
     */
    private static final int MAX_LLM_RETRIES = 1;

    private static final long[] BACKOFF_MS = {800};

    /*
     * RestTemplate dùng cho SQL generation + embedding.
     */
    private final RestTemplate restTemplate;

    /*
     * RestTemplate dùng riêng cho AI feature bổ sung:
     *
     * - Summary
     *
     * Timeout ngắn và KHÔNG retry.
     */
    private final RestTemplate optionalAiRestTemplate;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    @Value("${gemini.embedding.api.url}")
    private String embeddingApiUrl;

    @Value("${gemini.embedding.model:text-embedding-001}")
    private String embeddingModel;

    public LLMClient(
            RestTemplate restTemplate,
            @Qualifier("optionalAiRestTemplate")
            RestTemplate optionalAiRestTemplate
    ) {
        this.restTemplate = restTemplate;
        this.optionalAiRestTemplate = optionalAiRestTemplate;
    }

    /**
     * ============================================================
     * GENERATE RESPONSE - LUỒNG CHÍNH
     * ============================================================
     *
     * Dùng cho:
     * - SQL generation
     * - các tác vụ AI quan trọng
     *
     * Có retry:
     * - timeout/network
     * - 429
     * - 5xx
     *
     * Không retry 400/401/403...
     */
    @SuppressWarnings("unchecked")
    public String generateResponse(String prompt) {

        HttpHeaders headers = buildHeaders();

        Map<String, Object> body =
                buildGenerationBody(prompt);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        log.info(
                "Gọi Gemini API, độ dài prompt: {} ký tự",
                prompt.length()
        );

        log.debug(
                "Prompt gửi Gemini:\n{}",
                prompt
        );

        long start =
                System.currentTimeMillis();

        ResponseEntity<Map> response =
                callWithRetry(
                        "generateResponse",
                        () -> restTemplate.postForEntity(
                                apiUrl,
                                entity,
                                Map.class
                        )
                );

        String answer =
                extractResponseText(response);

        long elapsedMs =
                System.currentTimeMillis() - start;

        log.info(
                "Gemini API trả lời trong {} ms, độ dài response: {} ký tự",
                elapsedMs,
                answer.length()
        );

        log.debug(
                "Response từ Gemini:\n{}",
                answer
        );

        return answer;
    }

    /**
     * ============================================================
     * OPTIONAL AI RESPONSE
     * ============================================================
     *
     * Dùng cho những tính năng KHÔNG được phép làm chậm luồng chính:
     *
     * - Summary
     *
     * Đặc điểm:
     *
     * - dùng RestTemplate riêng
     * - timeout ngắn
     * - KHÔNG retry
     *
     * Nếu Gemini chậm/lỗi -> QueryService sẽ fallback.
     */
    @SuppressWarnings("unchecked")
    public String generateOptionalResponse(String prompt) {

        HttpHeaders headers =
                buildHeaders();

        Map<String, Object> body =
                buildGenerationBody(prompt);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        log.info(
                "Gọi Gemini Optional AI API, độ dài prompt: {} ký tự",
                prompt.length()
        );

        long start =
                System.currentTimeMillis();

        try {

            ResponseEntity<Map> response =
                    optionalAiRestTemplate.postForEntity(
                            apiUrl,
                            entity,
                            Map.class
                    );

            String answer =
                    extractResponseText(response);

            long elapsedMs =
                    System.currentTimeMillis()
                            - start;

            log.info(
                    "Gemini Optional AI trả lời trong {} ms, độ dài response: {} ký tự",
                    elapsedMs,
                    answer.length()
            );

            return answer;

        } catch (ResourceAccessException e) {

            long elapsedMs =
                    System.currentTimeMillis()
                            - start;

            log.warn(
                    "Gemini Optional AI timeout/network sau {} ms: {}",
                    elapsedMs,
                    e.getMessage()
            );

            throw e;

        } catch (RestClientException e) {

            long elapsedMs =
                    System.currentTimeMillis()
                            - start;

            log.warn(
                    "Gemini Optional AI thất bại sau {} ms: {}",
                    elapsedMs,
                    e.toString()
            );

            throw e;
        }
    }

    /**
     * ============================================================
     * EMBEDDING
     * ============================================================
     */
    @SuppressWarnings("unchecked")
    public float[] generateEmbedding(String text) {

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Nội dung embedding không được rỗng"
            );
        }

        HttpHeaders headers =
                buildHeaders();

        Map<String, Object> body =
                Map.of(
                        "model",
                        "models/" + embeddingModel,

                        "content",
                        Map.of(
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                text
                                        )
                                )
                        )
                );

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(
                        body,
                        headers
                );

        log.info(
                "Gọi Gemini Embedding API, model={}, độ dài text={} ký tự",
                embeddingModel,
                text.length()
        );

        long start =
                System.currentTimeMillis();

        ResponseEntity<Map> response =
                callWithRetry(
                        "generateEmbedding",
                        () -> restTemplate.postForEntity(
                                embeddingApiUrl,
                                entity,
                                Map.class
                        )
                );

        Map<String, Object> responseBody =
                response.getBody();

        if (responseBody == null) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về dữ liệu"
            );
        }

        Map<String, Object> embedding =
                (Map<String, Object>)
                        responseBody.get("embedding");

        if (embedding == null) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về trường embedding"
            );
        }

        List<Double> values =
                (List<Double>)
                        embedding.get("values");

        if (values == null || values.isEmpty()) {
            throw new RuntimeException(
                    "Gemini Embedding API không trả về vector hợp lệ"
            );
        }

        float[] vector =
                new float[values.size()];

        for (int i = 0; i < values.size(); i++) {
            vector[i] =
                    values.get(i).floatValue();
        }

        long elapsedMs =
                System.currentTimeMillis()
                        - start;

        log.info(
                "Gemini Embedding hoàn thành trong {} ms, dimension={}",
                elapsedMs,
                vector.length
        );

        return vector;
    }

    /**
     * ============================================================
     * COMMON HELPERS
     * ============================================================
     */

    private HttpHeaders buildHeaders() {

        HttpHeaders headers =
                new HttpHeaders();

        headers.setContentType(
                MediaType.APPLICATION_JSON
        );

        headers.set(
                "x-goog-api-key",
                apiKey
        );

        return headers;
    }

    private Map<String, Object> buildGenerationBody(
            String prompt
    ) {

        return Map.of(
                "contents",
                List.of(
                        Map.of(
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                prompt
                                        )
                                )
                        )
                ),

                "generationConfig",
                Map.of(
                        "temperature",
                        0.0,

                        "topP",
                        0.8,

                        "topK",
                        20,

                        "maxOutputTokens",
                        512
                )
        );
    }

    @SuppressWarnings("unchecked")
    private String extractResponseText(
            ResponseEntity<Map> response
    ) {

        Map<String, Object> responseBody =
                response.getBody();

        if (responseBody == null) {
            throw new RuntimeException(
                    "Gemini API không trả về dữ liệu"
            );
        }

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>)
                        responseBody.get(
                                "candidates"
                        );

        if (candidates == null
                || candidates.isEmpty()) {

            throw new RuntimeException(
                    "Gemini không có candidate"
            );
        }

        Map<String, Object> content =
                (Map<String, Object>)
                        candidates.get(0)
                                .get("content");

        if (content == null) {
            throw new RuntimeException(
                    "Gemini không trả về content"
            );
        }

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>)
                        content.get("parts");

        if (parts == null
                || parts.isEmpty()) {

            throw new RuntimeException(
                    "Gemini không trả về nội dung"
            );
        }

        Object text =
                parts.get(0).get("text");

        if (!(text instanceof String)
                || ((String) text).isBlank()) {

            throw new RuntimeException(
                    "Gemini không trả về nội dung hợp lệ"
            );
        }

        return (String) text;
    }

    /**
     * Retry cho luồng AI chính.
     */
    private <T> T callWithRetry(
            String operationName,
            Supplier<T> call
    ) {

        RestClientException lastError =
                null;

        /*
         * attempt:
         *
         * 0 = lần đầu
         * 1 = retry 1
         * 2 = retry 2
         *
         * Tổng cộng tối đa 3 HTTP calls.
         */
        for (
                int attempt = 0;
                attempt <= MAX_LLM_RETRIES;
                attempt++
        ) {

            try {

                return call.get();

            } catch (ResourceAccessException e) {

                lastError = e;

                log.warn(
                        "[{}] Gemini API network/timeout error (attempt {}/{}): {}",
                        operationName,
                        attempt + 1,
                        MAX_LLM_RETRIES + 1,
                        e.getMessage()
                );

            } catch (HttpServerErrorException e) {

                lastError = e;

                log.warn(
                        "[{}] Gemini API 5xx error (attempt {}/{}): {}",
                        operationName,
                        attempt + 1,
                        MAX_LLM_RETRIES + 1,
                        e.getStatusCode()
                );

            } catch (HttpStatusCodeException e) {

                if (e.getStatusCode().value() == 429) {

                    lastError = e;

                    log.warn(
                            "[{}] Gemini API rate limited - 429 (attempt {}/{})",
                            operationName,
                            attempt + 1,
                            MAX_LLM_RETRIES + 1
                    );

                } else {

                    /*
                     * 400/401/403...
                     * Không retry.
                     */
                    throw e;
                }
            }

            if (attempt < MAX_LLM_RETRIES) {
                sleepQuietly(
                        BACKOFF_MS[attempt]
                );
            }
        }

        log.error(
                "[{}] Gemini API thất bại sau {} lần thử",
                operationName,
                MAX_LLM_RETRIES + 1
        );

        throw new RuntimeException(
                "Không thể kết nối tới dịch vụ AI, vui lòng thử lại sau ít phút.",
                lastError
        );
    }

    private void sleepQuietly(long millis) {

        try {

            Thread.sleep(millis);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }
}