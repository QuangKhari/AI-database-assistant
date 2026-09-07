package com.example.aidatabaseassistant.contract;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.ConflictException;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ConnectionService;
import com.example.aidatabaseassistant.service.RateLimitService;
import com.example.aidatabaseassistant.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test "hop dong loi" (Phan 2.6 / 3.7 ke hoach hoan thien he thong) - kiem
 * tra CO CHE xu ly loi chung cua toan Backend (GlobalExceptionHandler +
 * RestAuthenticationEntryPoint + RestAccessDeniedHandler), KHONG kiem tra
 * nghiep vu tung service (nghiep vu da co test rieng o service/*Test.java).
 *
 * Dung GET /api/connections va GET /api/connections/{id} (qua
 * @MockitoBean ConnectionService) lam "be mat" chung, vi Controller nay
 * don gian, khong co side-effect, phu hop de mo phong moi loai loi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ConnectionService connectionService;

    @MockitoBean
    private RateLimitService rateLimitService;

    private String userToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = User.builder()
                .username("contractuser")
                .email("contractuser@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(user);

        userToken = jwtUtil.generateToken("contractuser");

        // Mac dinh cho phep (test 429 se override rieng)
        when(rateLimitService.tryConsume(anyString())).thenReturn(true);
    }

    // -----------------------------------------------------------
    // 401 - khong co token
    // -----------------------------------------------------------
    @Test
    void shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/connections"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/connections"));
    }

    // -----------------------------------------------------------
    // 401 - token het han
    // -----------------------------------------------------------
    @Test
    void shouldReturn401_whenTokenExpired() throws Exception {
        String expired = JwtTestSupport.expiredToken("contractuser");

        mockMvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------
    // 401 - token khong hop le (malformed / sai chu ky)
    // -----------------------------------------------------------
    @Test
    void shouldReturn401_whenTokenMalformed() throws Exception {
        mockMvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + JwtTestSupport.malformedToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401_whenTokenSignedWithWrongSecret() throws Exception {
        String forged = JwtTestSupport.tokenSignedWithWrongSecret("contractuser");

        mockMvc.perform(get("/api/connections")
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------
    // 403 - IDOR / khong co quyen (dung ForbiddenResourceException)
    // -----------------------------------------------------------
    @Test
    void shouldReturn403_whenForbiddenResourceException() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(999L)))
                .thenThrow(new ForbiddenResourceException("Bạn không có quyền truy cập connection này"));

        mockMvc.perform(get("/api/connections/999")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // -----------------------------------------------------------
    // 404 - resource khong ton tai
    // -----------------------------------------------------------
    @Test
    void shouldReturn404_whenResourceNotFound() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(404L)))
                .thenThrow(new ResourceNotFoundException("Không tìm thấy connection"));

        mockMvc.perform(get("/api/connections/404")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // -----------------------------------------------------------
    // 409 - xung dot nghiep vu
    // -----------------------------------------------------------
    @Test
    void shouldReturn409_whenConflictException() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(1L)))
                .thenThrow(new ConflictException("Conversation không thuộc connection này"));

        mockMvc.perform(get("/api/connections/1")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    // -----------------------------------------------------------
    // 400 - validation fail (thieu field bat buoc khi dang ky)
    // -----------------------------------------------------------
    @Test
    void shouldReturn400_withFieldErrors_whenRegisterRequestInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "username": "ab", "email": "not-an-email", "password": "123" }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    // -----------------------------------------------------------
    // 429 - rate limit + Retry-After header
    // -----------------------------------------------------------
    @Test
    void shouldReturn429_withRetryAfterHeader_whenRateLimitExceeded() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(1L)))
                .thenThrow(new RateLimitExceededException(
                        "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau 1 phút", 60));

        mockMvc.perform(get("/api/connections/1")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    // -----------------------------------------------------------
    // 500 - exception gia lap, KHONG lo thong tin noi bo
    // -----------------------------------------------------------
    @Test
    void shouldReturn500_withGenericMessage_whenUnexpectedException() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(1L)))
                .thenThrow(new RuntimeException(
                        "jdbc:mysql://internal-db:3306/secret?password=SuperSecret123"));

        mockMvc.perform(get("/api/connections/1")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // Message tra ve PHAI la thong bao chung, KHONG duoc chua
                // noi dung that cua exception (connection string, password...).
                .andExpect(jsonPath("$.message").value("Đã có lỗi xảy ra. Vui lòng thử lại sau."))
                .andExpect(jsonPath("$.message", not(containsString("jdbc"))))
                .andExpect(jsonPath("$.message", not(containsString("password"))))
                .andExpect(jsonPath("$.message", not(containsString("SuperSecret123"))))
                .andExpect(jsonPath("$.message", not(containsString("RuntimeException"))))
                .andExpect(jsonPath("$.message", not(containsString("at com.example"))));
    }

    // -----------------------------------------------------------
    // correlationId phai co mat de tra vet loi (Phan 3.8 tieu chi nghiem thu)
    // -----------------------------------------------------------
    @Test
    void errorResponse_shouldAlwaysIncludeCorrelationId() throws Exception {
        when(connectionService.getConnection(eq("contractuser"), eq(1L)))
                .thenThrow(new ResourceNotFoundException("Không tìm thấy connection"));

        mockMvc.perform(get("/api/connections/1")
                        .header("Authorization", "Bearer " + userToken)
                        .header("X-Correlation-Id", "test-trace-123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").exists());
    }
}