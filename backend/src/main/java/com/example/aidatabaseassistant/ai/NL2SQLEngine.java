package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class NL2SQLEngine {

    private final LLMClient llmClient;
    private final PromptBuilder promptBuilder;

    public String generateSQL(String question, DatabaseSchema schema) {
        return generateSQL(question, schema, List.of());
    }

    public String generateSQL(String question, DatabaseSchema schema,
                              List<ConversationContextMessage> context) {
        String prompt = promptBuilder.buildGenerationPrompt(question, schema, context);
        return extractSql(llmClient.generateResponse(prompt));
    }

    public String selfCorrect(String previousSql, String errorMessage, DatabaseSchema schema) {
        String prompt = promptBuilder.buildCorrectionPrompt(previousSql, errorMessage, schema);
        return extractSql(llmClient.generateResponse(prompt));
    }

    private String extractSql(String rawResponse) {
        return rawResponse.trim()
                .replaceAll("(?i)```sql", "")
                .replaceAll("```", "")
                .trim();
    }
}
