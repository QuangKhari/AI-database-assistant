package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.dto.SuggestedQuestionsResponse;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SuggestedQuestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestedQuestionService.class);
    private static final int MAX_QUESTIONS = 8;

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public SuggestedQuestionsResponse getSuggestions(String username, Long connectionId, boolean refresh) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

        DatabaseConnection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

        if (!connection.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
        }

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Chưa có schema. Vui lòng đồng bộ schema (POST /api/connections/{id}/schema) trước."));

        if (schema.getTables() == null || schema.getTables().isEmpty()) {
            throw new IllegalArgumentException("Schema chưa có bảng nào để gợi ý câu hỏi");
        }

        // ----- Cache hit: chỉ dùng lại nếu schema chưa resync sau khi cache -----
        boolean cacheValid = !refresh
                && schema.getSuggestedQuestionsJson() != null
                && schema.getSuggestedQuestionsGeneratedAt() != null
                && (schema.getLastSyncedAt() == null
                || !schema.getLastSyncedAt().isAfter(schema.getSuggestedQuestionsGeneratedAt()));

        if (cacheValid) {
            List<String> cached = parseJsonArray(schema.getSuggestedQuestionsJson());
            if (!cached.isEmpty()) {
                return new SuggestedQuestionsResponse(cached, "cache");
            }
        }

        List<String> questions;
        String source;

        try {
            String prompt = promptBuilder.buildSuggestedQuestionsPrompt(schema, MAX_QUESTIONS);
            String raw = llmClient.generateResponse(prompt);
            questions = parseJsonArray(cleanJson(raw));

            if (questions.isEmpty()) {
                throw new IllegalStateException("Gemini trả về danh sách rỗng hoặc không parse được");
            }
            source = "ai";

        } catch (Exception e) {
            // Đúng triết lý: main flow KHÔNG bao giờ fail vì AI lỗi -> dùng template
            log.warn("Không tạo được gợi ý câu hỏi bằng AI cho connection {}: {}. Dùng template fallback.",
                    connectionId, e.getMessage());
            questions = buildTemplateFallback(schema);
            source = "template";
        }

        questions = questions.stream().distinct().limit(MAX_QUESTIONS).toList();

        schema.setSuggestedQuestionsJson(toJson(questions));
        schema.setSuggestedQuestionsGeneratedAt(LocalDateTime.now());
        schemaRepository.save(schema);

        return new SuggestedQuestionsResponse(questions, source);
    }

    private List<String> buildTemplateFallback(DatabaseSchema schema) {
        List<String> result = new ArrayList<>();

        for (TableMetadata table : schema.getTables()) {
            if (result.size() >= MAX_QUESTIONS) break;

            String label = (table.getDescription() != null && !table.getDescription().isBlank())
                    ? table.getDescription()
                    : table.getName();

            result.add("Có bao nhiêu " + label + "?");

            boolean hasCreatedAt = table.getColumns().stream()
                    .anyMatch(c -> c.getName().toLowerCase().contains("created")
                            || c.getName().toLowerCase().contains("date")
                            || c.getName().toLowerCase().contains("time"));

            if (hasCreatedAt && result.size() < MAX_QUESTIONS) {
                result.add("Hiển thị 10 " + label + " gần đây nhất");
            }
        }

        return result;
    }

    private String cleanJson(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("Không parse được JSON gợi ý câu hỏi: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJson(List<String> questions) {
        try {
            return objectMapper.writeValueAsString(questions);
        } catch (Exception e) {
            return "[]";
        }
    }
}