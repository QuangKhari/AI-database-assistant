package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionRequest;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionResponse;
import com.example.aidatabaseassistant.dto.BenchmarkRunResponse;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.*;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cac test o day co goi runBenchmark(), ben trong co Thread.sleep(5000) sau
 * moi cau hoi (rate-limit throttle voi Gemini free tier) nen se cham hon
 * test binh thuong vai giay - day la gioi han cua thiet ke hien tai,
 * khong phai loi cua test.
 */
@ExtendWith(MockitoExtension.class)
class BenchmarkServiceTest {

    @Mock
    private BenchmarkQuestionRepository benchmarkQuestionRepository;
    @Mock
    private BenchmarkResultRepository benchmarkResultRepository;
    @Mock
    private DatabaseConnectionRepository connectionRepository;
    @Mock
    private DatabaseSchemaRepository schemaRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private NL2SQLEngine nl2SQLEngine;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private UserRepository userRepository;

    private BenchmarkService benchmarkService;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {
        benchmarkService = new BenchmarkService(
                benchmarkQuestionRepository, benchmarkResultRepository, connectionRepository,
                schemaRepository, encryptionUtil, nl2SQLEngine, queryExecutor, userRepository
        );
        ReflectionTestUtils.setField(benchmarkService, "modelUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");

        owner = User.builder().id(1L).username("owner").build();
        otherUser = User.builder().id(2L).username("intruder").build();

        connection = DatabaseConnection.builder()
                .id(10L)
                .user(owner)
                .name("Shop DB")
                .dbType("mysql")
                .host("localhost")
                .port(3306)
                .databaseName("shop")
                .username("root")
                .encryptedPassword("encrypted-secret")
                .build();
    }

    @Test
    void addQuestion_shouldPersistQuestion_whenConnectionOwnedByUser() {
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        BenchmarkQuestionRequest request = new BenchmarkQuestionRequest();
        request.setQuestionText("Co bao nhieu khach hang?");
        request.setExpectedSql("SELECT COUNT(*) FROM customers");

        BenchmarkQuestion saved = BenchmarkQuestion.builder()
                .id(100L)
                .connection(connection)
                .questionText(request.getQuestionText())
                .expectedSql(request.getExpectedSql())
                .build();
        when(benchmarkQuestionRepository.save(any())).thenReturn(saved);

        BenchmarkQuestionResponse response = benchmarkService.addQuestion("owner", 10L, request);

        assertEquals(100L, response.getId());
        assertEquals("Co bao nhieu khach hang?", response.getQuestionText());
        assertEquals(10L, response.getConnectionId());
    }

    @Test
    void addQuestion_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        BenchmarkQuestionRequest request = new BenchmarkQuestionRequest();
        request.setQuestionText("x");
        request.setExpectedSql("SELECT 1");

        assertThrows(
                IllegalArgumentException.class,
                () -> benchmarkService.addQuestion("intruder", 10L, request)
        );

