package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.dto.ConnectionResponse;
import com.example.aidatabaseassistant.dto.ConnectionUpdateRequest;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.service.SchemaDiscoveryService;
import com.example.aidatabaseassistant.service.SuggestedQuestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import com.example.aidatabaseassistant.dto.ConnectionTestResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        // Bị rate limit thì không được gọi service phía sau.
        verifyNoInteractions(suggestedQuestionService);
    }

    @Test
    void uploadExcelConnection_shouldDelegateToService() {

        when(authentication.getName())
                .thenReturn("owner");

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

        when(
                connectionService.saveExcelConnection(
                        eq("owner"),
                        any(),
                        eq("Sales")
                )
        ).thenReturn(expected);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "sales.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[]{1, 2, 3}
                );

        var response =
                connectionController.uploadExcelConnection(
                        authentication,
                        file,
                        "Sales"
                );

        assertEquals(
                expected,
                response.getBody()
        );

        verify(connectionService)
                .saveExcelConnection(
                        eq("owner"),
                        same(file),
                        eq("Sales")
                );
    }

    @Test
    void getConnection_shouldDelegateUsernameAndIdToService() {

        when(authentication.getName())
                .thenReturn("owner");

        ConnectionResponse expected =
                new ConnectionResponse(
                        10L,
                        "My DB",
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        true,
                        null,
                        null,
                        null,
                        null
                );

        when(
                connectionService.getConnection(
                        "owner",
                        10L
                )
        ).thenReturn(expected);

        var response =
                connectionController.getConnection(
                        authentication,
                        10L
                );

        assertEquals(
                expected,
                response.getBody()
        );

        verify(connectionService)
                .getConnection(
                        "owner",
                        10L
                );
    }

    @Test
    void updateConnection_shouldDelegateUsernameIdAndRequestToService() {

        when(authentication.getName())
                .thenReturn("owner");

        ConnectionUpdateRequest request =
                new ConnectionUpdateRequest();

        request.setName("Updated DB");
        request.setHost("localhost");
        request.setPort(3306);
        request.setDatabaseName("shop");
        request.setUsername("root");

        ConnectionResponse expected =
                new ConnectionResponse(
                        10L,
                        "Updated DB",
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        true,
                        null,
                        null,
                        null,
                        null
                );

        when(
                connectionService.updateConnection(
                        "owner",
                        10L,
                        request
                )
        ).thenReturn(expected);

        var response =
                connectionController.updateConnection(
                        authentication,
                        10L,
                        request
                );

        assertEquals(
                expected,
                response.getBody()
        );

        verify(connectionService)
                .updateConnection(
                        "owner",
                        10L,
                        request
                );
    }

    @Test
    void reconnect_shouldDelegateUsernameAndIdToService() {

        when(authentication.getName())
                .thenReturn("owner");

        ConnectionTestResult expected =
                new ConnectionTestResult(
                        true,
                        false,
                        "CONNECTION_OK",
                        "Kết nối database thành công.",
                        100L,
                        null
                );

        when(
                connectionService.reconnect(
                        "owner",
                        10L
                )
        ).thenReturn(expected);

        var response =
                connectionController.reconnect(
                        authentication,
                        10L
                );

        assertEquals(
                expected,
                response.getBody()
        );

        verify(connectionService)
                .reconnect(
                        "owner",
                        10L
                );
    }

    @Test
    void disconnect_shouldDelegateUsernameAndIdToService() {

        when(authentication.getName())
                .thenReturn("owner");

        var response =
                connectionController.disconnect(
                        authentication,
                        10L
                );

        assertEquals(
                204,
                response.getStatusCode().value()
        );

        verify(connectionService)
                .disconnect(
                        "owner",
                        10L
                );
    }

    @Test
    void disconnect_shouldUseAuthenticatedUsername_notClientProvidedUsername() {

        when(authentication.getName())
                .thenReturn("owner");

        connectionController.disconnect(
                authentication,
                10L
        );

        verify(connectionService)
                .disconnect(
                        eq("owner"),
                        eq(10L)
                );

        verify(connectionService, never())
                .disconnect(
                        eq("intruder"),
                        eq(10L)
                );
    }
}