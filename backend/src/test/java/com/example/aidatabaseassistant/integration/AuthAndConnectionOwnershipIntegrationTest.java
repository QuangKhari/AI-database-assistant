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
import com.example.aidatabaseassistant.config.EncryptionUtil;
import com.example.aidatabaseassistant.entity.DatabaseConnection;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.DatabaseConnectionRepository;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DatabaseConnectionRepository connectionRepository;

    @Autowired
    private EncryptionUtil encryptionUtil;

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

        // ============================================================
        // 1. Đăng ký 2 user để lấy JWT token
        // ============================================================

        String ownerUsername = "owner_" + System.nanoTime();
        String intruderUsername = "intruder_" + System.nanoTime();

        String ownerEmail = ownerUsername + "@example.com";
        String intruderEmail = intruderUsername + "@example.com";

        String ownerToken = registerAndGetToken(ownerUsername, ownerEmail);
        String intruderToken = registerAndGetToken(intruderUsername, intruderEmail);


        // ============================================================
        // 2. Lấy User entity từ H2
        // ============================================================

        User owner = userRepository.findByUsername(ownerUsername)
                .orElseThrow(() -> new AssertionError("Không tìm thấy owner"));

        User intruder = userRepository.findByUsername(intruderUsername)
                .orElseThrow(() -> new AssertionError("Không tìm thấy intruder"));


        // ============================================================
        // 3. Tạo DatabaseConnection trực tiếp trong H2
        //
        // Không gọi POST /api/connections vì API này có SSRF protection
        // và sẽ cố tình chặn localhost.
        // ============================================================

        DatabaseConnection connection = DatabaseConnection.builder()
                .user(owner)
                .name("Owner's DB")
                .dbType("mysql")
                .host("localhost")
                .port(3308)
                .databaseName("shop")
                .username("root")
                .encryptedPassword(encryptionUtil.encrypt("abc123"))
                .build();

        connection = connectionRepository.saveAndFlush(connection);

        Long connectionId = connection.getId();

        assertNotNull(connectionId);


        // ============================================================
        // DEBUG
        // ============================================================

        System.out.println("========== IDOR TEST DEBUG ==========");
        System.out.println("OWNER USER ID     = " + owner.getId());
        System.out.println("INTRUDER USER ID  = " + intruder.getId());
        System.out.println("CONNECTION ID     = " + connectionId);
        System.out.println("CONNECTION OWNER  = " + connection.getUser().getUsername());
        System.out.println("=====================================");


        // ============================================================
        // 4. Owner đọc connection của chính mình
        // => PHẢI 200 OK
        // ============================================================

        ResponseEntity<String> ownerReadResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ownerToken)));

        System.out.println("========== OWNER READ ==========");
        System.out.println("STATUS = " + ownerReadResponse.getStatusCode());
        System.out.println("BODY   = " + ownerReadResponse.getBody());
        System.out.println("================================");

        assertEquals(HttpStatus.OK, ownerReadResponse.getStatusCode());


        // ============================================================
        // 5. Intruder đoán được ID và cố đọc
        // => PHẢI bị từ chối
        // ============================================================

        ResponseEntity<String> intruderReadResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(intruderToken)));

        System.out.println("========== INTRUDER READ ==========");
        System.out.println("STATUS = " + intruderReadResponse.getStatusCode());
        System.out.println("BODY   = " + intruderReadResponse.getBody());
        System.out.println("===================================");

        assertEquals(HttpStatus.BAD_REQUEST, intruderReadResponse.getStatusCode());

        assertTrue(
                toJson(intruderReadResponse.getBody())
                        .get("message")
                        .asText()
                        .contains("không có quyền")
        );


        // ============================================================
        // 6. Intruder cố DELETE connection
        // => PHẢI bị từ chối
        // ============================================================

        ResponseEntity<String> intruderDeleteResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(intruderToken)));

        System.out.println("========== INTRUDER DELETE ==========");
        System.out.println("STATUS = " + intruderDeleteResponse.getStatusCode());
        System.out.println("BODY   = " + intruderDeleteResponse.getBody());
        System.out.println("=====================================");

        assertEquals(HttpStatus.BAD_REQUEST, intruderDeleteResponse.getStatusCode());


        // ============================================================
        // 7. Owner đọc lại
        // => Connection vẫn phải tồn tại
        // => chứng minh intruder không xóa được
        // ============================================================

        ResponseEntity<String> stillThereResponse = exchange(
                baseUrl() + "/api/connections/" + connectionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ownerToken)));

        System.out.println("========== OWNER READ AFTER ATTACK ==========");
        System.out.println("STATUS = " + stillThereResponse.getStatusCode());
        System.out.println("BODY   = " + stillThereResponse.getBody());
        System.out.println("=============================================");

        assertEquals(HttpStatus.OK, stillThereResponse.getStatusCode());
    }
}