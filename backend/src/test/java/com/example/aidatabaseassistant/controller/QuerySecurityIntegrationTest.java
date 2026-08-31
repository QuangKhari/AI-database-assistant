package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.QueryLogRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ChartSuggestionService;
import com.example.aidatabaseassistant.service.DataInsightService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SchemaLoaderService;
import com.example.aidatabaseassistant.service.SchemaRetrievalService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import com.example.aidatabaseassistant.service.SqlOptimizationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuerySecurityIntegrationTest {

    // =========================================================
    // HTTP TEST
    // =========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;


    // =========================================================
    // MOCK DEPENDENCIES
    // =========================================================

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private DatabaseConnectionRepository connectionRepository;

    @MockitoBean
    private DatabaseSchemaRepository schemaRepository;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private MessageRepository messageRepository;

    @MockitoBean
    private QueryLogRepository queryLogRepository;

    @MockitoBean
    private SchemaLoaderService schemaLoaderService;

    @MockitoBean
    private SchemaRetrievalService schemaRetrievalService;

    @MockitoBean
    private com.example.aidatabaseassistant.ai.NL2SQLEngine nl2SQLEngine;

    @MockitoBean
    private QueryExecutor queryExecutor;

    @MockitoBean
    private EncryptionUtil encryptionUtil;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private ChartSuggestionService chartSuggestionService;

    @MockitoBean
    private DataInsightService dataInsightService;

    @MockitoBean
    private SqlExplanationService sqlExplanationService;

    @MockitoBean
    private SqlOptimizationService sqlOptimizationService;

    @MockitoBean
    private LLMClient llmClient;


    // =========================================================
    // REAL SERVICE
    // =========================================================

    /*
     * KHÔNG mock SQLCorrectionService.
     *
     * Ta muốn kiểm tra flow thật:
     *
     * HTTP
     *   ↓
     * QueryController
     *   ↓
     * QueryService
     *   ↓
     * SQLCorrectionService
     *   ↓
     * QueryValidator THẬT
     *   ↓
     * chặn SQL nguy hiểm
     */
    @Autowired
    private SQLCorrectionService sqlCorrectionService;


    // =========================================================
    // TEST DATA
    // =========================================================

    private String validToken;

    private User user;

    private DatabaseConnection connection;

    private DatabaseSchema schema;


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUp() {

        // -----------------------------------------------------
        // JWT
        // -----------------------------------------------------

        validToken =
                jwtUtil.generateToken("khai");


        // -----------------------------------------------------
        // USER
        // -----------------------------------------------------

        user =
                User.builder()
                        .id(1L)
                        .username("khai")
                        .email("khai@test.com")
                        .passwordHash("password")
                        .build();


        // -----------------------------------------------------
        // DATABASE CONNECTION
        // -----------------------------------------------------

        connection =
                DatabaseConnection.builder()
                        .id(1L)
                        .name("Test DB")
                        .host("localhost")
                        .port(3306)
                        .databaseName("shop")
                        .username("root")
                        .encryptedPassword("encrypted")
                        .user(user)
                        .build();


        // -----------------------------------------------------
        // DATABASE SCHEMA
        // -----------------------------------------------------

        schema =
                DatabaseSchema.builder()
                        .databaseName("shop")
                        .build();


        // -----------------------------------------------------
        // RATE LIMIT
        // -----------------------------------------------------

        when(
                rateLimitService.tryConsume("khai")
        ).thenReturn(true);


        // -----------------------------------------------------
        // USER
        // -----------------------------------------------------

        when(
                userRepository.findByUsername("khai")
        ).thenReturn(
                Optional.of(user)
        );


        // -----------------------------------------------------
        // CONNECTION
        // -----------------------------------------------------

        when(
                connectionRepository.findById(1L)
        ).thenReturn(
                Optional.of(connection)
        );


        // -----------------------------------------------------
        // FULL SCHEMA
        // -----------------------------------------------------

        when(
                schemaLoaderService.loadCompleteSchema(1L)
        ).thenReturn(schema);


        // -----------------------------------------------------
        // RAG
        // -----------------------------------------------------

        /*
         * Test này tập trung vào READ-ONLY SECURITY,
         * không phải kiểm thử RAG.
         *
         * Vì vậy cho RAG trả lại toàn bộ schema.
         */

        when(
                schemaRetrievalService.retrieveRelevantSchema(
                        anyString(),
                        eq(schema)
                )
        ).thenReturn(schema);


        // -----------------------------------------------------
        // ENCRYPTION
        // -----------------------------------------------------

        /*
         * QueryService decrypt password trước khi chạy query.
         */

        when(
                encryptionUtil.decrypt("encrypted")
        ).thenReturn("password");


        // -----------------------------------------------------
        // CONVERSATION
        // -----------------------------------------------------

        when(
                conversationRepository.save(any())
        ).thenAnswer(
                invocation -> invocation.getArgument(0)
        );


        // -----------------------------------------------------
        // MESSAGE
        // -----------------------------------------------------

        when(
                messageRepository.save(any())
        ).thenAnswer(
                invocation -> invocation.getArgument(0)
        );


        // -----------------------------------------------------
        // QUERY LOG
        // -----------------------------------------------------

        when(
                queryLogRepository.save(any())
        ).thenAnswer(
                invocation -> invocation.getArgument(0)
        );
    }


    // =========================================================
    // INSERT
    // =========================================================

    @Test
    void execute_shouldRejectInsertThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "INSERT INTO customers(full_name) VALUES ('Hacker')"
        );
    }


    // =========================================================
    // UPDATE
    // =========================================================

    @Test
    void execute_shouldRejectUpdateThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "UPDATE customers SET full_name = 'Hacker'"
        );
    }


    // =========================================================
    // DELETE
    // =========================================================

    @Test
    void execute_shouldRejectDeleteThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "DELETE FROM customers"
        );
    }


    // =========================================================
    // DROP
    // =========================================================

    @Test
    void execute_shouldRejectDropThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "DROP TABLE customers"
        );
    }


    // =========================================================
    // ALTER
    // =========================================================

    @Test
    void execute_shouldRejectAlterThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "ALTER TABLE customers ADD COLUMN hacker VARCHAR(100)"
        );
    }


    // =========================================================
    // TRUNCATE
    // =========================================================

    @Test
    void execute_shouldRejectTruncateThroughHttp()
            throws Exception {

        rejectSqlThroughHttp(
                "TRUNCATE TABLE customers"
        );
    }


    // =========================================================
    // COMMON TEST
    // =========================================================

    private void rejectSqlThroughHttp(
            String maliciousSql
    ) throws Exception {

        // -----------------------------------------------------
        // AI sinh ra SQL nguy hiểm
        // -----------------------------------------------------

        when(
                nl2SQLEngine.generateSQL(
                        anyString(),
                        eq(schema)
                )
        ).thenReturn(maliciousSql);


        // -----------------------------------------------------
        // Gửi request HTTP thật
        // -----------------------------------------------------

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                            "question": "Hãy thực hiện truy vấn",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )


                // -------------------------------------------------
                // PRODUCTION CODE HIỆN TẠI TRẢ 200
                // -------------------------------------------------
                //
                // QueryValidator:
                //
                // INSERT / UPDATE / DELETE / DROP / ALTER / TRUNCATE
                //        ↓
                // ReadOnlyViolationException
                //
                // SQLCorrectionService bắt exception
                //        ↓
                // AttemptResult.success = false
                //
                // QueryService trả QueryResponse
                //        ↓
                // Controller trả HTTP 200
                //
                // Vì vậy ở test này phải expect 200.
                // -------------------------------------------------

                .andExpect(
                        status().isOk()
                )


                // -------------------------------------------------
                // Kiểm tra lỗi READ-ONLY
                // -------------------------------------------------

                .andExpect(
                        jsonPath("$.result.error")
                                .value(
                                        "Chỉ cho phép câu lệnh SELECT. Các câu lệnh INSERT, UPDATE, DELETE, DROP, ALTER, TRUNCATE, CREATE, RENAME, USE đều bị chặn."
                                )
                );


        // -----------------------------------------------------
        // SECURITY ASSERTION
        // -----------------------------------------------------
        //
        // SQL nguy hiểm KHÔNG ĐƯỢC phép xuống database.
        //
        // Đây là assertion quan trọng nhất của test.
        // -----------------------------------------------------

        verify(
                queryExecutor,
                never()
        ).executeQuery(
                anyString(),
                anyInt(),
                anyString(),
                anyString(),
                anyString(),
                eq(maliciousSql)
        );
    }
}