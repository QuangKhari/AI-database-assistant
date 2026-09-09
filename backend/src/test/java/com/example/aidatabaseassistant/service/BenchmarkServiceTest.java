package com.example.aidatabaseassistant.service;

import com.example.aidatabaseassistant.ai.NL2SQLEngine;
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionRequest;
import com.example.aidatabaseassistant.dto.BenchmarkQuestionResponse;
import com.example.aidatabaseassistant.dto.BenchmarkRunResponse;
import com.example.aidatabaseassistant.dto.QueryResultDto;
import com.example.aidatabaseassistant.entity.BenchmarkQuestion;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.exception.ForbiddenResourceException;
import com.example.aidatabaseassistant.exception.ResourceNotFoundException;
import com.example.aidatabaseassistant.query.QueryExecutor;
import com.example.aidatabaseassistant.query.QueryValidator;
import com.example.aidatabaseassistant.repository.BenchmarkQuestionRepository;
import com.example.aidatabaseassistant.repository.BenchmarkResultRepository;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.security.ConnectionAccessGuard;
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
 * Unit tests cho BenchmarkService.
 *
 * Quy ước:
 *
 * language = null
 *     -> ALL: chạy tất cả câu hỏi của connection.
 *
 * language = "VI"
 *     -> chỉ chạy câu hỏi tiếng Việt.
 *
 * language = "EN"
 *     -> chỉ chạy câu hỏi tiếng Anh.
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
    private QueryValidator queryValidator;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConnectionAccessGuard connectionAccessGuard;

    @Mock
    private SchemaLoaderService schemaLoaderService;

    private BenchmarkService benchmarkService;

    private User owner;
    private User otherUser;
    private DatabaseConnection connection;

    @BeforeEach
    void setUp() {

        benchmarkService = new BenchmarkService(
                benchmarkQuestionRepository,
                benchmarkResultRepository,
                connectionRepository,
                schemaRepository,
                encryptionUtil,
                nl2SQLEngine,
                queryExecutor,
                queryValidator,
                userRepository,
                schemaLoaderService,
                connectionAccessGuard
        );

        /*
         * Model URL dùng để kiểm tra modelUsed trong kết quả benchmark.
         */
        ReflectionTestUtils.setField(
                benchmarkService,
                "modelUrl",
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
        );

        /*
         * Đặt limit cao trong unit test để test không bị ảnh hưởng
         * bởi giới hạn số lượng benchmark thực tế.
         */
        ReflectionTestUtils.setField(
                benchmarkService,
                "maxQuestionsPerConnection",
                30
        );

        owner = User.builder()
                .id(1L)
                .username("owner")
                .build();

        otherUser = User.builder()
                .id(2L)
                .username("intruder")
                .build();

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

    // ============================================================
    // ADD QUESTION
    // ============================================================

    @Test
    void addQuestion_shouldPersistQuestion_whenConnectionOwnedByUser() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        BenchmarkQuestionRequest request =
                new BenchmarkQuestionRequest();

        request.setLanguage("VI");
        request.setQuestionText(
                "Co bao nhieu khach hang?"
        );
        request.setExpectedSql(
                "SELECT COUNT(*) FROM customers"
        );

        BenchmarkQuestion saved =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                request.getQuestionText()
                        )
                        .expectedSql(
                                request.getExpectedSql()
                        )
                        .build();

        when(
                benchmarkQuestionRepository.save(any())
        ).thenReturn(saved);

        BenchmarkQuestionResponse response =
                benchmarkService.addQuestion(
                        "owner",
                        10L,
                        request
                );

        assertEquals(
                100L,
                response.getId()
        );

        assertEquals(
                "Co bao nhieu khach hang?",
                response.getQuestionText()
        );

        assertEquals(
                10L,
                response.getConnectionId()
        );
    }

    @Test
    void addQuestion_shouldThrow_whenMaxQuestionsPerConnectionReached() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        /*
         * Service thực tế của bạn đang dùng countByConnectionId()
         * để kiểm tra giới hạn.
         */
        when(
                benchmarkQuestionRepository.countByConnectionId(10L)
        ).thenReturn(30L);

        BenchmarkQuestionRequest request =
                new BenchmarkQuestionRequest();

        request.setLanguage("VI");
        request.setQuestionText(
                "Co bao nhieu khach hang?"
        );
        request.setExpectedSql(
                "SELECT COUNT(*) FROM customers"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> benchmarkService.addQuestion(
                        "owner",
                        10L,
                        request
                )
        );

        verify(
                benchmarkQuestionRepository,
                never()
        ).save(any());
    }

    @Test
    void addQuestion_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "intruder",
                        10L
                )
        ).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        BenchmarkQuestionRequest request =
                new BenchmarkQuestionRequest();

        request.setLanguage("VI");
        request.setQuestionText("x");
        request.setExpectedSql("SELECT 1");

        assertThrows(
                ForbiddenResourceException.class,
                () -> benchmarkService.addQuestion(
                        "intruder",
                        10L,
                        request
                )
        );

        verify(
                benchmarkQuestionRepository,
                never()
        ).save(any());
    }

    // ============================================================
    // DELETE QUESTION
    // ============================================================

    @Test
    void deleteQuestion_shouldDelete_whenQuestionBelongsToOwnedConnection() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        BenchmarkQuestion question =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Co bao nhieu khach hang?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) FROM customers"
                        )
                        .build();

        when(
                benchmarkQuestionRepository
                        .findByIdAndConnectionId(
                                100L,
                                10L
                        )
        ).thenReturn(
                Optional.of(question)
        );

        benchmarkService.deleteQuestion(
                "owner",
                10L,
                100L
        );

        verify(
                benchmarkQuestionRepository
        ).delete(question);
    }

    @Test
    void deleteQuestion_shouldThrow_whenQuestionNotFoundOnConnection() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                benchmarkQuestionRepository
                        .findByIdAndConnectionId(
                                999L,
                                10L
                        )
        ).thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> benchmarkService.deleteQuestion(
                        "owner",
                        10L,
                        999L
                )
        );

        verify(
                benchmarkQuestionRepository,
                never()
        ).delete(any());
    }

    @Test
    void deleteQuestion_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "intruder",
                        10L
                )
        ).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> benchmarkService.deleteQuestion(
                        "intruder",
                        10L,
                        100L
                )
        );

        verifyNoInteractions(
                benchmarkQuestionRepository
        );
    }

    // ============================================================
    // RUN BENCHMARK - SCHEMA / ACCESS
    // ============================================================

    @Test
    void runBenchmark_shouldThrow_whenSchemaNotDiscoveredYet() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenThrow(
                new ResourceNotFoundException(
                        "Chưa discover schema cho connection này"
                )
        );

        assertThrows(
                ResourceNotFoundException.class,
                () -> benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                )
        );
    }

    @Test
    void runBenchmark_shouldThrow_whenConnectionNotOwnedByUser_IDOR() {

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "intruder",
                        10L
                )
        ).thenThrow(
                new ForbiddenResourceException(
                        "Bạn không có quyền truy cập connection này"
                )
        );

        assertThrows(
                ForbiddenResourceException.class,
                () -> benchmarkService.runBenchmark(
                        "intruder",
                        10L,
                        null
                )
        );

        verifyNoInteractions(
                schemaRepository,
                schemaLoaderService,
                benchmarkQuestionRepository,
                queryExecutor,
                nl2SQLEngine
        );
    }

    // ============================================================
    // RUN BENCHMARK - ALL
    // ============================================================

    @Test
    void runBenchmark_shouldRunAllQuestions_whenLanguageIsNull() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion viQuestion =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Co bao nhieu khach hang?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) AS total FROM customers"
                        )
                        .build();

        BenchmarkQuestion enQuestion =
                BenchmarkQuestion.builder()
                        .id(101L)
                        .connection(connection)
                        .language("EN")
                        .questionText(
                                "How many customers are there?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) AS total FROM customers"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        /*
         * ALL -> findByConnectionId()
         */
        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of(
                        viQuestion,
                        enQuestion
                )
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedViSql =
                "SELECT COUNT(*) AS total FROM customers";

        String generatedEnSql =
                "SELECT COUNT(*) AS total FROM customers";

        when(
                nl2SQLEngine.generateSQL(
                        eq("Co bao nhieu khach hang?"),
                        eq(schema)
                )
        ).thenReturn(generatedViSql);

        when(
                nl2SQLEngine.generateSQL(
                        eq("How many customers are there?"),
                        eq(schema)
                )
        ).thenReturn(generatedEnSql);

        doNothing()
                .when(queryValidator)
                .validate(
                        anyString(),
                        eq(schema)
                );

        QueryResultDto sameResult =
                new QueryResultDto(
                        List.of("total"),
                        List.of(
                                Map.of("total", 42)
                        ),
                        15,
                        1,
                        null
                );

        when(
                queryExecutor.executeQuery(
                        eq("mysql"),
                        eq("localhost"),
                        eq(3306),
                        eq("shop"),
                        eq("root"),
                        eq("plain-secret"),
                        anyString()
                )
        ).thenReturn(sameResult);

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                2,
                response.getTotalQuestions()
        );

        assertEquals(
                2,
                response.getCorrectCount()
        );

        assertEquals(
                100.0,
                response.getAccuracy()
        );

        assertEquals(
                2,
                response.getDetails().size()
        );

        assertTrue(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        assertTrue(
                response.getDetails()
                        .get(1)
                        .isCorrect()
        );

        /*
         * ALL phải gọi findByConnectionId()
         */
        verify(
                benchmarkQuestionRepository
        ).findByConnectionId(10L);

        /*
         * ALL không được gọi repository filter VI/EN.
         */
        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionIdAndLanguage(
                anyLong(),
                anyString()
        );
    }

    // ============================================================
    // RUN BENCHMARK - VI
    // ============================================================

    @Test
    void runBenchmark_shouldRunOnlyVietnameseQuestions_whenLanguageVI() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion viQuestion =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Co bao nhieu khach hang?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) AS total FROM customers"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        /*
         * VI -> chỉ gọi findByConnectionIdAndLanguage(..., "VI")
         */
        when(
                benchmarkQuestionRepository
                        .findByConnectionIdAndLanguage(
                                10L,
                                "VI"
                        )
        ).thenReturn(
                List.of(viQuestion)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "SELECT COUNT(*) AS total FROM customers";

        when(
                nl2SQLEngine.generateSQL(
                        eq("Co bao nhieu khach hang?"),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        doNothing()
                .when(queryValidator)
                .validate(
                        anyString(),
                        eq(schema)
                );

        QueryResultDto sameResult =
                new QueryResultDto(
                        List.of("total"),
                        List.of(
                                Map.of("total", 42)
                        ),
                        15,
                        1,
                        null
                );

        when(
                queryExecutor.executeQuery(
                        eq("mysql"),
                        eq("localhost"),
                        eq(3306),
                        eq("shop"),
                        eq("root"),
                        eq("plain-secret"),
                        anyString()
                )
        ).thenReturn(sameResult);

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        "VI"
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                1,
                response.getCorrectCount()
        );

        assertEquals(
                100.0,
                response.getAccuracy()
        );

        assertTrue(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        /*
         * Phải gọi đúng query VI.
         */
        verify(
                benchmarkQuestionRepository
        ).findByConnectionIdAndLanguage(
                10L,
                "VI"
        );

        /*
         * Tuyệt đối không gọi ALL.
         */
        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionId(10L);

        /*
         * Không được query EN.
         */
        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionIdAndLanguage(
                10L,
                "EN"
        );

        /*
         * AI chỉ được gọi đúng 1 câu VI.
         */
        verify(
                nl2SQLEngine,
                times(1)
        ).generateSQL(
                eq("Co bao nhieu khach hang?"),
                eq(schema)
        );
    }

    // ============================================================
    // RUN BENCHMARK - EN
    // ============================================================

    @Test
    void runBenchmark_shouldRunOnlyEnglishQuestions_whenLanguageEN() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion enQuestion =
                BenchmarkQuestion.builder()
                        .id(101L)
                        .connection(connection)
                        .language("EN")
                        .questionText(
                                "How many customers are there?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) AS total FROM customers"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        /*
         * EN -> chỉ gọi findByConnectionIdAndLanguage(..., "EN")
         */
        when(
                benchmarkQuestionRepository
                        .findByConnectionIdAndLanguage(
                                10L,
                                "EN"
                        )
        ).thenReturn(
                List.of(enQuestion)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "SELECT COUNT(*) AS total FROM customers";

        when(
                nl2SQLEngine.generateSQL(
                        eq("How many customers are there?"),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        doNothing()
                .when(queryValidator)
                .validate(
                        anyString(),
                        eq(schema)
                );

        QueryResultDto sameResult =
                new QueryResultDto(
                        List.of("total"),
                        List.of(
                                Map.of("total", 42)
                        ),
                        15,
                        1,
                        null
                );

        when(
                queryExecutor.executeQuery(
                        eq("mysql"),
                        eq("localhost"),
                        eq(3306),
                        eq("shop"),
                        eq("root"),
                        eq("plain-secret"),
                        anyString()
                )
        ).thenReturn(sameResult);

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        "EN"
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                1,
                response.getCorrectCount()
        );

        assertEquals(
                100.0,
                response.getAccuracy()
        );

        assertTrue(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        /*
         * Phải gọi đúng query EN.
         */
        verify(
                benchmarkQuestionRepository
        ).findByConnectionIdAndLanguage(
                10L,
                "EN"
        );

        /*
         * Không được gọi ALL.
         */
        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionId(10L);

        /*
         * Không được query VI.
         */
        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionIdAndLanguage(
                10L,
                "VI"
        );

        /*
         * AI chỉ được gọi đúng câu EN.
         */
        verify(
                nl2SQLEngine,
                times(1)
        ).generateSQL(
                eq("How many customers are there?"),
                eq(schema)
        );
    }

    // ============================================================
    // RUN BENCHMARK - CORRECT RESULT
    // ============================================================

    @Test
    void runBenchmark_shouldMarkCorrect_whenGeneratedResultMatchesExpectedResult() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion question =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Co bao nhieu khach hang?"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) AS total FROM customers"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        /*
         * null = ALL
         */
        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of(question)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "SELECT COUNT(*) AS total FROM customers";

        when(
                nl2SQLEngine.generateSQL(
                        eq("Co bao nhieu khach hang?"),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        doNothing()
                .when(queryValidator)
                .validate(
                        generatedSql,
                        schema
                );

        QueryResultDto sameResult =
                new QueryResultDto(
                        List.of("total"),
                        List.of(
                                Map.of("total", 42)
                        ),
                        15,
                        1,
                        null
                );

        when(
                queryExecutor.executeQuery(
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        "plain-secret",
                        generatedSql
                )
        ).thenReturn(sameResult);

        when(
                queryExecutor.executeQuery(
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        "plain-secret",
                        question.getExpectedSql()
                )
        ).thenReturn(sameResult);

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                1,
                response.getCorrectCount()
        );

        assertEquals(
                100.0,
                response.getAccuracy()
        );

        assertTrue(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        assertNull(
                response.getDetails()
                        .get(0)
                        .getErrorMessage()
        );

        verify(
                benchmarkResultRepository
        ).save(
                argThat(result ->
                        Boolean.TRUE.equals(
                                result.getIsCorrect()
                        )
                                &&
                                "gemini-3.5-flash-lite"
                                        .equals(
                                                result.getModelUsed()
                                        )
                )
        );
    }

    // ============================================================
    // RUN BENCHMARK - INCORRECT RESULT
    // ============================================================

    @Test
    void runBenchmark_shouldMarkIncorrect_whenRowCountsDiffer() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion question =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Liet ke khach hang o Ha Noi"
                        )
                        .expectedSql(
                                "SELECT * FROM customers WHERE city = 'Hanoi'"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of(question)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "SELECT * FROM customers";

        when(
                nl2SQLEngine.generateSQL(
                        anyString(),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        doNothing()
                .when(queryValidator)
                .validate(
                        anyString(),
                        eq(schema)
                );

        QueryResultDto generatedResult =
                new QueryResultDto(
                        List.of("id"),
                        List.of(
                                Map.of("id", 1),
                                Map.of("id", 2)
                        ),
                        12,
                        2,
                        null
                );

        QueryResultDto expectedResult =
                new QueryResultDto(
                        List.of("id"),
                        List.of(
                                Map.of("id", 1)
                        ),
                        12,
                        1,
                        null
                );

        when(
                queryExecutor.executeQuery(
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        "plain-secret",
                        generatedSql
                )
        ).thenReturn(generatedResult);

        when(
                queryExecutor.executeQuery(
                        "mysql",
                        "localhost",
                        3306,
                        "shop",
                        "root",
                        "plain-secret",
                        question.getExpectedSql()
                )
        ).thenReturn(expectedResult);

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                0,
                response.getCorrectCount()
        );

        assertEquals(
                0.0,
                response.getAccuracy()
        );

        assertFalse(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );
    }

    // ============================================================
    // RUN BENCHMARK - NO QUESTIONS
    // ============================================================

    @Test
    void runBenchmark_shouldReturnZeroAccuracy_whenNoQuestionsExist() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of()
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                0,
                response.getTotalQuestions()
        );

        assertEquals(
                0,
                response.getCorrectCount()
        );

        assertEquals(
                0.0,
                response.getAccuracy()
        );

        assertTrue(
                response.getDetails()
                        .isEmpty()
        );

        verifyNoInteractions(
                nl2SQLEngine,
                queryExecutor
        );
    }

    // ============================================================
    // RUN BENCHMARK - INVALID GENERATED SQL
    // ============================================================

    @Test
    void runBenchmark_shouldNotExecuteGeneratedSql_whenValidatorRejectsIt() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion question =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Xoa tat ca don hang"
                        )
                        .expectedSql(
                                "SELECT COUNT(*) FROM orders"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of(question)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "DELETE FROM orders";

        when(
                nl2SQLEngine.generateSQL(
                        eq("Xoa tat ca don hang"),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        doThrow(
                new IllegalArgumentException(
                        "Chỉ cho phép câu lệnh SELECT"
                )
        )
                .when(queryValidator)
                .validate(
                        generatedSql,
                        schema
                );

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                0,
                response.getCorrectCount()
        );

        assertEquals(
                0.0,
                response.getAccuracy()
        );

        assertFalse(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        verify(
                queryValidator
        ).validate(
                generatedSql,
                schema
        );

        /*
         * SQL nguy hiểm không được execute.
         */
        verify(
                queryExecutor,
                never()
        ).executeQuery(
                anyString(),
                anyString(),
                anyInt(),
                anyString(),
                anyString(),
                anyString(),
                eq(generatedSql)
        );
    }

    // ============================================================
    // RUN BENCHMARK - INVALID EXPECTED SQL
    // ============================================================

    @Test
    void runBenchmark_shouldNotExecuteExpectedSql_whenValidatorRejectsIt() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        BenchmarkQuestion question =
                BenchmarkQuestion.builder()
                        .id(100L)
                        .connection(connection)
                        .language("VI")
                        .questionText(
                                "Dem don hang"
                        )
                        .expectedSql(
                                "DELETE FROM orders"
                        )
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        when(
                benchmarkQuestionRepository
                        .findByConnectionId(10L)
        ).thenReturn(
                List.of(question)
        );

        when(
                encryptionUtil.decrypt(
                        "encrypted-secret"
                )
        ).thenReturn("plain-secret");

        String generatedSql =
                "SELECT COUNT(*) FROM orders";

        when(
                nl2SQLEngine.generateSQL(
                        eq("Dem don hang"),
                        eq(schema)
                )
        ).thenReturn(generatedSql);

        /*
         * Generated SQL hợp lệ.
         */
        doNothing()
                .when(queryValidator)
                .validate(
                        generatedSql,
                        schema
                );

        /*
         * Expected SQL không hợp lệ.
         */
        doThrow(
                new IllegalArgumentException(
                        "Chỉ cho phép câu lệnh SELECT"
                )
        )
                .when(queryValidator)
                .validate(
                        question.getExpectedSql(),
                        schema
                );

        when(
                benchmarkResultRepository.save(any())
        ).thenAnswer(
                inv -> inv.getArgument(0)
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        null
                );

        assertEquals(
                1,
                response.getTotalQuestions()
        );

        assertEquals(
                0,
                response.getCorrectCount()
        );

        assertEquals(
                0.0,
                response.getAccuracy()
        );

        assertFalse(
                response.getDetails()
                        .get(0)
                        .isCorrect()
        );

        verify(
                queryValidator
        ).validate(
                generatedSql,
                schema
        );

        verify(
                queryValidator
        ).validate(
                question.getExpectedSql(),
                schema
        );

        /*
         * Expected SQL không hợp lệ
         * -> không được execute bất kỳ SQL nào.
         */
        verifyNoInteractions(
                queryExecutor
        );
    }

    // ============================================================
    // RUN BENCHMARK - INVALID LANGUAGE
    // ============================================================

    @Test
    void runBenchmark_shouldThrow_whenLanguageIsInvalid() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        assertThrows(
                IllegalArgumentException.class,
                () -> benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        "JP"
                )
        );

        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionId(anyLong());

        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionIdAndLanguage(
                anyLong(),
                anyString()
        );

        verifyNoInteractions(
                nl2SQLEngine,
                queryExecutor
        );
    }

    // ============================================================
    // LANGUAGE NORMALIZATION
    // ============================================================

    @Test
    void runBenchmark_shouldNormalizeLanguageToUpperCase() {

        DatabaseSchema schema =
                DatabaseSchema.builder()
                        .id(1L)
                        .connection(connection)
                        .databaseName("shop")
                        .build();

        when(
                connectionAccessGuard.requireOwnedConnection(
                        "owner",
                        10L
                )
        ).thenReturn(connection);

        when(
                schemaLoaderService.loadCompleteSchema(10L)
        ).thenReturn(schema);

        when(
                benchmarkQuestionRepository
                        .findByConnectionIdAndLanguage(
                                10L,
                                "VI"
                        )
        ).thenReturn(
                List.of()
        );

        BenchmarkRunResponse response =
                benchmarkService.runBenchmark(
                        "owner",
                        10L,
                        " vi "
                );

        assertEquals(
                0,
                response.getTotalQuestions()
        );

        assertEquals(
                0,
                response.getCorrectCount()
        );

        assertEquals(
                0.0,
                response.getAccuracy()
        );

        verify(
                benchmarkQuestionRepository
        ).findByConnectionIdAndLanguage(
                10L,
                "VI"
        );

        verify(
                benchmarkQuestionRepository,
                never()
        ).findByConnectionId(10L);
    }
}