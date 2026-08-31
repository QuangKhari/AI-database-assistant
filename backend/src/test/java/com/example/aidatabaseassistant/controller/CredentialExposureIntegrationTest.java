package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.security.SsrfProtection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CredentialExposureIntegrationTest {

    // =========================================================
    // HTTP
    // =========================================================

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;


    // =========================================================
    // MOCK DEPENDENCIES
    // =========================================================

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private DatabaseConnectionRepository connectionRepository;

    @MockitoBean
    private EncryptionUtil encryptionUtil;

    @MockitoBean
    private SsrfProtection ssrfProtection;


    // =========================================================
    // USER
    // =========================================================

    private User user;

    private String token;


    // =========================================================
    // CREDENTIALS
    // =========================================================

    private static final String RAW_PASSWORD =
            "SuperSecretDatabasePassword123!";

    private static final String ENCRYPTED_PASSWORD =
            "ENCRYPTED_DATABASE_PASSWORD_SECRET";

    private static final String USER_PASSWORD_HASH =
            "USER_PASSWORD_HASH_SECRET";


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUp() {

        // -----------------------------------------------------
        // USER
        // -----------------------------------------------------

        user =
                User.builder()
                        .id(1L)
                        .username("testuser")
                        .email("test@example.com")
                        .passwordHash(USER_PASSWORD_HASH)
                        .role(Role.USER)
                        .build();


        // -----------------------------------------------------
        // JWT
        // -----------------------------------------------------

        token =
                jwtUtil.generateToken("testuser");


        // -----------------------------------------------------
        // USER REPOSITORY
        // -----------------------------------------------------

        when(
                userRepository.findByUsername("testuser")
        ).thenReturn(
                Optional.of(user)
        );
    }


    // =========================================================
    // HELPER
    // =========================================================

    private DatabaseConnection sampleConnection() {

        return DatabaseConnection.builder()
                .id(10L)
                .user(user)
                .name("Production DB")
                .dbType("mysql")
                .host("db.example.com")
                .port(3306)
                .databaseName("shop")
                .username("root")
                .encryptedPassword(ENCRYPTED_PASSWORD)
                .build();
    }


    // =========================================================
    // TEST 1
    // =========================================================

    @Test
    void getConnections_shouldNotExposeDatabasePassword()
            throws Exception {

        /*
         * DatabaseConnection chứa encryptedPassword.
         *
         * Tuy nhiên ConnectionResponse chỉ được phép chứa:
         *
         * id
         * name
         * dbType
         * host
         * port
         * databaseName
         * username
         *
         * Không được trả:
         *
         * password
         * encryptedPassword
         */

        DatabaseConnection connection =
                sampleConnection();


        when(
                connectionRepository.findByUserId(1L)
        ).thenReturn(
                List.of(connection)
        );


        mockMvc.perform(
                        get("/api/connections")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(APPLICATION_JSON)
                )

                // -----------------------------------------------------
                // HTTP 200
                // -----------------------------------------------------

                .andExpect(
                        status().isOk()
                )

                // -----------------------------------------------------
                // Không expose plaintext password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                RAW_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose encrypted password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                ENCRYPTED_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"password\""
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field encryptedPassword
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"encryptedPassword\""
                                        )
                                )
                        )
                );
    }


    // =========================================================
    // TEST 2
    // =========================================================

    @Test
    void getConnection_shouldNotExposeDatabasePassword()
            throws Exception {

        /*
         * Đây là endpoint:
         *
         * GET /api/connections/{id}
         *
         * Vì vậy phải mock:
         *
         * connectionRepository.findById(10L)
         *
         * chứ không phải findByUserId().
         */

        DatabaseConnection connection =
                sampleConnection();


        when(
                connectionRepository.findById(10L)
        ).thenReturn(
                Optional.of(connection)
        );


        mockMvc.perform(
                        get("/api/connections/10")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(APPLICATION_JSON)
                )

                // -----------------------------------------------------
                // HTTP 200
                // -----------------------------------------------------

                .andExpect(
                        status().isOk()
                )

                // -----------------------------------------------------
                // Không expose plaintext password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                RAW_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose encrypted password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                ENCRYPTED_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"password\""
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field encryptedPassword
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"encryptedPassword\""
                                        )
                                )
                        )
                );
    }


    // =========================================================
    // TEST 3
    // =========================================================

    @Test
    void saveConnection_shouldNotExposeDatabasePassword()
            throws Exception {

        /*
         * Test này kiểm tra 2 vấn đề:
         *
         * 1. Password client gửi lên không xuất hiện trong response.
         *
         * 2. Password được encrypt trước khi save vào database.
         */


        // -----------------------------------------------------
        // ENCRYPTION
        // -----------------------------------------------------

        when(
                encryptionUtil.encrypt(RAW_PASSWORD)
        ).thenReturn(
                ENCRYPTED_PASSWORD
        );


        // -----------------------------------------------------
        // CONNECTION LIMIT
        // -----------------------------------------------------

        when(
                connectionRepository.countByUserId(1L)
        ).thenReturn(0L);


        // -----------------------------------------------------
        // SAVE
        // -----------------------------------------------------

        when(
                connectionRepository.save(
                        any(DatabaseConnection.class)
                )
        ).thenAnswer(
                invocation -> {

                    DatabaseConnection connection =
                            invocation.getArgument(
                                    0
                            );

                    connection.setId(20L);

                    return connection;
                }
        );


        // -----------------------------------------------------
        // REQUEST BODY
        // -----------------------------------------------------

        String requestBody =
                """
                {
                    "name": "Test DB",
                    "dbType": "mysql",
                    "host": "db.example.com",
                    "port": 3306,
                    "databaseName": "shop",
                    "username": "root",
                    "password": "%s"
                }
                """.formatted(
                        RAW_PASSWORD
                );


        // -----------------------------------------------------
        // POST REQUEST
        // -----------------------------------------------------

        /*
         * QUAN TRỌNG:
         *
         * Ở code cũ bạn dùng:
         *
         * get("/api/connections")
         *
         * Đây là nguyên nhân khiến save() không bao giờ được gọi.
         *
         * Phải là:
         *
         * post("/api/connections")
         */

        mockMvc.perform(
                        post("/api/connections")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(APPLICATION_JSON)
                                .content(requestBody)
                )

                // -----------------------------------------------------
                // HTTP 200
                // -----------------------------------------------------

                .andExpect(
                        status().isOk()
                )

                // -----------------------------------------------------
                // Không expose plaintext password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                RAW_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose encrypted password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                ENCRYPTED_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"password\""
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field encryptedPassword
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"encryptedPassword\""
                                        )
                                )
                        )
                );


        // =====================================================
        // VERIFY DATABASE STORAGE
        // =====================================================

        ArgumentCaptor<DatabaseConnection> captor =
                ArgumentCaptor.forClass(
                        DatabaseConnection.class
                );


        verify(
                connectionRepository
        ).save(
                captor.capture()
        );


        DatabaseConnection savedConnection =
                captor.getValue();


        // -----------------------------------------------------
        // Password phải được encrypted
        // -----------------------------------------------------

        assertEquals(
                ENCRYPTED_PASSWORD,
                savedConnection.getEncryptedPassword()
        );


        // -----------------------------------------------------
        // Không được lưu plaintext
        // -----------------------------------------------------

        assertNotEquals(
                RAW_PASSWORD,
                savedConnection.getEncryptedPassword()
        );
    }


