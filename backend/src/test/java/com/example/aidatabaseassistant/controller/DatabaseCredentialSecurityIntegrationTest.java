package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
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

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseCredentialSecurityIntegrationTest {

    // =========================================================
    // HTTP
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
    private NL2SQLEngine nl2SQLEngine;

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
                        .host("invalid-host-that-does-not-exist")
                        .port(3306)
                        .databaseName("shop")
                        .username("root")
                        .encryptedPassword("encrypted-password")
                        .user(user)
                        .build();


        // -----------------------------------------------------
        // SCHEMA
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
        // SCHEMA
        // -----------------------------------------------------

        when(
                schemaLoaderService.loadCompleteSchema(1L)
        ).thenReturn(schema);


        when(
                schemaRetrievalService.retrieveRelevantSchema(
                        anyString(),
                        eq(schema)
                )
        ).thenReturn(schema);


        // -----------------------------------------------------
        // AI
        // -----------------------------------------------------

        when(
                nl2SQLEngine.generateSQL(
                        anyString(),
                        eq(schema)
                )
        ).thenReturn(
                "SELECT * FROM customers"
        );


        // -----------------------------------------------------
        // ENCRYPTION
        // -----------------------------------------------------

        when(
                encryptionUtil.decrypt(
                        "encrypted-password"
                )
        ).thenReturn(
                "SuperSecretPassword123!"
        );


        // -----------------------------------------------------
        // CONVERSATION
        // -----------------------------------------------------

        when(
                conversationRepository.save(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );


        // -----------------------------------------------------
        // MESSAGE
        // -----------------------------------------------------

        when(
                messageRepository.save(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );


        // -----------------------------------------------------
        // QUERY LOG
        // -----------------------------------------------------

        when(
                queryLogRepository.save(any())
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(0)
        );


        // -----------------------------------------------------
        // DATABASE ERROR
        // -----------------------------------------------------

        /*
         * Giả lập tình huống DB sai host/port.
         *
         * QueryExecutor production thực tế sẽ trả lỗi
         * thông qua QueryResultDto.
         *
         * Ở đây ta giả lập chính xác kết quả lỗi mà
         * QueryService nhận được.
         */

        when(
                queryExecutor.executeQuery(
                        anyString(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()
                )
        ).thenReturn(
                new com.example.aidatabaseassistant.dto.QueryResultDto(
                        java.util.List.of(),
                        java.util.List.of(),
                        100L,
                        0,
                        "Communications link failure. " +
                                "Access denied for user 'root' " +
                                "using password 'SuperSecretPassword123!'"
                )
        );
    }


    // =========================================================
    // TEST
    // =========================================================

    @Test
    void execute_shouldNotExposeDatabaseCredential_whenConnectionFails()
            throws Exception {

        String secretPassword =
                "SuperSecretPassword123!";


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
                                            "question": "Cho tôi danh sách khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(
                        status().isOk()
                )

                /*
                 * Đây là assertion bảo mật quan trọng nhất.
                 *
                 * Password tuyệt đối không được xuất hiện
                 * trong HTTP response.
                 */
                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                secretPassword
                                        )
                                )
                        )
                );
    }

    @Test
    void execute_shouldNotExposeDatabaseCredentialInLogs_whenConnectionFails()
            throws Exception {

        String secretPassword =
                "SuperSecretPassword123!";

        String encryptedPassword =
                "encrypted-password";

        String databaseUsername =
                "root";

        String databaseHost =
                "invalid-host-that-does-not-exist";


        // ---------------------------------------------------------
        // Thực hiện request HTTP
        // ---------------------------------------------------------

        var mvcResult =
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
                                        "question": "Cho tôi danh sách khách hàng",
                                        "databaseConnectionId": 1
                                    }
                                    """)
                        )
                        .andExpect(
                                status().isOk()
                        )
                        .andReturn();


        // ---------------------------------------------------------
        // HTTP response không được chứa credential
        // ---------------------------------------------------------

        String responseBody =
                mvcResult.getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(
                responseBody.contains(secretPassword),
                "Response không được chứa password DB"
        );

        org.junit.jupiter.api.Assertions.assertFalse(
                responseBody.contains(encryptedPassword),
                "Response không được chứa encrypted password"
        );

        org.junit.jupiter.api.Assertions.assertFalse(
                responseBody.contains(databaseUsername),
                "Response không được chứa username DB"
        );


        // ---------------------------------------------------------
        // Lưu ý:
        //
        // MockMvc không trực tiếp cung cấp toàn bộ application log.
        //
        // Vì vậy test log cần dùng log appender.
        // ---------------------------------------------------------
    }
}