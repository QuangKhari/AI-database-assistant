package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.dto.ConversationResponse;
import com.example.aidatabaseassistant.dto.MessageResponse;
import com.example.aidatabaseassistant.dto.MessageSearchResultResponse;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConnectionAccessGuard connectionAccessGuard;

    private ConversationService conversationService;

    private User owner;
    private User otherUser;
    private Conversation conversation;

    @BeforeEach
    void setUp() {

        conversationService = new ConversationService(
                conversationRepository,
                messageRepository,
                userRepository,
                connectionAccessGuard
        );

        owner = User.builder()
                .id(1L)
                .username("owner")
                .build();

        otherUser = User.builder()
                .id(2L)
                .username("intruder")
                .build();

        DatabaseConnection connection = DatabaseConnection.builder()
                .id(20L)
                .user(owner)
                .name("Sample DB")
                .dbType("mysql")
                .build();

        conversation = Conversation.builder()
                .id(100L)
                .user(owner)
                .connection(connection)
                .title("Doanh thu theo tháng")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Message sampleMessage(boolean pinned) {
        return Message.builder()
                .id(5L)
                .conversation(conversation)
                .role("user")
                .content("Có bao nhiêu khách hàng?")
                .generatedSql("SELECT COUNT(*) FROM customers")
                .createdAt(LocalDateTime.now())
                .pinned(pinned)
                .build();
        // queryLogs mặc định = new ArrayList<>() nhờ @Builder.Default trong entity Message
    }

    // ===== getConversations =====

    @Test
    void getConversations_shouldReturnMappedConversations() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(conversationRepository.findByUserId(1L))
                .thenReturn(List.of(conversation));

        List<ConversationResponse> result =
                conversationService.getConversations("owner");

        assertEquals(1, result.size());
        assertEquals("Doanh thu theo tháng", result.get(0).getTitle());
        assertEquals(20L, result.get(0).getConnectionId());
    }

    @Test
    void getConversations_shouldThrow_whenUserNotFound() {

        when(connectionAccessGuard.requireUser("ghost"))
                .thenThrow(new ResourceNotFoundException("Không tìm thấy user"));

        assertThrows(
                ResourceNotFoundException.class,
                () -> conversationService.getConversations("ghost")
        );
    }

    // ===== getMessages =====

    @Test
    void getMessages_shouldReturnMappedMessages_withPinnedFlag() {

        Message message = sampleMessage(true);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(conversationRepository.findById(100L))
                .thenReturn(Optional.of(conversation));

        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(100L))
                .thenReturn(List.of(message));

        List<MessageResponse> result =
                conversationService.getMessages("owner", 100L);

        assertEquals(1, result.size());
        assertEquals("Có bao nhiêu khách hàng?", result.get(0).getContent());
        assertTrue(result.get(0).getPinned());
    }

    @Test
    void getMessages_shouldThrow_whenRequestedByNonOwner_IDOR() {

        when(connectionAccessGuard.requireUser("intruder"))
                .thenReturn(otherUser);

        when(conversationRepository.findById(100L))
                .thenReturn(Optional.of(conversation));

        ForbiddenResourceException ex = assertThrows(
                ForbiddenResourceException.class,
                () -> conversationService.getMessages("intruder", 100L)
        );

        assertTrue(ex.getMessage().contains("không có quyền"));

        verify(
                messageRepository,
                never()
        ).findByConversationIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    void getMessages_shouldThrow_whenConversationNotFound() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(conversationRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> conversationService.getMessages("owner", 999L)
        );
    }

    // ===== deleteConversation / deleteAllConversations =====

    @Test
    void deleteConversation_shouldDelete_whenRequestedByOwner() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(conversationRepository.findById(100L))
                .thenReturn(Optional.of(conversation));

        conversationService.deleteConversation("owner", 100L);

        verify(conversationRepository).delete(conversation);
    }

    @Test
    void deleteConversation_shouldThrow_whenRequestedByNonOwner_IDOR() {

        when(connectionAccessGuard.requireUser("intruder"))
                .thenReturn(otherUser);

        when(conversationRepository.findById(100L))
                .thenReturn(Optional.of(conversation));

        ForbiddenResourceException ex = assertThrows(
                ForbiddenResourceException.class,
                () -> conversationService.deleteConversation("intruder", 100L)
        );

        assertTrue(ex.getMessage().contains("không có quyền"));

        verify(
                conversationRepository,
                never()
        ).delete(any());
    }

    @Test
    void deleteAllConversations_shouldDeleteAllOfThatUser() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(conversationRepository.findByUserId(1L))
                .thenReturn(List.of(conversation));

        conversationService.deleteAllConversations("owner");

        verify(conversationRepository).deleteAll(List.of(conversation));
    }

    // ===== togglePin =====

    @Test
    void togglePin_shouldSetPinnedTrue_whenCurrentlyUnpinned() {

        Message message = sampleMessage(false);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.findByIdWithOwner(5L))
                .thenReturn(Optional.of(message));

        MessageResponse response =
                conversationService.togglePin("owner", 5L);

        assertTrue(response.getPinned());
        assertTrue(message.getPinned());

        verify(messageRepository).save(message);
    }

    @Test
    void togglePin_shouldSetPinnedFalse_whenCurrentlyPinned() {

        Message message = sampleMessage(true);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.findByIdWithOwner(5L))
                .thenReturn(Optional.of(message));

        MessageResponse response =
                conversationService.togglePin("owner", 5L);

        assertFalse(response.getPinned());
        assertFalse(message.getPinned());
    }

    @Test
    void togglePin_shouldThrow_whenMessageNotFound() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.findByIdWithOwner(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> conversationService.togglePin("owner", 999L)
        );

        verify(
                messageRepository,
                never()
        ).save(any());
    }

    @Test
    void togglePin_shouldThrow_whenRequestedByNonOwner_IDOR() {

        Message message = sampleMessage(false);

        when(connectionAccessGuard.requireUser("intruder"))
                .thenReturn(otherUser);

        when(messageRepository.findByIdWithOwner(5L))
                .thenReturn(Optional.of(message));

        ForbiddenResourceException ex = assertThrows(
                ForbiddenResourceException.class,
                () -> conversationService.togglePin("intruder", 5L)
        );

        assertTrue(ex.getMessage().contains("không có quyền"));

        verify(
                messageRepository,
                never()
        ).save(any());
    }

    // ===== getPinnedMessages =====

    @Test
    void getPinnedMessages_shouldReturnOnlyPinnedMappedMessages() {

        Message pinned = sampleMessage(true);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.findPinnedByUserId(1L))
                .thenReturn(List.of(pinned));

        List<MessageResponse> result =
                conversationService.getPinnedMessages("owner");

        assertEquals(1, result.size());
        assertTrue(result.get(0).getPinned());
    }

    @Test
    void getPinnedMessages_shouldReturnEmptyList_whenNoneArePinned() {

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.findPinnedByUserId(1L))
                .thenReturn(List.of());

        List<MessageResponse> result =
                conversationService.getPinnedMessages("owner");

        assertTrue(result.isEmpty());
    }

    // ===== searchMessages =====

    @Test
    void searchMessages_shouldMapPageContentCorrectly() {

        Message message = sampleMessage(false);

        Pageable pageable = PageRequest.of(0, 10);

        Page<Message> page =
                new PageImpl<>(
                        List.of(message),
                        pageable,
                        1
                );

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.searchByUser(
                eq(1L),
                eq("doanh thu"),
                eq(false),
                eq(pageable)
        )).thenReturn(page);

        Page<MessageSearchResultResponse> result =
                conversationService.searchMessages(
                        "owner",
                        "doanh thu",
                        false,
                        pageable
                );

        assertEquals(1, result.getTotalElements());

        MessageSearchResultResponse item =
                result.getContent().get(0);

        assertEquals(5L, item.getMessageId());
        assertEquals(100L, item.getConversationId());

        assertEquals(
                "Doanh thu theo tháng",
                item.getConversationTitle()
        );

        assertFalse(item.getPinned());
    }

    @Test
    void searchMessages_shouldTrimKeyword_beforeQueryingRepository() {

        Pageable pageable = PageRequest.of(0, 10);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.searchByUser(
                anyLong(),
                anyString(),
                anyBoolean(),
                any()
        )).thenReturn(
                new PageImpl<>(List.of())
        );

        conversationService.searchMessages(
                "owner",
                "  doanh thu  ",
                false,
                pageable
        );

        ArgumentCaptor<String> keywordCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(messageRepository).searchByUser(
                eq(1L),
                keywordCaptor.capture(),
                eq(false),
                eq(pageable)
        );

        assertEquals(
                "doanh thu",
                keywordCaptor.getValue()
        );
    }

    @Test
    void searchMessages_shouldTreatNullKeyword_asEmptyString() {

        Pageable pageable = PageRequest.of(0, 10);

        when(connectionAccessGuard.requireUser("owner"))
                .thenReturn(owner);

        when(messageRepository.searchByUser(
                anyLong(),
                anyString(),
                anyBoolean(),
                any()
        )).thenReturn(
                new PageImpl<>(List.of())
        );

        conversationService.searchMessages(
                "owner",
                null,
                true,
                pageable
        );

        verify(messageRepository).searchByUser(
                eq(1L),
                eq(""),
                eq(true),
                eq(pageable)
        );
    }
}