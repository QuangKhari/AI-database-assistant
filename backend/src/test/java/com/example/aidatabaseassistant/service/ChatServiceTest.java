package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.ConversationContextMessage;
import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.dto.ChatPreviewRequest;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock UserRepository userRepository;
    @Mock ConnectionService connectionService;
    @Mock DatabaseSchemaRepository schemaRepository;
    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository messageRepository;
    @Mock NL2SQLEngine engine;
    @Mock RateLimitService rateLimitService;
    private ChatService service;
    private User user;
    private DatabaseConnection connection;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {
        service = new ChatService(userRepository, connectionService, schemaRepository,
                conversationRepository, messageRepository, engine, new QueryValidator(), rateLimitService);
        user = User.builder().id(1L).username("student").build();
        connection = DatabaseConnection.builder().id(2L).user(user).active(true).build();
        schema = DatabaseSchema.builder().id(3L).connection(connection)
                .tables(List.of(TableMetadata.builder().name("orders").build())).build();
    }

    @Test
    void existingConversationSendsOnlyThreeRecentTurnsToAi() {
        ChatPreviewRequest request = new ChatPreviewRequest();
        request.setConnectionId(2L);
        request.setConversationId(4L);
        request.setQuestion("Còn tháng trước thì sao?");
        Conversation conversation = Conversation.builder().id(4L).user(user).connection(connection).build();
        List<Message> recent = List.of(
                message("assistant", "a3", "SELECT 3"), message("user", "q3", null),
                message("assistant", "a2", "SELECT 2"), message("user", "q2", null),
                message("assistant", "a1", "SELECT 1"), message("user", "q1", null));

        when(rateLimitService.tryConsume("student")).thenReturn(true);
        when(userRepository.findByUsernameIgnoreCase("student")).thenReturn(Optional.of(user));
        when(connectionService.getOwnedActiveConnection("student", 2L)).thenReturn(connection);
        when(schemaRepository.findByConnectionId(2L)).thenReturn(Optional.of(schema));
        when(conversationRepository.findByIdAndUserUsernameIgnoreCaseAndConnectionId(4L, "student", 2L))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findTop6ByConversationIdOrderByCreatedAtDesc(4L)).thenReturn(recent);
        when(engine.generateSQL(eq("Còn tháng trước thì sao?"), eq(schema), any()))
                .thenReturn("SELECT * FROM orders");
        AtomicLong ids = new AtomicLong(10);
        when(messageRepository.save(any())).thenAnswer(invocation -> {
            Message value = invocation.getArgument(0);
            value.setId(ids.incrementAndGet());
            return value;
        });

        var response = service.preview("student", request);

        assertThat(response.valid()).isTrue();
        ArgumentCaptor<List<ConversationContextMessage>> contextCaptor = ArgumentCaptor.forClass(List.class);
        verify(engine).generateSQL(eq("Còn tháng trước thì sao?"), eq(schema), contextCaptor.capture());
        assertThat(contextCaptor.getValue()).hasSize(6);
        assertThat(contextCaptor.getValue().get(0).content()).isEqualTo("q1");
        assertThat(contextCaptor.getValue().get(5).content()).isEqualTo("a3");
    }

    private Message message(String role, String content, String sql) {
        return Message.builder().role(role).content(content).generatedSql(sql).build();
    }
}
