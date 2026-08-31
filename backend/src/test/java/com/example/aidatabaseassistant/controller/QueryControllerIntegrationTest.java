package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.service.ChartSuggestionService;
import com.example.aidatabaseassistant.service.DataInsightService;
import com.example.aidatabaseassistant.service.QueryService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SqlExplanationService;
import com.example.aidatabaseassistant.service.SqlOptimizationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private QueryService queryService;

    @MockitoBean
    private SqlExplanationService sqlExplanationService;

    @MockitoBean
    private ChartSuggestionService chartSuggestionService;

    @MockitoBean
    private DataInsightService dataInsightService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private SqlOptimizationService sqlOptimizationService;

    private String validToken;


    @BeforeEach
    void setUp() {

        userRepository.deleteAll();

        User user = User.builder()
                .username("khai")
                .email("khai@example.com")
                .passwordHash("test-password-hash")
                .role(Role.USER)
                .locked(false)
                .build();

        userRepository.save(user);

        validToken = jwtUtil.generateToken("khai");
    }


    // =========================================================
    // JWT SECURITY
    // =========================================================

    @Test
    void execute_shouldReturn401_whenJwtIsExpired()
            throws Exception {

        String expiredToken = createExpiredToken();

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer " + expiredToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }


    @Test
    void execute_shouldReturn401_whenAuthorizationHeaderIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/execute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }


    @Test
    void execute_shouldReturn401_whenJwtIsInvalid()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer invalid.jwt.token"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }


    @Test
    void execute_shouldReturn401_whenAuthorizationHeaderIsMalformed()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Basic abc123"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }


    // =========================================================
    // AUTHENTICATED REQUEST
    // =========================================================

    @Test
    void execute_shouldReachController_whenJwtIsValid()
            throws Exception {

        when(queryService.processQuery(
                eq("khai"),
                any()
        )).thenReturn(null);

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isOk());

        verify(queryService).processQuery(
                eq("khai"),
                any()
        );
    }


    // =========================================================
    // PREVIEW
    // =========================================================

    @Test
    void preview_shouldReturn401_whenJwtIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Cho biết tất cả khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queryService);
    }


    // =========================================================
    // SQL SECURITY
    // =========================================================

    @Test
    void execute_shouldRejectRequest_whenServiceThrowsIllegalArgumentException()
            throws Exception {

        when(queryService.processQuery(
                eq("khai"),
                any()
        )).thenThrow(
                new IllegalArgumentException(
                        "Chỉ cho phép câu lệnh SELECT"
                )
        );

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "question": "Xóa toàn bộ khách hàng",
                                            "databaseConnectionId": 1
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(queryService).processQuery(
                eq("khai"),
                any()
        );
    }


    // =========================================================
    // HELPER
    // =========================================================

    private String createExpiredToken() {

        String secret =
                "TestOnlySecretKeyForJUnitDoNotUseInProduction123456";

        SecretKey key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        Date now = new Date();

        return Jwts.builder()
                .subject("khai")
                .issuedAt(
                        new Date(now.getTime() - 10_000)
                )
                .expiration(
                        new Date(now.getTime() - 1_000)
                )
                .signWith(
                        key,
                        SignatureAlgorithm.HS256
                )
                .compact();
    }

    @Test
    void execute_shouldRejectInsertSqlThroughHttp() throws Exception {

        when(queryService.processQuery(
                eq("khai"),
                any()
        )).thenThrow(
                new IllegalArgumentException(
                        "Chỉ cho phép câu lệnh SELECT"
                )
        );

        mockMvc.perform(
                        post("/api/query/execute")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                        "question": "INSERT INTO customers VALUES (1, 'Hacker')",
                                        "databaseConnectionId": 1
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest());

        verify(queryService).processQuery(
                eq("khai"),
                any()
        );
    }
}