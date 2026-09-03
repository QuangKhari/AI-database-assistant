package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ExcelExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryExcelExportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ExcelExportService excelExportService;

    private String validToken;

    @BeforeEach
    void setUp() {

        userRepository.deleteAll();

        User user = User.builder()
                .username("exceltest")
                .email("exceltest@example.com")
                .passwordHash("test-password-hash")
                .role(Role.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(user);

        validToken = jwtUtil.generateToken("exceltest");
    }

    // =========================================================
    // 1. VALID REQUEST
    // =========================================================

    @Test
    void exportExcel_shouldReturn200AndExcelFile_whenJwtIsValid()
            throws Exception {

        byte[] fakeExcel =
                new byte[]{
                        0x50,
                        0x4B,
                        0x03,
                        0x04
                };

        when(excelExportService.export(any()))
                .thenReturn(fakeExcel);

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id",
                                                "name",
                                                "email"
                                            ],
                                            "rows": [
                                                {
                                                    "id": 1,
                                                    "name": "Alice",
                                                    "email": "alice@example.com"
                                                },
                                                {
                                                    "id": 2,
                                                    "name": "Bob",
                                                    "email": "bob@example.com"
                                                }
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        content().bytes(fakeExcel)
                )
                .andExpect(
                        content().contentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        )
                )
                .andExpect(
                        header().string(
                                "Content-Disposition",
                                "attachment; filename=\"query-result.xlsx\""
                        )
                )
                .andExpect(
                        header().longValue(
                                "Content-Length",
                                fakeExcel.length
                        )
                );

        verify(excelExportService)
                .export(any());
    }

    // =========================================================
    // 2. JWT MISSING
    // =========================================================

    @Test
    void exportExcel_shouldReturn401_whenJwtIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id",
                                                "name"
                                            ],
                                            "rows": [
                                                {
                                                    "id": 1,
                                                    "name": "Alice"
                                                }
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 3. JWT INVALID
    // =========================================================

    @Test
    void exportExcel_shouldReturn401_whenJwtIsInvalid()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer invalid.jwt.token"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id"
                                            ],
                                            "rows": [
                                                {
                                                    "id": 1
                                                }
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 4. JWT EXPIRED
    // =========================================================

    @Test
    void exportExcel_shouldReturn401_whenJwtIsExpired()
            throws Exception {

        String expiredToken =
                createExpiredToken();

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + expiredToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id"
                                            ],
                                            "rows": [
                                                {
                                                    "id": 1
                                                }
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 5. MALFORMED AUTHORIZATION HEADER
    // =========================================================

    @Test
    void exportExcel_shouldReturn401_whenAuthorizationHeaderIsMalformed()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Basic abc123"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id"
                                            ],
                                            "rows": [
                                                {
                                                    "id": 1
                                                }
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 6. EMPTY COLUMNS
    // =========================================================

    @Test
    void exportExcel_shouldReturn400_whenColumnsAreEmpty()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [],
                                            "rows": []
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 7. COLUMNS MISSING
    // =========================================================

    @Test
    void exportExcel_shouldReturn400_whenColumnsAreMissing()
            throws Exception {

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "rows": []
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 8. TOO MANY COLUMNS
    // =========================================================

    @Test
    void exportExcel_shouldReturn400_whenColumnsExceedLimit()
            throws Exception {

        StringBuilder columns =
                new StringBuilder("[");

        for (int i = 0; i < 101; i++) {

            if (i > 0) {
                columns.append(",");
            }

            columns.append("\"column_")
                    .append(i)
                    .append("\"");
        }

        columns.append("]");

        String body = """
                {
                    "columns": %s,
                    "rows": []
                }
                """.formatted(columns);

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 9. TOO MANY ROWS
    // =========================================================

    @Test
    void exportExcel_shouldReturn400_whenRowsExceedLimit()
            throws Exception {

        StringBuilder rows =
                new StringBuilder("[");

        for (int i = 0; i < 100_001; i++) {

            if (i > 0) {
                rows.append(",");
            }

            rows.append("""
                    {"id": %d}
                    """.formatted(i));
        }

        rows.append("]");

        String body = """
                {
                    "columns": ["id"],
                    "rows": %s
                }
                """.formatted(rows);

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(excelExportService);
    }

    // =========================================================
    // 10. SERVICE VALIDATION ERROR
    // =========================================================

    @Test
    void exportExcel_shouldReturn400_whenServiceRejectsRequest()
            throws Exception {

        when(excelExportService.export(any()))
                .thenThrow(
                        new IllegalArgumentException(
                                "Kết quả quá lớn để xuất Excel"
                        )
                );

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": ["id"],
                                            "rows": [
                                                {"id": 1}
                                            ]
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verify(excelExportService)
                .export(any());
    }

    // =========================================================
    // 11. EMPTY ROWS IS VALID
    // =========================================================

    @Test
    void exportExcel_shouldReturn200_whenRowsAreEmpty()
            throws Exception {

        byte[] fakeExcel =
                new byte[]{
                        0x50,
                        0x4B,
                        0x03,
                        0x04
                };

        when(excelExportService.export(any()))
                .thenReturn(fakeExcel);

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id",
                                                "name"
                                            ],
                                            "rows": []
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(content().bytes(fakeExcel));

        verify(excelExportService)
                .export(any());
    }

    // =========================================================
    // 12. NULL ROWS
    // =========================================================

    @Test
    void exportExcel_shouldReturn200_whenRowsAreNull()
            throws Exception {

        byte[] fakeExcel =
                new byte[]{
                        0x50,
                        0x4B,
                        0x03,
                        0x04
                };

        when(excelExportService.export(any()))
                .thenReturn(fakeExcel);

        mockMvc.perform(
                        post("/api/query/export/excel")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "columns": [
                                                "id",
                                                "name"
                                            ],
                                            "rows": null
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(content().bytes(fakeExcel));

        verify(excelExportService)
                .export(any());
    }

    // =========================================================
    // HELPER
    // =========================================================

    private String createExpiredToken() {

        String secret =
                "TestOnlySecretKeyForJUnitDoNotUseInProduction123456";

        javax.crypto.SecretKey key =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8
                        )
                );

        Date now = new Date();

        return io.jsonwebtoken.Jwts.builder()
                .subject("exceltest")
                .issuedAt(
                        new Date(
                                now.getTime() - 10_000
                        )
                )
                .expiration(
                        new Date(
                                now.getTime() - 1_000
                        )
                )
                .signWith(
                        key,
                        io.jsonwebtoken.SignatureAlgorithm.HS256
                )
                .compact();
    }
}