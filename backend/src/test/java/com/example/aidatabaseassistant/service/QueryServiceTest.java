package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.ai.LLMClient;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.ChartSuggestionResponse;
import com.example.aidatabaseassistant.dto.ChartType;
import com.example.aidatabaseassistant.dto.DataInsightResponse;
import com.example.aidatabaseassistant.dto.PreviewResponse;
import com.example.aidatabaseassistant.dto.QueryRequest;
import com.example.aidatabaseassistant.dto.QueryResponse;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.dto.TrendDirection;
import com.example.aidatabaseassistant.entity.Conversation;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.Message;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.RateLimitExceededException;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.query.SQLCorrectionService;
import com.example.aidatabaseassistant.repository.ConversationRepository;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.MessageRepository;
import com.example.aidatabaseassistant.repository.QueryLogRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private DatabaseConnectionRepository connectionRepository;
    @Mock
    private DatabaseSchemaRepository schemaRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private QueryLogRepository queryLogRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private NL2SQLEngine nl2SQLEngine;
    @Mock
    private QueryValidator queryValidator;
    @Mock
    private SQLCorrectionService sqlCorrectionService;
    @Mock
    private LLMClient llmClient;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ChartSuggestionService chartSuggestionService;
    @Mock
    private SchemaLoaderService schemaLoaderService;
    @Mock
    private DataInsightService dataInsightService;
    @Mock
    private SchemaRetrievalService schemaRetrievalService;
    @Mock
    private Executor sseTaskExecutor;

    private QueryService queryService;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;
    private DatabaseSchema schema;

    @BeforeEach
    void setUp() {
        // schemaRepository KHONG con duoc QueryService goi truc tiep nua -
        // viec load schema day du (bao gom tables/columns) da duoc gom vao
        // SchemaLoaderService.loadCompleteSchema(). Mock schemaRepository
        // van duoc giu lai vi la tham so constructor bat buoc (Lombok
        // @RequiredArgsConstructor), nhung KHONG duoc stub truc tiep trong
        // cac test ben duoi (se gay UnnecessaryStubbingException).
        queryService = new QueryService(
                userRepository, connectionRepository, schemaRepository, conversationRepository,
                messageRepository, queryLogRepository, encryptionUtil, nl2SQLEngine, queryValidator,
                sqlCorrectionService, llmClient, rateLimitService, chartSuggestionService,
                schemaLoaderService, dataInsightService, schemaRetrievalService, sseTaskExecutor);

        // modelUrl la field @Value, KHONG duoc Lombok dua vao constructor vi
        // khong phai final - phai bom bang reflection, giong cach da lam o
        // BenchmarkServiceTest.
        ReflectionTestUtils.setField(queryService, "modelUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");

        owner = User.builder().id(1L).username("owner").build();
        otherUser = User.builder().id(2L).username("intruder").build();
        connection = DatabaseConnection.builder()
                .id(10L).user(owner).host("localhost").port(3306)
                .databaseName("shop_db").username("dbuser").encryptedPassword("enc-pass")
                .build();
        schema = DatabaseSchema.builder().id(100L).connection(connection).build();
    }

    private QueryRequest buildRequest(String question, Long connectionId, Long conversationId) {
        QueryRequest request = new QueryRequest();
        request.setQuestion(question);
        request.setDatabaseConnectionId(connectionId);
        request.setConversationId(conversationId);
        return request;
    }

    /**
     * AttemptResult/AttemptLog chi co @Getter, khong co setter/builder cong
     * khai (thiet ke co chu dich cua SQLCorrectionService), nen phai dung
     * ReflectionTestUtils de fabricate ket qua tra ve tu mock cua no.
     */
    private SQLCorrectionService.AttemptResult buildAttemptResult(boolean success, String sql,
                                                                  QueryResultDto finalResult,
                                                                  List<SQLCorrectionService.AttemptLog> logs) {
        SQLCorrectionService.AttemptResult result = new SQLCorrectionService.AttemptResult();
        ReflectionTestUtils.setField(result, "success", success);
        ReflectionTestUtils.setField(result, "sql", sql);
        ReflectionTestUtils.setField(result, "finalResult", finalResult);
        ReflectionTestUtils.setField(result, "attemptLogs", logs);
        return result;
    }

    private SQLCorrectionService.AttemptLog buildAttemptLog(String sql, boolean success, QueryResultDto result) {
        SQLCorrectionService.AttemptLog log = new SQLCorrectionService.AttemptLog();
        ReflectionTestUtils.setField(log, "sql", sql);
        ReflectionTestUtils.setField(log, "success", success);
        ReflectionTestUtils.setField(log, "result", result);
        return log;
    }

    private void stubMessageSaveAssignsId() {
        // Mock repository khong tu sinh ID nhu JPA that, nen phai gia lap
        // hanh vi "persist" bang cach gan ID thu cong trong Answer.
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message m = invocation.getArgument(0);
            if (m.getId() == null) {
                m.setId("user".equals(m.getRole()) ? 900L : 901L);
            }
            return m;
        });
    }

    private void stubConversationSaveAssignsId() {
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation c = invocation.getArgument(0);
            c.setId(500L);
            return c;
        });
    }

    // ===================== previewQuery =====================

    @Test
    void previewQuery_shouldReturnValid_whenSqlPassesValidation() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(nl2SQLEngine.generateSQL(request.getQuestion(), schema)).thenReturn("SELECT * FROM orders");

        PreviewResponse response = queryService.previewQuery("owner", request);

        assertTrue(response.isValid());
        assertEquals("SELECT * FROM orders", response.getGeneratedSql());
        verifyNoInteractions(sqlCorrectionService, chartSuggestionService, dataInsightService);
    }

    @Test
    void previewQuery_shouldReturnInvalid_whenValidationFails() {
        QueryRequest request = buildRequest("Xoá hết đơn hàng", 10L, null);

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(nl2SQLEngine.generateSQL(request.getQuestion(), schema)).thenReturn("DELETE FROM orders");
        doThrow(new IllegalArgumentException("Chỉ cho phép câu lệnh SELECT"))
                .when(queryValidator).validate("DELETE FROM orders", schema);

        PreviewResponse response = queryService.previewQuery("owner", request);

        assertFalse(response.isValid());
        assertEquals("Chỉ cho phép câu lệnh SELECT", response.getErrorMessage());
    }

    @Test
    void previewQuery_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        QueryRequest request = buildRequest("Bất kỳ câu hỏi nào", 10L, null);

        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        assertThrows(IllegalArgumentException.class, () -> queryService.previewQuery("intruder", request));
        verifyNoInteractions(nl2SQLEngine, queryValidator, schemaLoaderService, schemaRetrievalService);
    }

    // ===================== processQuery: guard clauses =====================

    @Test
    void processQuery_shouldThrow_whenRateLimitExceeded() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);
        when(rateLimitService.tryConsume("owner")).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> queryService.processQuery("owner", request));

        // Bi chan ngay tu dau, tuyet doi khong duoc dong cham DB hay goi AI.
        verifyNoInteractions(userRepository, connectionRepository, schemaLoaderService,
                schemaRetrievalService, sqlCorrectionService, chartSuggestionService, dataInsightService);
    }

    @Test
    void processQuery_shouldThrow_whenUserNotFound() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);
        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> queryService.processQuery("owner", request));
    }

    @Test
    void processQuery_shouldThrow_whenConnectionNotFound() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 999L, null);
        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> queryService.processQuery("owner", request));
    }

    @Test
    void processQuery_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);
        when(rateLimitService.tryConsume("intruder")).thenReturn(true);
        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        assertThrows(IllegalArgumentException.class, () -> queryService.processQuery("intruder", request));
        verifyNoInteractions(sqlCorrectionService, chartSuggestionService, dataInsightService);
    }

    @Test
    void processQuery_shouldThrow_whenSchemaNotDiscovered() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);
        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L))
                .thenThrow(new IllegalArgumentException("Chưa discover schema cho connection này"));

        assertThrows(IllegalArgumentException.class, () -> queryService.processQuery("owner", request));
    }

    // ===================== processQuery: luồng thành công (cũ) =====================

    @Test
    void processQuery_shouldCreateNewConversation_whenConversationIdNotProvided() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(
                List.of("thang", "doanh_thu"),
                List.of(Map.of("thang", 1, "doanh_thu", 1000)),
                42L, 1, null);
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);

        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt kết quả");
        when(chartSuggestionService.suggest(finalResult.getColumns(), finalResult.getRows()))
                .thenReturn(new ChartSuggestionResponse(ChartType.BAR, List.of(ChartType.LINE),
                        "thang", List.of("1"), List.of(), "vì lý do gì đó"));

        QueryResponse response = queryService.processQuery("owner", request);

        assertEquals(500L, response.getConversationId());
        assertEquals("SELECT thang, doanh_thu FROM revenue", response.getGeneratedSql());
        assertEquals(1, response.getAttemptCount());
        verify(conversationRepository).save(any(Conversation.class));
        // Phai gui dung username/connection cua nguoi so huu, khong phai request truyen len.
        verify(conversationRepository, never()).findById(anyLong());
    }

    @Test
    void processQuery_shouldReuseExistingConversation_whenConversationIdProvided() {
        QueryRequest request = buildRequest("Tiếp tục câu hỏi trước", 10L, 500L);
        Conversation existingConversation = Conversation.builder().id(500L).user(owner).connection(connection).build();

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(conversationRepository.findById(500L)).thenReturn(Optional.of(existingConversation));
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(List.of("col"), List.of(Map.of("col", 1)), 10L, 1, null);
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT col FROM t", finalResult,
                List.of(buildAttemptLog("SELECT col FROM t", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt");
        when(chartSuggestionService.suggest(anyList(), anyList()))
                .thenReturn(new ChartSuggestionResponse(ChartType.TABLE, List.of(), null, List.of(), List.of(), "Không có dữ liệu"));

        QueryResponse response = queryService.processQuery("owner", request);

        assertEquals(500L, response.getConversationId());
        verify(conversationRepository, never()).save(any(Conversation.class));
        verify(conversationRepository).findById(500L);
    }

    @Test
    void processQuery_shouldSaveOneQueryLogPerAttempt() {
        QueryRequest request = buildRequest("Câu hỏi khó, cần tự sửa SQL", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(List.of("col"), List.of(Map.of("col", 1)), 20L, 1, null);
        List<SQLCorrectionService.AttemptLog> logs = new ArrayList<>();
        logs.add(buildAttemptLog("SELECT sai cu phap", false,
                new QueryResultDto(List.of(), List.of(), 0, 0, "Lỗi cú pháp")));
        logs.add(buildAttemptLog("SELECT col FROM t", true, finalResult));

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT col FROM t", finalResult, logs);
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt");
        when(chartSuggestionService.suggest(anyList(), anyList()))
                .thenReturn(new ChartSuggestionResponse(ChartType.TABLE, List.of(), null, List.of(), List.of(), "reason"));

        QueryResponse response = queryService.processQuery("owner", request);

        assertEquals(2, response.getAttemptCount());
        verify(queryLogRepository, times(2)).save(any());
    }

    @Test
    void processQuery_shouldReturnNullSummaryAndChartSuggestion_whenAllAttemptsFail() {
        QueryRequest request = buildRequest("Câu hỏi không sinh được SQL hợp lệ", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto failedResult = new QueryResultDto(List.of(), List.of(), 0, 0, "Không thể sinh SQL hợp lệ");
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                false, "SELECT sai", failedResult,
                List.of(buildAttemptLog("SELECT sai", false, failedResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);

        QueryResponse response = queryService.processQuery("owner", request);

        assertNull(response.getSummary());
        assertNull(response.getChartSuggestion());
        assertNull(response.getDataInsight());
        // That bai thi tuyet doi khong duoc goi AI de tom tat hay de xuat
        // chart/insight - vua sai logic, vua ton quota.
        verifyNoInteractions(llmClient, chartSuggestionService, dataInsightService);
    }

    // ===================== processQuery: AI Summary fallback (safeSummarize) =====================

    @Test
    void processQuery_shouldReturnFallbackSummary_whenLlmClientThrowsDuringSummarize() {
        // Bao ve dung nguyen tac da de ra: AI Summary la tinh nang BO SUNG,
        // Gemini loi/timeout luc tom tat KHONG duoc lam sap ca API /execute
        // trong khi SQL da chay thanh cong. QueryService phai bat loi tu
        // safeSummarize() va tra ve cau fallback, thay vi de RuntimeException
        // tu LLMClient.generateResponse() bay thang len Controller (=> 500).
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(Map.of("thang", 1, "doanh_thu", 1000));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 20L, 1, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);

        // Mo phong dung loi thuc te cua LLMClient khi Gemini khong tra du lieu.
        when(llmClient.generateResponse(anyString()))
                .thenThrow(new RuntimeException("Gemini API không trả về dữ liệu"));

        QueryResponse response = queryService.processQuery("owner", request);

        // Du Gemini loi luc tom tat, luong chinh (SQL + ket qua) van phai
        // tra ve thanh cong nhu binh thuong.
        assertNotNull(response);
        assertEquals("SELECT thang, doanh_thu FROM revenue", response.getGeneratedSql());
        assertNotNull(response.getResult());
        assertSame(finalResult, response.getResult());

        // summary phai la cau fallback, khong duoc null va cang khong duoc
        // de exception lan ra ngoai processQuery().
        assertEquals(
                "Không thể tạo tóm tắt tự động cho kết quả này. Vui lòng xem bảng dữ liệu bên dưới.",
                response.getSummary());
    }

    @Test
    void processQuery_shouldReturnNormalSummary_whenLlmClientSucceeds() {
        // Doi chung cho test tren: khi Gemini hoat dong binh thuong,
        // safeSummarize() phai tra dung ve gia tri that cua LLMClient,
        // khong duoc luon tra ve fallback.
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(Map.of("thang", 1, "doanh_thu", 1000));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 20L, 1, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Doanh thu tháng 1 đạt 1000.");

        QueryResponse response = queryService.processQuery("owner", request);

        assertEquals("Doanh thu tháng 1 đạt 1000.", response.getSummary());
    }

    // ===================== processQuery: tích hợp chart suggestion =====================

    @Test
    void processQuery_shouldAttachChartSuggestion_whenQuerySucceeds() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                Map.of("thang", 1, "doanh_thu", 1000),
                Map.of("thang", 2, "doanh_thu", 1500));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 30L, 2, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Doanh thu tăng theo tháng");

        ChartSuggestionResponse expectedChart = new ChartSuggestionResponse(
                ChartType.LINE, List.of(ChartType.BAR), "thang", List.of("1", "2"),
                List.of(), "cột 'thang' mang tính thời gian nên phù hợp Line");
        when(chartSuggestionService.suggest(columns, rows)).thenReturn(expectedChart);

        QueryResponse response = queryService.processQuery("owner", request);

        assertSame(expectedChart, response.getChartSuggestion());
        verify(chartSuggestionService).suggest(columns, rows);
    }

    @Test
    void processQuery_shouldNotCallChartSuggestion_whenQueryFails() {
        QueryRequest request = buildRequest("Câu hỏi không hợp lệ", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto failedResult = new QueryResultDto(List.of(), List.of(), 0, 0, "Lỗi cú pháp");
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                false, "SELECT sai", failedResult,
                List.of(buildAttemptLog("SELECT sai", false, failedResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);

        QueryResponse response = queryService.processQuery("owner", request);

        assertNull(response.getChartSuggestion());
        verifyNoInteractions(chartSuggestionService);
    }

    @Test
    void processQuery_shouldReturnNullChartSuggestion_whenChartSuggestionServiceThrowsUnexpectedly() {
        // Day la diem quan trong nhat can bao ve: neu ChartSuggestionService
        // (vi du do loi logic tuong lai) nem exception, luong /execute CHINH
        // van phai tra ve thanh cong voi du lieu query, chi chartSuggestion = null.
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(Map.of("thang", 1, "doanh_thu", 1000));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 15L, 1, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt kết quả");
        when(chartSuggestionService.suggest(columns, rows))
                .thenThrow(new RuntimeException("Loi bat ngo trong chart suggestion"));

        QueryResponse response = queryService.processQuery("owner", request);

        assertNotNull(response);
        assertEquals("SELECT thang, doanh_thu FROM revenue", response.getGeneratedSql());
        assertNotNull(response.getResult());
        assertNull(response.getChartSuggestion());
    }

    // ===================== processQuery: tích hợp data insight (mới) =====================

    @Test
    void processQuery_shouldAttachDataInsight_whenQuerySucceeds() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(
                Map.of("thang", 1, "doanh_thu", 1000),
                Map.of("thang", 2, "doanh_thu", 2000));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 30L, 2, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Doanh thu tăng theo tháng");

        DataInsightResponse expectedInsight = new DataInsightResponse(
                "doanh_thu", "thang", "2", 2000.0, "1", 1000.0,
                100.0, TrendDirection.INCREASING, "1", "2", null, null, List.of(),
                "Doanh thu tăng 100% từ tháng 1 đến tháng 2.");
        when(dataInsightService.analyze(columns, rows)).thenReturn(expectedInsight);

        QueryResponse response = queryService.processQuery("owner", request);

        assertSame(expectedInsight, response.getDataInsight());
        verify(dataInsightService).analyze(columns, rows);
    }

    @Test
    void processQuery_shouldNotCallDataInsight_whenQueryFails() {
        QueryRequest request = buildRequest("Câu hỏi không hợp lệ", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto failedResult = new QueryResultDto(List.of(), List.of(), 0, 0, "Lỗi cú pháp");
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                false, "SELECT sai", failedResult,
                List.of(buildAttemptLog("SELECT sai", false, failedResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);

        QueryResponse response = queryService.processQuery("owner", request);

        assertNull(response.getDataInsight());
        verifyNoInteractions(dataInsightService);
    }

    @Test
    void processQuery_shouldReturnNullDataInsight_whenDataInsightServiceThrowsUnexpectedly() {
        // Giong chartSuggestion: neu DataInsightService nem exception bat
        // ngo, luong /execute CHINH van phai thanh cong, chi dataInsight = null
        // (FE se fallback ve hien thi "summary" thay the).
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        List<String> columns = List.of("thang", "doanh_thu");
        List<Map<String, Object>> rows = List.of(Map.of("thang", 1, "doanh_thu", 1000));
        QueryResultDto finalResult = new QueryResultDto(columns, rows, 15L, 1, null);

        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT thang, doanh_thu FROM revenue", finalResult,
                List.of(buildAttemptLog("SELECT thang, doanh_thu FROM revenue", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt kết quả");
        when(dataInsightService.analyze(columns, rows))
                .thenThrow(new RuntimeException("Loi bat ngo trong data insight"));

        QueryResponse response = queryService.processQuery("owner", request);

        assertNotNull(response);
        assertEquals("SELECT thang, doanh_thu FROM revenue", response.getGeneratedSql());
        assertNotNull(response.getResult());
        assertNull(response.getDataInsight());
    }

    @Test
    void processQuery_withExistingConversationAndPriorMessages_shouldBuildHistoryAndPassToCorrectionService() {
        QueryRequest request = buildRequest("So sánh nó với tháng 2", 10L, 500L);
        Conversation existingConversation = Conversation.builder().id(500L).user(owner).connection(connection).build();

        Message priorUserMsg = Message.builder().id(1L).conversation(existingConversation)
                .role("user").content("Doanh thu tháng 1 là bao nhiêu?").build();
        Message priorAssistantMsg = Message.builder().id(2L).conversation(existingConversation)
                .role("assistant").generatedSql("SELECT SUM(total) FROM orders WHERE MONTH(created_at)=1").build();

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(conversationRepository.findById(500L)).thenReturn(Optional.of(existingConversation));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(500L))
                .thenReturn(List.of(priorUserMsg, priorAssistantMsg));
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(List.of("col"), List.of(Map.of("col", 1)), 10L, 1, null);
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT col FROM t", finalResult,
                List.of(buildAttemptLog("SELECT col FROM t", true, finalResult)));

        // QUAN TRỌNG: stub bản 6-arg (có history), KHÔNG stub bản 5-arg cho test này
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection),
                eq("plain-pass"), contains("Doanh thu tháng 1 là bao nhiêu?")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt");
        when(chartSuggestionService.suggest(anyList(), anyList()))
                .thenReturn(new ChartSuggestionResponse(ChartType.TABLE, List.of(), null, List.of(), List.of(), "..."));

        QueryResponse response = queryService.processQuery("owner", request);

        assertEquals("SELECT col FROM t", response.getGeneratedSql());
        verify(sqlCorrectionService).run(anyString(), any(), any(), any(), anyString(),
                contains("SELECT SUM(total) FROM orders WHERE MONTH(created_at)=1")); // SQL cũ có trong history
    }

    @Test
    void processQuery_withNewConversation_shouldNotBuildHistory_backwardCompatible() {
        QueryRequest request = buildRequest("Doanh thu theo tháng", 10L, null);

        when(rateLimitService.tryConsume("owner")).thenReturn(true);
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaLoaderService.loadCompleteSchema(10L)).thenReturn(schema);
        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema))).thenReturn(schema);
        when(encryptionUtil.decrypt("enc-pass")).thenReturn("plain-pass");
        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(List.of("col"), List.of(Map.of("col", 1)), 10L, 1, null);
        SQLCorrectionService.AttemptResult attemptResult = buildAttemptResult(
                true, "SELECT col FROM t", finalResult,
                List.of(buildAttemptLog("SELECT col FROM t", true, finalResult)));
        when(sqlCorrectionService.run(eq(request.getQuestion()), eq(schema), eq(schema), eq(connection), eq("plain-pass")))
                .thenReturn(attemptResult);
        when(llmClient.generateResponse(anyString())).thenReturn("Tóm tắt");
        when(chartSuggestionService.suggest(anyList(), anyList()))
                .thenReturn(new ChartSuggestionResponse(ChartType.TABLE, List.of(), null, List.of(), List.of(), "reason"));

        queryService.processQuery("owner", request);

        // Conversation mới -> KHÔNG được query lịch sử (tránh 1 lượt DB thừa)
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    void processQuery_withProgressListener_shouldEmitStagesInOrder() {
        QueryRequest request =
                buildRequest("Doanh thu theo tháng", 10L, null);

        stubSuccessfulProcessQuery();

        List<String> stages = new ArrayList<>();

        QueryProgressListener listener =
                (stage, message) -> stages.add(stage);

        queryService.processQuery(
                "owner",
                request,
                listener
        );

        assertFalse(stages.isEmpty());

        assertEquals("STATUS", stages.get(0));

        assertTrue(stages.contains("STATUS"));
    }

    private void stubSuccessfulProcessQuery() {
        when(rateLimitService.tryConsume("owner")).thenReturn(true);

        when(userRepository.findByUsername("owner"))
                .thenReturn(Optional.of(owner));

        when(connectionRepository.findById(10L))
                .thenReturn(Optional.of(connection));

        when(schemaLoaderService.loadCompleteSchema(10L))
                .thenReturn(schema);

        when(schemaRetrievalService.retrieveRelevantSchema(anyString(), eq(schema)))
                .thenReturn(schema);

        when(encryptionUtil.decrypt("enc-pass"))
                .thenReturn("plain-pass");

        stubConversationSaveAssignsId();
        stubMessageSaveAssignsId();

        QueryResultDto finalResult = new QueryResultDto(
                List.of("thang", "doanh_thu"),
                List.of(
                        Map.of(
                                "thang", 1,
                                "doanh_thu", 1000
                        )
                ),
                42L,
                1,
                null
        );

        SQLCorrectionService.AttemptLog attemptLog =
                buildAttemptLog(
                        "SELECT thang, doanh_thu FROM revenue",
                        true,
                        finalResult
                );

        SQLCorrectionService.AttemptResult attemptResult =
                buildAttemptResult(
                        true,
                        "SELECT thang, doanh_thu FROM revenue",
                        finalResult,
                        List.of(attemptLog)
                );

        when(sqlCorrectionService.run(
                eq("Doanh thu theo tháng"),
                eq(schema),
                eq(schema),
                eq(connection),
                eq("plain-pass")
        )).thenReturn(attemptResult);

        when(llmClient.generateResponse(anyString()))
                .thenReturn("Doanh thu tháng 1 đạt 1000.");
    }

    @Test
    void processQuery_defaultOverload_shouldUseNoopListener_noExceptionThrown() {
        // Đảm bảo bản public cũ processQuery(username, request)
        // vẫn hoạt động bình thường sau khi thêm overload có listener.

        QueryRequest request =
                buildRequest("Doanh thu theo tháng", 10L, null);

        stubSuccessfulProcessQuery();

        assertDoesNotThrow(
                () -> queryService.processQuery("owner", request)
        );
    }
}