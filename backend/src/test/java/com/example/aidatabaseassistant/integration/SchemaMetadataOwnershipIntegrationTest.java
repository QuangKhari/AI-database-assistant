package com.example.aidatabaseassistant.integration;

import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.entity.ColumnMetadata;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.DatabaseSchema;
import com.example.aidatabaseassistant.entity.TableMetadata;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.ColumnMetadataRepository;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.DatabaseSchemaRepository;
import com.example.aidatabaseassistant.repository.TableMetadataRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchemaMetadataOwnershipIntegrationTest {

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseConnectionRepository connectionRepository;

    @Autowired
    private DatabaseSchemaRepository schemaRepository;

    @Autowired
    private TableMetadataRepository tableMetadataRepository;

    @Autowired
    private ColumnMetadataRepository columnMetadataRepository;

    @Autowired
    private EncryptionUtil encryptionUtil;

    private final RestTemplate restTemplate = new RestTemplate();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private ResponseEntity<String> exchange(
            String url,
            HttpMethod method,
            HttpEntity<?> entity) {

        try {
            return restTemplate.exchange(
                    url,
                    method,
                    entity,
                    String.class
            );
        } catch (HttpStatusCodeException ex) {
            return ResponseEntity
                    .status(ex.getStatusCode())
                    .body(ex.getResponseBodyAsString());
        }
    }

    private String registerAndGetToken(
            String username,
            String email) {

        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "password123"
                }
                """.formatted(username, email);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = exchange(
                baseUrl() + "/api/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(body, headers)
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());

        String responseBody = response.getBody();
        assertNotNull(responseBody);

        int tokenStart = responseBody.indexOf("\"token\":\"")
                + "\"token\":\"".length();

        int tokenEnd = responseBody.indexOf(
                "\"",
                tokenStart
        );

        assertTrue(tokenStart > 0);
        assertTrue(tokenEnd > tokenStart);

        return responseBody.substring(
                tokenStart,
                tokenEnd
        );
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private User createUser(
            String username,
            String email) {

        return userRepository.saveAndFlush(
                User.builder()
                        .username(username)
                        .email(email)
                        .passwordHash("test")
                        .build()
        );
    }

    private DatabaseConnection createConnection(User user) {
        return connectionRepository.saveAndFlush(
                DatabaseConnection.builder()
                        .user(user)
                        .name("Schema IDOR Test DB")
                        .dbType("mysql")
                        .host("localhost")
                        .port(3306)
                        .databaseName("test")
                        .username("root")
                        .encryptedPassword(
                                encryptionUtil.encrypt("password")
                        )
                        .build()
        );
    }

    private TableMetadata createTable(
            DatabaseSchema schema) {

        return tableMetadataRepository.saveAndFlush(
                TableMetadata.builder()
                        .schema(schema)
                        .name("orders")
                        .description("Original table description")
                        .build()
        );
    }

    private ColumnMetadata createColumn(
            TableMetadata table) {

        return columnMetadataRepository.saveAndFlush(
                ColumnMetadata.builder()
                        .table(table)
                        .name("id")
                        .dataType("BIGINT")
                        .nullable(false)
                        .primaryKey(true)
                        .foreignKey(false)
                        .description("Original column description")
                        .build()
        );
    }

    @Test
    void owner_shouldBeAbleToUpdateTableDescription()
            throws Exception {

        String username =
                "schema_owner_" + System.nanoTime();

        String token = registerAndGetToken(
                username,
                username + "@example.com"
        );

        User owner =
                userRepository.findByUsername(username)
                        .orElseThrow();

        DatabaseConnection connection =
                createConnection(owner);

        DatabaseSchema schema =
                schemaRepository.saveAndFlush(
                        DatabaseSchema.builder()
                                .connection(connection)
                                .databaseName("test")
                                .dbType("mysql")
                                .build()
                );

        TableMetadata table =
                createTable(schema);

        String body = """
                {
                    "description": "Updated by owner"
                }
                """;

        ResponseEntity<String> response =
                exchange(
                        baseUrl()
                                + "/api/schema/tables/"
                                + table.getId(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                body,
                                authHeaders(token)
                        )
                );

        assertEquals(
                HttpStatus.NO_CONTENT,
                response.getStatusCode()
        );

        TableMetadata updated =
                tableMetadataRepository
                        .findById(table.getId())
                        .orElseThrow();

        assertEquals(
                "Updated by owner",
                updated.getDescription()
        );
    }

    @Test
    void intruder_shouldNotBeAbleToUpdateTableDescription()
            throws Exception {

        String ownerUsername =
                "schema_owner_" + System.nanoTime();

        String intruderUsername =
                "schema_intruder_" + System.nanoTime();

        String ownerToken =
                registerAndGetToken(
                        ownerUsername,
                        ownerUsername + "@example.com"
                );

        String intruderToken =
                registerAndGetToken(
                        intruderUsername,
                        intruderUsername + "@example.com"
                );

        User owner =
                userRepository.findByUsername(ownerUsername)
                        .orElseThrow();

        DatabaseConnection connection =
                createConnection(owner);

        DatabaseSchema schema =
                schemaRepository.saveAndFlush(
                        DatabaseSchema.builder()
                                .connection(connection)
                                .databaseName("test")
                                .dbType("mysql")
                                .build()
                );

        TableMetadata table =
                createTable(schema);

        String body = """
                {
                    "description": "HACKED"
                }
                """;

        ResponseEntity<String> response =
                exchange(
                        baseUrl()
                                + "/api/schema/tables/"
                                + table.getId(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                body,
                                authHeaders(intruderToken)
                        )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode()
        );

        TableMetadata unchanged =
                tableMetadataRepository
                        .findById(table.getId())
                        .orElseThrow();

        assertEquals(
                "Original table description",
                unchanged.getDescription()
        );
    }

    @Test
    void owner_shouldBeAbleToUpdateColumnDescription()
            throws Exception {

        String username =
                "column_owner_" + System.nanoTime();

        String token =
                registerAndGetToken(
                        username,
                        username + "@example.com"
                );

        User owner =
                userRepository.findByUsername(username)
                        .orElseThrow();

        DatabaseConnection connection =
                createConnection(owner);

        DatabaseSchema schema =
                schemaRepository.saveAndFlush(
                        DatabaseSchema.builder()
                                .connection(connection)
                                .databaseName("test")
                                .dbType("mysql")
                                .build()
                );

        TableMetadata table =
                createTable(schema);

        ColumnMetadata column =
                createColumn(table);

        String body = """
                {
                    "description": "Updated column description"
                }
                """;

        ResponseEntity<String> response =
                exchange(
                        baseUrl()
                                + "/api/schema/columns/"
                                + column.getId(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                body,
                                authHeaders(token)
                        )
                );

        assertEquals(
                HttpStatus.NO_CONTENT,
                response.getStatusCode()
        );

        ColumnMetadata updated =
                columnMetadataRepository
                        .findById(column.getId())
                        .orElseThrow();

        assertEquals(
                "Updated column description",
                updated.getDescription()
        );
    }

    @Test
    void intruder_shouldNotBeAbleToUpdateColumnDescription()
            throws Exception {

        String ownerUsername =
                "column_owner_" + System.nanoTime();

        String intruderUsername =
                "column_intruder_" + System.nanoTime();

        registerAndGetToken(
                ownerUsername,
                ownerUsername + "@example.com"
        );

        String intruderToken =
                registerAndGetToken(
                        intruderUsername,
                        intruderUsername + "@example.com"
                );

        User owner =
                userRepository.findByUsername(ownerUsername)
                        .orElseThrow();

        DatabaseConnection connection =
                createConnection(owner);

        DatabaseSchema schema =
                schemaRepository.saveAndFlush(
                        DatabaseSchema.builder()
                                .connection(connection)
                                .databaseName("test")
                                .dbType("mysql")
                                .build()
                );

        TableMetadata table =
                createTable(schema);

        ColumnMetadata column =
                createColumn(table);

        String body = """
                {
                    "description": "HACKED"
                }
                """;

        ResponseEntity<String> response =
                exchange(
                        baseUrl()
                                + "/api/schema/columns/"
                                + column.getId(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                body,
                                authHeaders(intruderToken)
                        )
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode()
        );

        ColumnMetadata unchanged =
                columnMetadataRepository
                        .findById(column.getId())
                        .orElseThrow();

        assertEquals(
                "Original column description",
                unchanged.getDescription()
        );
    }
}