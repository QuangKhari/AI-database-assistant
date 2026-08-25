package com.example.aidatabaseassistant.integration;

import com.example.aidatabaseassistant.dto.ConnectionRequest;
import com.example.aidatabaseassistant.dto.LoginRequest;
import com.example.aidatabaseassistant.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test tich hop chay tren H2 in-memory (xem src/test/resources/application.properties),
 * khong can Docker MySQL. Muc tieu: xac nhan flow dang ky/dang nhap hoat dong that qua
 * HTTP, va xac nhan lo hong IDOR o Connection API da duoc chan (user A khong the
 * xem/xoa connection cua user B chi bang cach doan ID).
 *
 * Dung RestTemplate thuan (khong phai TestRestTemplate) vi cac test-starter tach rieng
 * (data-jpa-test, security-test, validation-test, webmvc-test) dang dung trong pom.xml
 * khong keo theo module spring-boot-test chua TestRestTemplate. RestTemplate da co san
 * qua spring-boot-starter-webmvc nen khong can them dependency nao.
 *
 * Khac biet quan trong voi TestRestTemplate: RestTemplate thuan se NEM EXCEPTION
 * (HttpStatusCodeException) khi gap response 4xx/5xx thay vi tra ve binh thuong, nen
 * cac ham goi request duoi day deu bat exception va tu dung lai ResponseEntity de
 * cach assert phia duoi khong doi.
 *
 * Response body duoc doc ve String va parse bang Jackson thu cong (thay vi bind
 * thang vao DTO) vi cac DTO response trong project chi co @AllArgsConstructor,
 * khong co constructor mac dinh nen Jackson deserialize truc tiep de bi loi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthAndConnectionOwnershipIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private JsonNode toJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /**
     * Goi POST/GET/DELETE... va LUON tra ve ResponseEntity<String>, ke ca khi
     * server tra ve 4xx/5xx (RestTemplate binh thuong se nem exception trong
     * truong hop nay, o day minh bat lai de test co the assert status code
     * va body nhu binh thuong).
     */
    private ResponseEntity<String> exchange(String url, HttpMethod method, HttpEntity<?> entity) {
        try {
            return restTemplate.exchange(url, method, entity, String.class);
        } catch (HttpStatusCodeException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsString());
        }
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword("password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = exchange(
                baseUrl() + "/api/auth/register", HttpMethod.POST, new HttpEntity<>(request, headers));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = toJson(response.getBody());
        assertTrue(body.has("token"));
        return body.get("token").asText();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void register_thenLogin_shouldSucceedAndReturnValidToken() throws Exception {
        String username = "e2e_user_" + System.nanoTime();
        registerAndGetToken(username, username + "@example.com");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(username);
        loginRequest.setPassword("password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> loginResponse = exchange(
                baseUrl() + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest, headers));

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        JsonNode body = toJson(loginResponse.getBody());
        assertEquals(username, body.get("username").asText());
        assertFalse(body.get("token").asText().isBlank());
    }

    @Test
    void login_shouldReturnUnauthorized_whenPasswordIsWrong() throws Exception {
        String username = "e2e_user_" + System.nanoTime();
        registerAndGetToken(username, username + "@example.com");

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(username);
        loginRequest.setPassword("wrong-password");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = exchange(
                baseUrl() + "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest, headers));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void connectionsApi_shouldRejectUnauthenticatedRequests() {
        ResponseEntity<String> response = exchange(
                baseUrl() + "/api/connections", HttpMethod.GET, HttpEntity.EMPTY);

        assertTrue(response.getStatusCode().is4xxClientError());
    }

    @Test
    void user_shouldNotBeAbleToReadOrDeleteAnotherUsersConnection_IDOR() throws Exception {
        String ownerToken = registerAndGetToken(
                "owner_" + System.nanoTime(), "owner_" + System.nanoTime() + "@example.com");
        String intruderToken = registerAndGetToken(
                "intruder_" + System.nanoTime(), "intruder_" + System.nanoTime() + "@example.com");

        ConnectionRequest connectionRequest = new ConnectionRequest();
        connectionRequest.setName("Owner's DB");
        connectionRequest.setDbType("mysql");
        connectionRequest.setHost("localhost");
        connectionRequest.setPort(3306);
        connectionRequest.setDatabaseName("shop");
        connectionRequest.setUsername("root");
        connectionRequest.setPassword("secret");

        HttpEntity<ConnectionRequest> createRequest = new HttpEntity<>(connectionRequest, authHeaders(ownerToken));
        ResponseEntity<String> createResponse = exchange(
                baseUrl() + "/api/connections", HttpMethod.POST, createRequest);

        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        long connectionId = toJson(createResponse.getBody()).get("id").asLong();

        // Chinh chu doc duoc
        ResponseEntity<String> ownerReadResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ownerToken)));
        assertEquals(HttpStatus.OK, ownerReadResponse.getStatusCode());

        // Nguoi khac doan ID va co doc -> phai bi chan (loi IDOR da duoc fix o Buoc 1)
        ResponseEntity<String> intruderReadResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(intruderToken)));
        assertEquals(HttpStatus.BAD_REQUEST, intruderReadResponse.getStatusCode());
        assertTrue(toJson(intruderReadResponse.getBody()).get("message").asText().contains("không có quyền"));

        // Nguoi khac cung khong the xoa
        ResponseEntity<String> intruderDeleteResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(intruderToken)));
        assertEquals(HttpStatus.BAD_REQUEST, intruderDeleteResponse.getStatusCode());

        // Connection cua chinh chu van con nguyen (chua bi xoa nham)
        ResponseEntity<String> stillThereResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ownerToken)));
        assertEquals(HttpStatus.OK, stillThereResponse.getStatusCode());
    }
}