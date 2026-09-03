package com.example.aidatabaseassistant.controller;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import com.example.aidatabaseassistant.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File vuot qua spring.servlet.multipart.max-file-size phai bi TU CHOI boi
 * SERVLET CONTAINER THAT truoc khi request toi duoc controller. Hanh vi nay
 * KHONG the kiem tra bang MockMvc: MockMultipartHttpServletRequest chi giu
 * MockMultipartFile trong bo nho, khong di qua buoc parse multipart that
 * cua Tomcat, nen MaxUploadSizeExceededException khong bao gio duoc nem ra
 * trong moi truong MockMvc du file lon bao nhieu.
 *
 * -> Phai dung TestRestTemplate qua 1 cong HTTP that (RANDOM_PORT) de
 *    servlet container that thuc su enforce gioi han kich thuoc.
 *
 * QUAN TRONG - vi sao KHONG gui that 26MB:
 *
 *     Neu gui that 1 file > 25MB (gioi han that cua app), request duoc
 *     RestTemplate ma hoa bang Transfer-Encoding: chunked. Khi Tomcat phat
 *     hien vuot gioi han GIUA CHUNG luc dang nhan du lieu, no dong ket noi
 *     SOM truoc khi client gui het toan bo 26MB - client (dung HttpURL
 *     Connection/Apache client ben duoi TestRestTemplate) doc phai 1
 *     response bi dang do (chunk bi cat ngang) va nem ra
 *     ResourceAccessException ("chunked transfer encoding, state:
 *     READING_LENGTH") THAY VI 1 HttpStatus binh thuong. Day la han che da
 *     biet cua giao thuc HTTP/client khi upload file that su lon qua ket
 *     noi chunked, khong lien quan gi toi GlobalExceptionHandler.
 *
 *     -> Ha gioi han multipart XUONG RAT NHO chi trong pham vi test nay
 *        (properties cua @SpringBootTest ghi de application.properties),
 *        roi gui 1 file NHO vuot qua gioi han nho do. Cung mot luong xu ly
 *        (MaxUploadSizeExceededException -> GlobalExceptionHandler) duoc
 *        kich hoat y het, nhung payload chi vai KB nen khong bao gio gap
 *        van de chunked/dong ket noi som nhu tren - nhanh va on dinh hon
 *        nhieu so voi gui that 26MB.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.servlet.multipart.max-file-size=1KB",
                "spring.servlet.multipart.max-request-size=1KB"
        }
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ConnectionExcelUploadSizeLimitTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    // Khong quan tam ConnectionService lam gi voi request nay - request se
    // bi chan o tang multipart TRUOC KHI toi duoc service, nen mock rong
    // la du, khong can stub gi ca.
    @MockitoBean
    private ConnectionService connectionService;

    private String validToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = User.builder()
                .username("exceltest")
                .email("exceltest@example.com")
                .passwordHash("test-password-hash")
                .role(Role.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(user);

        validToken = jwtUtil.generateToken("exceltest");
    }

    @Test
    void uploadExcel_shouldReturn413_whenFileExceedsMultipartLimit() {

        // 2KB > 1KB (gioi han da ghi de rieng cho test nay o tren)
        byte[] oversized = new byte[2 * 1024];

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(oversized) {
            @Override
            public String getFilename() {
                return "big.xlsx";
            }
        });
        body.add("name", "Big File");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(validToken);

        HttpEntity<MultiValueMap<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/connections/excel", request, String.class);

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody())
                .contains("File tải lên vượt quá dung lượng cho phép");
    }
}