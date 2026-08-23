package com.example.aidatabaseassistant.ai;

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

    private final RestTemplate restTemplate;

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    public LLMClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @SuppressWarnings("unchecked")
    public String generateResponse(String prompt) {

        // ===== Header =====
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // ===== Request Body =====
        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", prompt)
                                )
                        )
                ),

                // Quan trọng nhất
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "topP", 0.8,
                        "topK", 20,
                        "maxOutputTokens", 512
                )
        );

        String url = apiUrl + "?key=" + apiKey;

        // ===== Debug Prompt =====
        System.out.println("\n========== PROMPT ==========");
        System.out.println(prompt);

        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(body, headers);

        ResponseEntity<Map> response =
                restTemplate.postForEntity(url, entity, Map.class);

        Map<String, Object> responseBody = response.getBody();

        if (responseBody == null) {
            throw new RuntimeException("Gemini API không trả về dữ liệu");
        }

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) responseBody.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Gemini không có candidate");
        }

        Map<String, Object> content =
                (Map<String, Object>) candidates.get(0).get("content");

        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) content.get("parts");

        String answer = (String) parts.get(0).get("text");

        // ===== Debug Response =====
        System.out.println("\n========== GEMINI RESPONSE ==========");
        System.out.println(answer);
        System.out.println("====================================\n");

        return answer;
    }
}