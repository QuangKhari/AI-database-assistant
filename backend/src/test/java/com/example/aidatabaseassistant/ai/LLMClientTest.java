package com.example.aidatabaseassistant.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Test cho co che retry/timeout khi goi Gemini API.
 *
 * Bao phu cac tinh huong ma checklist "AI failure / timeout" yeu cau:
 *  - Timeout/network loi tam thoi -> retry va cuoi cung thanh cong.
 *  - Loi 429 (rate limit) -> retry.
 *  - Loi 5xx -> retry.
 *  - Loi 4xx khac (400) -> KHONG retry, nem loi ngay.
 *  - Loi lien tuc vuot qua so lan retry cho phep -> that bai voi thong bao
 *    than thien, khong lo chi tiet ky thuat cho nguoi dung cuoi.
 */
@ExtendWith(MockitoExtension.class)
class LLMClientTest {

    @Mock
    private RestTemplate restTemplate;

    private LLMClient llmClient;

    @SuppressWarnings("unchecked")
    private final Map<String, Object> successBody = Map.of(
            "candidates", List.of(
                    Map.of("content", Map.of(
                            "parts", List.of(Map.of("text", "SELECT 1"))
                    ))
            )
    );

    @BeforeEach
    void setUp() {
        llmClient = new LLMClient(restTemplate);
        ReflectionTestUtils.setField(llmClient, "apiKey", "fake-key");
        ReflectionTestUtils.setField(llmClient, "apiUrl", "https://fake-gemini/generate");
        ReflectionTestUtils.setField(llmClient, "embeddingApiUrl", "https://fake-gemini/embed");
    }

    @Test
    void generateResponse_shouldSucceed_whenFirstCallOk() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(successBody));

        String result = llmClient.generateResponse("cau hoi");

        assertEquals("SELECT 1", result);
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void generateResponse_shouldRetryThenSucceed_onTimeoutThenOk() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Connection timed out"))
                .thenReturn(ResponseEntity.ok(successBody));

        String result = llmClient.generateResponse("cau hoi");

        assertEquals("SELECT 1", result);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void generateResponse_shouldRetry_on429ThenSucceed() {
        HttpClientErrorException tooManyRequests =
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", null, null, null);

        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(tooManyRequests)
                .thenReturn(ResponseEntity.ok(successBody));

        String result = llmClient.generateResponse("cau hoi");

        assertEquals("SELECT 1", result);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void generateResponse_shouldRetry_on5xxThenSucceed() {
        HttpServerErrorException serverError =
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable", null, null, null);

        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(serverError)
                .thenReturn(ResponseEntity.ok(successBody));

        String result = llmClient.generateResponse("cau hoi");

        assertEquals("SELECT 1", result);
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void generateResponse_shouldNotRetry_on400BadRequest() {
        HttpClientErrorException badRequest =
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                        "Bad Request", null, null, null);

        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(badRequest);

        assertThrows(HttpClientErrorException.class,
                () -> llmClient.generateResponse("cau hoi"));

        // Khong duoc retry loi 400 - chi goi dung 1 lan.
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void generateResponse_shouldFailWithFriendlyMessage_whenAllRetriesExhausted() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Connection timed out"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> llmClient.generateResponse("cau hoi"));

        assertTrue(ex.getMessage().contains("Không thể kết nối tới dịch vụ AI"));

        // 1 lan dau + 2 lan retry = 3 lan goi tong cong.
        verify(restTemplate, times(3)).postForEntity(anyString(), any(), eq(Map.class));
    }
}