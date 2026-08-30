package com.example.aidatabaseassistant.ai;

import com.example.aidatabaseassistant.entity.DatabaseSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NL2SQLEngineTest {

    @Mock
    private LLMClient llmClient;

    @Mock
    private PromptBuilder promptBuilder;

    private NL2SQLEngine engine;

    @BeforeEach
    void setUp() {
        engine = new NL2SQLEngine(llmClient, promptBuilder);
    }

    private DatabaseSchema emptySchema() {
        return DatabaseSchema.builder().id(1L).tables(java.util.List.of()).build();
    }

    @Test
    void shouldBlockWriteOperation_whenQuestionContainsAccentedDoi() {
        // Bug da fix: truoc day \b khong nhan dien duoc ranh gioi tu voi
        // "đổi" (bat dau bang ky tu co dau "đ") do Java mac dinh dung
        // ASCII \w - "\bđổi\b" khong bao gio khop. Sau khi them
        // Pattern.UNICODE_CHARACTER_CLASS, phai khop dung.
        String sql = engine.generateSQL("Hãy đổi tên khách hàng này thành ABC", emptySchema());

        assertTrue(sql.contains("Không được phép"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldBlockWriteOperation_whenQuestionContainsAccentedDoiInOtherContext() {
        String sql = engine.generateSQL("đổi mật khẩu cho tài khoản admin", emptySchema());

        assertTrue(sql.contains("Không được phép"));
        verifyNoInteractions(llmClient);
    }

    @Test
    void shouldNotBlock_whenQuestionIsPlainReadQuery() {
        when(promptBuilder.buildGenerationPrompt(anyString(), any()))
                .thenReturn("prompt");
        when(llmClient.generateResponse(anyString()))
                .thenReturn("SELECT * FROM orders");

        String sql = engine.generateSQL("Doanh thu theo tháng", emptySchema());

        assertEquals("SELECT * FROM orders", sql);
        verify(llmClient).generateResponse(anyString());
    }

    @Test
    void shouldStillBlockWriteOperation_forEnglishSqlKeyword() {
        String sql = engine.generateSQL("Please DELETE all orders", emptySchema());

        assertTrue(sql.contains("Không được phép"));
        verifyNoInteractions(llmClient);
    }
}