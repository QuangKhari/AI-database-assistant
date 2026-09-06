package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Date;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionExcelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ConnectionService connectionService;

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

    @Test
    void uploadExcel_shouldReturn200_whenJwtIsValid()
            throws Exception {

        ConnectionResponse expected =
                new ConnectionResponse(
                        10L,
                        "Sales",
                        "excel",
                        "local-file",
                        0,
                        "/data/sales.duckdb",
                        "excel-file",
                        true,
                        null,
                        null,
                        null,
                        null
                );

        when(connectionService.saveExcelConnection(
                eq("exceltest"),
                any(),
                eq("Sales")
        )).thenReturn(expected);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Sales"))
                .andExpect(jsonPath("$.dbType").value("excel"))
                .andExpect(jsonPath("$.host").value("local-file"))
                .andExpect(jsonPath("$.port").value(0))
                .andExpect(jsonPath("$.username").value("excel-file"));

        verify(connectionService)
                .saveExcelConnection(
                        eq("exceltest"),
                        any(),
                        eq("Sales")
                );
    }

    @Test
    void uploadExcel_shouldReturn401_whenJwtIsMissing()
            throws Exception {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(connectionService);
    }

    @Test
    void uploadExcel_shouldReturn401_whenJwtIsInvalid()
            throws Exception {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer invalid.jwt.token"
                                )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(connectionService);
    }

    @Test
    void uploadExcel_shouldReturn401_whenJwtIsExpired()
            throws Exception {

        String expiredToken = createExpiredToken();

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer " + expiredToken
                                )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(connectionService);
    }

    @Test
    void uploadExcel_shouldReturn400_whenFileIsMissing()
            throws Exception {

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(connectionService);
    }

    @Test
    void uploadExcel_shouldReturn400_whenNameIsMissing()
            throws Exception {

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(connectionService);
    }

    @Test
    void uploadExcel_shouldReturn400_whenServiceRejectsFile()
            throws Exception {

        when(connectionService.saveExcelConnection(
                eq("exceltest"),
                any(),
                eq("Sales")
        )).thenThrow(
                new IllegalArgumentException(
                        "Chỉ hỗ trợ file .xlsx (không hỗ trợ .xls)"
                )
        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xls",
                        "application/octet-stream",
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Chỉ hỗ trợ file .xlsx (không hỗ trợ .xls)"
                                )
                );

        verify(connectionService)
                .saveExcelConnection(
                        eq("exceltest"),
                        any(),
                        eq("Sales")
                );
    }

    @Test
    void uploadExcel_shouldReturn400_whenConnectionLimitIsExceeded()
            throws Exception {

        when(connectionService.saveExcelConnection(
                eq("exceltest"),
                any(),
                eq("Sales")
        )).thenThrow(
                new IllegalArgumentException(
                        "Bạn đã đạt giới hạn tối đa 5 kết nối database."
                )
        );

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        new byte[]{1, 2, 3}
                );

        mockMvc.perform(
                        multipart("/api/connections/excel")
                                .file(file)
                                .param("name", "Sales")
                                .header(
                                        "Authorization",
                                        "Bearer " + validToken
                                )
                )
                .andExpect(status().isBadRequest());

        verify(connectionService)
                .saveExcelConnection(
                        eq("exceltest"),
                        any(),
                        eq("Sales")
                );
    }

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
                        new Date(now.getTime() - 10_000)
                )
                .expiration(
                        new Date(now.getTime() - 1_000)
                )
                .signWith(
                        key,
                        io.jsonwebtoken.SignatureAlgorithm.HS256
                )
                .compact();
    }
}