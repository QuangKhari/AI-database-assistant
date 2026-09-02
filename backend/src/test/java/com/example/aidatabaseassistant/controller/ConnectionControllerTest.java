package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import com.example.aidatabaseassistant.service.SuggestedQuestionService;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConnectionControllerTest {

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SchemaDiscoveryService schemaDiscoveryService;

    @Mock
    private SuggestedQuestionService suggestedQuestionService;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private Authentication authentication;

    private ConnectionController connectionController;

    @BeforeEach
    void setUp() {
        connectionController = new ConnectionController(
                connectionService,
                schemaDiscoveryService,
                suggestedQuestionService,
                rateLimitService
        );
    }

    @Test
    void getSuggestedQuestions_shouldThrowRateLimitExceeded_whenRateLimitIsExceeded() {

        when(authentication.getName()).thenReturn("owner");
        when(rateLimitService.tryConsume("owner")).thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> connectionController.getSuggestedQuestions(
                        authentication,
                        10L,
                        true
                )
        );

        verify(rateLimitService).tryConsume("owner");

        // Quan trọng: bị rate limit thì không được gọi Gemini/service phía sau
        verifyNoInteractions(suggestedQuestionService);
    }
}