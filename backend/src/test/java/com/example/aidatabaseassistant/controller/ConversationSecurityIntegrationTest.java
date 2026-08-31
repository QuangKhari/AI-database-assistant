package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;

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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationSecurityIntegrationTest {

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
    // TEST USERS
    // =========================================================

    private User owner;

    private User otherUser;


    // =========================================================
    // TEST DATA
    // =========================================================

    private Conversation otherUserConversation;

    private Message otherUserMessage;

    private String ownerToken;


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUp() {

        // -----------------------------------------------------
        // USER A
        // -----------------------------------------------------

        owner = User.builder()
                .id(1L)
                .username("owner")
                .email("owner@test.com")
                .passwordHash("password")
                .build();


        // -----------------------------------------------------
        // USER B
        // -----------------------------------------------------

        otherUser = User.builder()
                .id(2L)
                .username("other")
                .email("other@test.com")
                .passwordHash("password")
                .build();


        // -----------------------------------------------------
        // JWT CỦA USER A
        // -----------------------------------------------------

        ownerToken = jwtUtil.generateToken("owner");


        // -----------------------------------------------------
        // DATABASE CONNECTION CỦA USER B
        // -----------------------------------------------------

        DatabaseConnection otherConnection =
                DatabaseConnection.builder()
                        .id(20L)
                        .name("Other DB")
                        .host("localhost")
                        .port(3306)
                        .databaseName("other_db")
                        .username("root")
                        .encryptedPassword("encrypted")
                        .user(otherUser)
                        .build();


        // -----------------------------------------------------
        // CONVERSATION CỦA USER B
        // -----------------------------------------------------

        otherUserConversation =
                Conversation.builder()
                        .id(200L)
                        .title("Conversation của user khác")
                        .user(otherUser)
                        .connection(otherConnection)
                        .build();


        // -----------------------------------------------------
        // MESSAGE CỦA USER B
        // -----------------------------------------------------

        otherUserMessage =
                Message.builder()
                        .id(300L)
                        .role("user")
                        .content("Dữ liệu riêng tư của user khác")
                        .conversation(otherUserConversation)
                        .pinned(false)
                        .build();
    }


    // =========================================================
    // GET MESSAGES
    // =========================================================

    @Test
    void getMessages_shouldRejectAccessToOtherUsersConversationThroughHttp()
            throws Exception {

        // -----------------------------------------------------
        // JWT = USER A
        // -----------------------------------------------------

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));


        // -----------------------------------------------------
        // conversationId = 200 thuộc USER B
        // -----------------------------------------------------

        when(conversationRepository.findById(200L))
                .thenReturn(Optional.of(otherUserConversation));


        // -----------------------------------------------------
        // USER A cố đọc conversation của USER B
        // -----------------------------------------------------

        mockMvc.perform(
                        get("/api/conversations/200/messages")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                )
                .andExpect(status().isBadRequest());


        // -----------------------------------------------------
        // Không được phép lấy message của conversation đó
        // -----------------------------------------------------

        verify(
                messageRepository,
                never()
        ).findByConversationIdOrderByCreatedAtAsc(200L);
    }


    // =========================================================
    // DELETE CONVERSATION
    // =========================================================

    @Test
    void deleteConversation_shouldRejectDeletingOtherUsersConversationThroughHttp()
            throws Exception {

        // -----------------------------------------------------
        // JWT = USER A
        // -----------------------------------------------------

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));


        // -----------------------------------------------------
        // conversationId = 200 thuộc USER B
        // -----------------------------------------------------

        when(conversationRepository.findById(200L))
                .thenReturn(Optional.of(otherUserConversation));


        // -----------------------------------------------------
        // USER A cố xóa conversation của USER B
        // -----------------------------------------------------

        mockMvc.perform(
                        delete("/api/conversations/200")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                )
                .andExpect(status().isBadRequest());


        // -----------------------------------------------------
        // Conversation của USER B tuyệt đối không được delete
        // -----------------------------------------------------

        verify(
                conversationRepository,
                never()
        ).delete(otherUserConversation);
    }


    // =========================================================
    // PIN / UNPIN MESSAGE
    // =========================================================

    @Test
    void togglePin_shouldRejectOtherUsersMessageThroughHttp()
            throws Exception {

        // -----------------------------------------------------
        // JWT = USER A
        // -----------------------------------------------------

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));


        // -----------------------------------------------------
        // messageId = 300 thuộc conversation của USER B
        // -----------------------------------------------------

        when(messageRepository.findByIdWithOwner(300L))
                .thenReturn(Optional.of(otherUserMessage));


        // -----------------------------------------------------
        // USER A cố pin message của USER B
        // -----------------------------------------------------

        mockMvc.perform(
                        patch("/api/history/messages/300/pin")
                                .header(
                                        "Authorization",
                                        "Bearer " + ownerToken
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                )
                .andExpect(status().isBadRequest());


        // -----------------------------------------------------
        // Không được save message
        // -----------------------------------------------------

        verify(
                messageRepository,
                never()
        ).save(otherUserMessage);
    }
}