        verify(benchmarkQuestionRepository, never()).save(any());
    }

    @Test
    void runBenchmark_shouldThrow_whenSchemaNotDiscoveredYet() {
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> benchmarkService.runBenchmark("owner", 10L)
        );
    }

    @Test
    void runBenchmark_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {
        when(userRepository.findByUsername("intruder")).thenReturn(Optional.of(otherUser));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));

        assertThrows(
                IllegalArgumentException.class,
                () -> benchmarkService.runBenchmark("intruder", 10L)
        );

        verifyNoInteractions(schemaRepository, benchmarkQuestionRepository, queryExecutor, nl2SQLEngine);
    }

    @Test
    void runBenchmark_shouldMarkCorrect_whenGeneratedResultMatchesExpectedResult() {
        DatabaseSchema schema = DatabaseSchema.builder().id(1L).connection(connection).databaseName("shop").build();
        BenchmarkQuestion question = BenchmarkQuestion.builder()
                .id(100L)
                .connection(connection)
                .questionText("Co bao nhieu khach hang?")
                .expectedSql("SELECT COUNT(*) AS total FROM customers")
                .build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));
        when(benchmarkQuestionRepository.findByConnectionId(10L)).thenReturn(List.of(question));
        when(encryptionUtil.decrypt("encrypted-secret")).thenReturn("plain-secret");

        String generatedSql = "SELECT COUNT(*) AS total FROM customers";
        when(nl2SQLEngine.generateSQL(eq("Co bao nhieu khach hang?"), eq(schema))).thenReturn(generatedSql);

        QueryResultDto sameResult = new QueryResultDto(
                List.of("total"), List.of(Map.of("total", 42)), 15, 1, null);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "plain-secret", generatedSql))
                .thenReturn(sameResult);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "plain-secret", question.getExpectedSql()))
                .thenReturn(sameResult);

        when(benchmarkResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BenchmarkRunResponse response = benchmarkService.runBenchmark("owner", 10L);

        assertEquals(1, response.getTotalQuestions());
        assertEquals(1, response.getCorrectCount());
        assertEquals(100.0, response.getAccuracy());
        assertTrue(response.getDetails().get(0).isCorrect());
        assertNull(response.getDetails().get(0).getErrorMessage());

        verify(benchmarkResultRepository).save(argThat(r ->
                Boolean.TRUE.equals(r.getIsCorrect())
                        && "gemini-3.5-flash-lite".equals(r.getModelUsed())
        ));
    }

    @Test
    void runBenchmark_shouldMarkIncorrect_whenRowCountsDiffer() {
        DatabaseSchema schema = DatabaseSchema.builder().id(1L).connection(connection).databaseName("shop").build();
        BenchmarkQuestion question = BenchmarkQuestion.builder()
                .id(100L)
                .connection(connection)
                .questionText("Liet ke khach hang o Ha Noi")
                .expectedSql("SELECT * FROM customers WHERE city = 'Hanoi'")
                .build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));
        when(benchmarkQuestionRepository.findByConnectionId(10L)).thenReturn(List.of(question));
        when(encryptionUtil.decrypt("encrypted-secret")).thenReturn("plain-secret");

        String generatedSql = "SELECT * FROM customers";
        when(nl2SQLEngine.generateSQL(anyString(), eq(schema))).thenReturn(generatedSql);

        QueryResultDto generatedResult = new QueryResultDto(
                List.of("id"), List.of(Map.of("id", 1), Map.of("id", 2)), 12, 2, null);
        QueryResultDto expectedResult = new QueryResultDto(
                List.of("id"), List.of(Map.of("id", 1)), 12, 1, null);

        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "plain-secret", generatedSql))
                .thenReturn(generatedResult);
        when(queryExecutor.executeQuery("localhost", 3306, "shop", "root", "plain-secret", question.getExpectedSql()))
                .thenReturn(expectedResult);

        when(benchmarkResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BenchmarkRunResponse response = benchmarkService.runBenchmark("owner", 10L);

        assertEquals(0, response.getCorrectCount());
        assertEquals(0.0, response.getAccuracy());
        assertFalse(response.getDetails().get(0).isCorrect());
    }

    @Test
    void runBenchmark_shouldReturnZeroAccuracy_whenNoQuestionsExist() {
        DatabaseSchema schema = DatabaseSchema.builder().id(1L).connection(connection).databaseName("shop").build();

        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(connectionRepository.findById(10L)).thenReturn(Optional.of(connection));
        when(schemaRepository.findByConnectionId(10L)).thenReturn(Optional.of(schema));
        when(benchmarkQuestionRepository.findByConnectionId(10L)).thenReturn(List.of());

        BenchmarkRunResponse response = benchmarkService.runBenchmark("owner", 10L);

        assertEquals(0, response.getTotalQuestions());
        assertEquals(0, response.getCorrectCount());
        assertEquals(0.0, response.getAccuracy());
        assertTrue(response.getDetails().isEmpty());
        verifyNoInteractions(nl2SQLEngine, queryExecutor);
    }
}