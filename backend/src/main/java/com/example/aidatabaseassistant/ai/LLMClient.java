package com.example.aidatabaseassistant.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class LLMClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public LLMClient(
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-4.1-mini}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.timeout-seconds:30}") int timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;

        Duration timeout = Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 60)));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String generateResponse(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API chưa được cấu hình.");
        }

        Map<String, Object> request = Map.of(
                "model", model,
                "instructions", "You generate safe, read-only MySQL for the supplied schema. "
                        + "Follow the user's output format exactly.",
                "input", prompt,
                "temperature", 0.0,
                "max_output_tokens", 1200,
                "store", false
        );

        try {
            OpenAIResponse response = restClient.post()
                    .uri("/responses")
                    .body(request)
                    .retrieve()
                    .body(OpenAIResponse.class);
            String outputText = extractOutputText(response);
            if (outputText == null || outputText.isBlank()) {
                throw new IllegalStateException("OpenAI không trả về nội dung hợp lệ.");
            }
            return outputText;
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw new IllegalStateException("Không thể nhận phản hồi từ OpenAI. Vui lòng thử lại.");
        }
    }

    private String extractOutputText(OpenAIResponse response) {
        if (response == null || response.output() == null) return null;
        return response.output().stream()
                .filter(item -> item.content() != null)
                .flatMap(item -> item.content().stream())
                .filter(content -> "output_text".equals(content.type()))
                .map(OutputContent::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAIResponse(List<OutputItem> output) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OutputItem(List<OutputContent> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OutputContent(String type, String text) {
    }
}
