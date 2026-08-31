package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationOwnershipIntegrationTest {

    // =========================================================
    // HTTP
    // =========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;


    // =========================================================
    // MOCK REPOSITORIES
    // =========================================================

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private MessageRepository messageRepository;


    // =========================================================
    // USERS
    // =========================================================

    private User userA;

    private User userB;


    // =========================================================
    // CONVERSATION
    // =========================================================

    private Conversation userBConversation;


    // =========================================================
    // JWT
    // =========================================================

    private String tokenA;

    private String tokenB;


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUp() {

        // -----------------------------------------------------
        // USER A
        // -----------------------------------------------------

        userA =
                User.builder()
                        .id(1L)
                        .username("userA")
                        .email("userA@test.com")
                        .passwordHash("password")
                        .build();


        // -----------------------------------------------------
        // USER B
        // -----------------------------------------------------

        userB =
                User.builder()
                        .id(2L)
                        .username("userB")
                        .email("userB@test.com")
                        .passwordHash("password")
                        .build();


        // -----------------------------------------------------
        // JWT
        // -----------------------------------------------------

        tokenA =
                jwtUtil.generateToken("userA");

        tokenB =
                jwtUtil.generateToken("userB");


        // -----------------------------------------------------
        // USER REPOSITORY
        // -----------------------------------------------------

        when(
                userRepository.findByUsername("userA")
        ).thenReturn(
                Optional.of(userA)
        );

        when(
                userRepository.findByUsername("userB")
        ).thenReturn(
                Optional.of(userB)
        );


        // -----------------------------------------------------
        // CONNECTION
        // -----------------------------------------------------

        DatabaseConnection connection =
                DatabaseConnection.builder()
                        .id(1L)
                        .name("Test DB")
                        .host("localhost")
                        .port(3306)
                        .databaseName("shop")
                        .username("root")
                        .encryptedPassword("encrypted")
                        .user(userB)
                        .build();


        // -----------------------------------------------------
        // CONVERSATION THUỘC USER B
        // -----------------------------------------------------

        userBConversation =
                Conversation.builder()
                        .id(100L)
                        .user(userB)
                        .connection(connection)
                        .title("Conversation của User B")
                        .build();


        when(
                conversationRepository.findById(100L)
        ).thenReturn(
                Optional.of(userBConversation)
        );
    }


    // =========================================================
    // TEST 1
    // =========================================================

    @Test
    void getMessages_shouldRejectOtherUsersConversation()
            throws Exception {

        /*
         * User A cố truy cập conversation của User B.
         *
         * GET /api/conversations/100/messages
         */

        mockMvc.perform(
                        get("/api/conversations/100/messages")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenA
                                )
                                .contentType(APPLICATION_JSON)
                )
                .andExpect(
                        status().isBadRequest()
                );


        /*
         * Vì ownership bị từ chối,
         * messageRepository KHÔNG được phép truy vấn.
         */

        verify(
                messageRepository,
                never()
        ).findByConversationIdOrderByCreatedAtAsc(
                eq(100L)
        );
    }


    // =========================================================
    // TEST 2
    // =========================================================

    @Test
    void deleteConversation_shouldRejectOtherUsersConversation()
            throws Exception {

        /*
         * User A cố xóa conversation của User B.
         *
         * DELETE /api/conversations/100
         */

        mockMvc.perform(
                        delete("/api/conversations/100")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenA
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );


        /*
         * Conversation tuyệt đối không được xóa.
         */

        verify(
                conversationRepository,
                never()
        ).delete(
                any(Conversation.class)
        );
    }


    // =========================================================
    // TEST 3
    // =========================================================

    @Test
    void getConversations_shouldReturnOnlyCurrentUsersConversations()
            throws Exception {

        /*
         * User A đăng nhập.
         *
         * Repository phải được gọi với userId = 1,
         * KHÔNG phải userId của User B.
         */

        when(
                conversationRepository.findByUserId(1L)
        ).thenReturn(
                List.of()
        );


        mockMvc.perform(
                        get("/api/conversations")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenA
                                )
                                .contentType(APPLICATION_JSON)
                )
                .andExpect(
                        status().isOk()
                );


        /*
         * Kiểm tra query conversation sử dụng
         * đúng user hiện tại.
         */

        verify(
                conversationRepository
        ).findByUserId(
                eq(1L)
        );

        /*
         * Không được lấy conversation theo User B.
         */

        verify(
                conversationRepository,
                never()
        ).findByUserId(
                eq(2L)
        );
    }


    // =========================================================
    // TEST 4
    // =========================================================

    @Test
    void ownerShouldBeAbleToAccessOwnConversation()
            throws Exception {

        /*
         * User B chính là owner của conversation 100.
         */

        when(
                messageRepository
                        .findByConversationIdOrderByCreatedAtAsc(100L)
        ).thenReturn(
                List.of()
        );


        mockMvc.perform(
                        get("/api/conversations/100/messages")
                                .header(
                                        "Authorization",
                                        "Bearer " + tokenB
                                )
                                .contentType(APPLICATION_JSON)
                )
                .andExpect(
                        status().isOk()
                );


        /*
         * Owner được phép truy cập message.
         */

        verify(
                messageRepository
        ).findByConversationIdOrderByCreatedAtAsc(
                eq(100L)
        );
    }
}
