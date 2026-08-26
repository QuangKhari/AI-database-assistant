package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.PromptBuilder;
import com.example.aidatabaseassistant.dto.ExplainSqlRequest;
import com.example.aidatabaseassistant.dto.ExplainSqlResponse;
import com.example.aidatabaseassistant.dto.SqlExplanationStep;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SqlExplanationService {

    private final UserRepository userRepository;
    private final DatabaseConnectionRepository connectionRepository;
    private final DatabaseSchemaRepository schemaRepository;
    private final PromptBuilder promptBuilder;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public ExplainSqlResponse explain(String username, ExplainSqlRequest request) {
        DatabaseSchema schema = null;

        // Schema context la TUY CHON - neu client co truyen databaseConnectionId
        // thi van BAT BUOC kiem tra ownership (IDOR) truoc khi dung schema do,
        // nhung neu chua discover schema thi bo qua context chu khong loi cung,
        // vi giai thich SQL van co gia tri du khong co schema.
        if (request.getDatabaseConnectionId() != null) {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy user"));

            DatabaseConnection connection = connectionRepository.findById(request.getDatabaseConnectionId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy connection"));

            if (!connection.getUser().getId().equals(user.getId())) {
                throw new IllegalArgumentException("Bạn không có quyền truy cập connection này");
            }

            schema = schemaRepository.findByConnectionId(connection.getId()).orElse(null);
        }

        String prompt = promptBuilder.buildExplanationPrompt(request.getSql(), schema);
        String rawResponse = llmClient.generateResponse(prompt);

        LlmExplanationPayload payload = parseResponse(rawResponse);

        return new ExplainSqlResponse(request.getSql(), payload.getSummary(), payload.getSteps());
    }

    private LlmExplanationPayload parseResponse(String rawResponse) {
        // Du prompt da yeu cau khong dung markdown code block, mot so lan AI
        // van boc JSON trong ```json ... ``` - go bo truoc khi parse cho chac.
        String cleaned = rawResponse
                .replaceAll("(?s)```json", "")
                .replaceAll("(?s)```", "")
                .trim();

        try {
            return objectMapper.readValue(cleaned, LlmExplanationPayload.class);
        } catch (Exception e) {
            throw new RuntimeException("AI trả về định dạng không hợp lệ, vui lòng thử lại", e);
        }
    }

    /**
     * Class rieng chi de Jackson parse JSON tra ve tu LLM (chi co summary +
     * steps). Khong dung chung ExplainSqlResponse vi field "sql" nen luon lay
     * tu request cua nguoi dung, khong de AI tu echo lai (tranh AI vo tinh
     * sua doi cau SQL goc trong luc tra loi).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    private static class LlmExplanationPayload {
        private String summary;
        private List<SqlExplanationStep> steps;
    }
}