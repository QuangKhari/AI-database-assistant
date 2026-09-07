package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerStreamingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private QueryService queryService;

    private String validToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User user = User.builder().username("khai").email("khai@example.com")
                .passwordHash("hash").role(Role.USER).locked(false).build();
        userRepository.save(user);
        validToken = jwtUtil.generateToken("khai");
    }

    @Test
    void executeStream_shouldReturnSseContentType_andCompleteWithResult() throws Exception {

        SseEmitter fakeEmitter = new SseEmitter(5_000L);
        when(queryService.processQueryStreaming(eq("khai"), any())).thenReturn(fakeEmitter);

        fakeEmitter.send(SseEmitter.event().name("STATUS").data("Đang tải schema..."));
        fakeEmitter.send(SseEmitter.event().name("STATUS").data("Đang sinh SQL..."));
        fakeEmitter.send(SseEmitter.event().name("result")
                .data(new QueryResponse(1L, 1L, "SELECT 1", null, "ok", 1, null, null)));
        fakeEmitter.complete();

        // KHÔNG gọi asyncDispatch() nữa — vì send()/complete() đã chạy TRƯỚC
        // perform(), Spring MVC flush toàn bộ dữ liệu SSE ngay trong lượt
        // dispatch đầu tiên (ResponseBodyEmitterReturnValueHandler xử lý đồng
        // bộ trên chính thread test). Gọi asyncDispatch() thêm 1 lần chỉ để
        // "chốt" trạng thái async của MockMvc, nhưng nó khiến toàn bộ Security
        // filter chain (đặc biệt AuthorizationFilter) chạy lại trên cùng thread
        // trong khi SecurityContextHolder đã bị Spring Security dọn sau lượt
        // đầu — không phản ánh đúng hành vi thật của Tomcat, chỉ là giới hạn
        // của việc mô phỏng async trong MockMvc + JWT stateless.
        mockMvc.perform(
                        post("/api/query/execute/stream")
                                .header("Authorization", "Bearer " + validToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"question": "Doanh thu tháng 1", "databaseConnectionId": 1}
                                    """))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STATUS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("result")));
    }

    @Test
    void executeStream_withoutToken_shouldBeUnauthorized() throws Exception {
        mockMvc.perform(post("/api/query/execute/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "Doanh thu tháng 1", "databaseConnectionId": 1}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executeStream_shouldEmitErrorEvent_andEndStream_whenServiceFails() throws Exception {

        SseEmitter fakeEmitter = new SseEmitter(5_000L);
        when(queryService.processQueryStreaming(eq("khai"), any())).thenReturn(fakeEmitter);

        // Mo phong dung thu tu that: STATUS... roi den error (khong co "result")
        fakeEmitter.send(SseEmitter.event().name("STATUS").data("Đang sinh SQL..."));
        fakeEmitter.send(SseEmitter.event().name("error").data("Không thể sinh SQL hợp lệ sau 3 lần thử"));
        fakeEmitter.complete();

        mockMvc.perform(
                        post("/api/query/execute/stream")
                                .header("Authorization", "Bearer " + validToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"question": "Câu hỏi không hợp lệ", "databaseConnectionId": 1}
                                    """))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:STATUS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                // "error" phai xuat hien SAU "STATUS" va KHONG co "result" trong stream
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    int statusIdx = body.indexOf("event:STATUS");
                    int errorIdx = body.indexOf("event:error");
                    org.junit.jupiter.api.Assertions.assertTrue(statusIdx >= 0 && errorIdx > statusIdx,
                            "Event 'error' phải xuất hiện sau 'STATUS'");
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("event:result"),
                            "Khi có lỗi thì KHÔNG được có event 'result'");
                });
    }
}