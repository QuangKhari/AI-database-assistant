package com.example.aidatabaseassistant.contract;

import com.example.aidatabaseassistant.config.JwtUtil;
import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phan 3.7 / 2.6: "403 dung user nhung sai role". Truoc day KHONG co test
 * nao kiem tra @PreAuthorize("hasRole('ADMIN')") tren AdminController tra
 * ve dung JSON 403 (thay vi HTML redirect mac dinh cua Spring Security).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccessControlContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        userRepository.saveAndFlush(User.builder()
                .username("plainuser").email("plainuser@example.com")
                .passwordHash("hash").role(Role.USER).locked(false).build());

        userRepository.saveAndFlush(User.builder()
                .username("realadmin").email("realadmin@example.com")
                .passwordHash("hash").role(Role.ADMIN).locked(false).build());

        userToken = jwtUtil.generateToken("plainuser");
        adminToken = jwtUtil.generateToken("realadmin");
    }

    @Test
    void adminEndpoint_shouldReturn403Json_notHtmlRedirect_whenCalledByPlainUser() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/admin/users"));
    }

    @Test
    void adminEndpoint_shouldReturn401_whenNoTokenAtAll() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void adminEndpoint_shouldReturn200_whenCalledByRealAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // 409 that (khong mock) - admin tu khoa/tu doi role chinh minh
    // ---------------------------------------------------------------
    @Test
    void lockUser_shouldReturn409_whenAdminLocksOwnAccount() throws Exception {
        User admin = userRepository.findByUsername("realadmin").orElseThrow();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/admin/users/" + admin.getId() + "/lock")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}