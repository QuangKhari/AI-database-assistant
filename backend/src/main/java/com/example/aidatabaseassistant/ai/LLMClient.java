package com.example.aidatabaseassistant.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class LLMClient {

    private static final Logger log =
            LoggerFactory.getLogger(LLMClient.class);

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

        String url = apiUrl + "?key=" + apiKey;

        log.info(
                "Gọi Gemini API, độ dài prompt: {} ký tự",
                prompt.length()
        );

        log.debug("Prompt gửi Gemini:\n{}", prompt);

        long start = System.currentTimeMillis();

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        url,
                        entity,
                        Map.class
                );

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

        Map<String, Object> body = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", text)
                        )
                )
        );

        String url = embeddingApiUrl + "?key=" + apiKey;

        log.info(
                "Gọi Gemini Embedding API, model={}, độ dài text={} ký tự",
                embeddingModel,
                text.length()
        );

        long start = System.currentTimeMillis();

        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        url,
                        new HttpEntity<>(body, headers),
                        Map.class
                );

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
}