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
    private final com.example.aidatabaseassistant.security.ConnectionAccessGuard connectionAccessGuard;

    @Transactional
    public SuggestedQuestionsResponse getSuggestions(String username, Long connectionId, boolean refresh) {
        DatabaseConnection connection = connectionAccessGuard.requireOwnedConnection(username, connectionId);

        DatabaseSchema schema = schemaRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new com.example.aidatabaseassistant.exception.ResourceNotFoundException(
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

    private List<String> parseJsonArray(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();

            // Doc tuong minh thanh List<String> bang TypeReference thay vi
            // List.class tho (raw type) - truoc day neu Gemini tra ve JSON
            // hop le nhung SAI HINH DANG (vd [{"question":"..."}] thay vi
            // ["..."]) thi code cu se KHONG bao gio bao loi, lang le nhet
            // LinkedHashMap vao field khai bao la List<String>, roi Jackson
            // serialize ra ngoai theo runtime type that -> FE nhan mang
            // object long nhau nhung van thay source:"ai" nhu thanh cong.
            List<String> parsed = objectMapper.readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});

            // Loai bo phan tu rong/blank cho chac, giu nguyen thu tu.
            return parsed.stream()
                    .filter(q -> q != null && !q.isBlank())
                    .toList();

        } catch (Exception e) {
            // TypeReference<List<String>> se tu throw MismatchedInputException
            // ngay tai day neu phan tu khong phai String (vi du la object),
            // roi roi vao nhanh nay -> coi nhu parse that bai, dung template
            // fallback nhu cac truong hop loi khac, KHONG bao gio de lot du
            // lieu sai hinh dang ra ngoai.
            log.warn("Không parse được JSON gợi ý câu hỏi (sai định dạng hoặc sai kiểu phần tử): {}", e.getMessage());
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