// =========================================================
// TEST 4
// =========================================================

    @Test
    void getConnections_shouldNotExposeUserPasswordHash()
            throws Exception {

        /*
         * DatabaseConnection có relationship tới User.
         *
         * User chứa passwordHash.
         *
         * API không được serialize User entity trực tiếp
         * ra response.
         */

        DatabaseConnection connection =
                sampleConnection();


        when(
                connectionRepository.findByUserId(1L)
        ).thenReturn(
                List.of(connection)
        );


        // -----------------------------------------------------
        // REQUEST
        // -----------------------------------------------------

        mockMvc.perform(
                        get("/api/connections")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(APPLICATION_JSON)
                )

                // -----------------------------------------------------
                // HTTP 200
                // -----------------------------------------------------

                .andExpect(
                        status().isOk()
                )

                // -----------------------------------------------------
                // Không expose User password hash
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                USER_PASSWORD_HASH
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose field passwordHash
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                "\"passwordHash\""
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose plaintext database password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                RAW_PASSWORD
                                        )
                                )
                        )
                )

                // -----------------------------------------------------
                // Không expose encrypted database password
                // -----------------------------------------------------

                .andExpect(
                        content().string(
                                not(
                                        containsString(
                                                ENCRYPTED_PASSWORD
                                        )
                                )
                        )
                );
    }
}