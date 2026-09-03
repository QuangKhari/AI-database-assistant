package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ConnectionService connectionService;

    @MockitoBean
    private QueryService queryService;

    private String ownerToken;

    @BeforeEach
    void setUp() {

        userRepository.deleteAll();

        User owner = User.builder()
                .username("owner")
                .email("owner@example.com")
                .passwordHash("test-password-hash")
                .role(Role.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(owner);

        ownerToken = jwtUtil.generateToken("owner");
    }

    @Test
    void getConnection_shouldReturn400_whenConnectionBelongsToAnotherUser()
            throws Exception {

        when(connectionService.getConnection(
                eq("owner"),
                eq(100L)
        )).thenThrow(
                new IllegalArgumentException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        mockMvc.perform(
                        get("/api/connections/100")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Bạn không có quyền truy cập connection này"
                                )
                );

        verify(connectionService)
                .getConnection("owner", 100L);
    }

    @Test
    void updateConnection_shouldReturn400_whenConnectionBelongsToAnotherUser()
            throws Exception {

        when(connectionService.updateConnection(
                eq("owner"),
                eq(100L),
                any(ConnectionUpdateRequest.class)
        )).thenThrow(
                new IllegalArgumentException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        String requestBody = """
            {
                "name": "Hacked DB",
                "host": "localhost",
                "port": 3306,
                "databaseName": "shop",
                "username": "root",
                "password": "password"
            }
            """;

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/connections/100")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Bạn không có quyền truy cập connection này"
                                )
                );

        verify(connectionService).updateConnection(
                eq("owner"),
                eq(100L),
                any(ConnectionUpdateRequest.class)
        );
    }

    @Test
    void deleteConnection_shouldReturn400_whenConnectionBelongsToAnotherUser()
            throws Exception {

        doThrow(
                new IllegalArgumentException(
                        "Bạn không có quyền truy cập connection này"
                )
        ).when(connectionService)
                .disconnect("owner", 100L);

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete("/api/connections/100")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Bạn không có quyền truy cập connection này"
                                )
                );

        verify(connectionService)
                .disconnect("owner", 100L);
    }

    @Test
    void reconnect_shouldReturn400_whenConnectionBelongsToAnotherUser()
            throws Exception {

        when(connectionService.reconnect(
                "owner",
                100L
        )).thenThrow(
                new IllegalArgumentException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/connections/100/reconnect")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Bạn không có quyền truy cập connection này"
                                )
                );

        verify(connectionService)
                .reconnect("owner", 100L);
    }

    @Test
    void executeQuery_shouldReturn401_whenJwtIsMissing()
            throws Exception {

        String requestBody = """
        {
            "question": "Doanh thu tháng này",
            "databaseConnectionId": 100
        }
        """;

        mockMvc.perform(
                        post("/api/query/execute")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(connectionService);
    }

}