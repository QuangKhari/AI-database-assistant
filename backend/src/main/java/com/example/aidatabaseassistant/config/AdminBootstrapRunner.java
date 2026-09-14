package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.entity.Role;
import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Giai bai toan "ga - trung": PATCH /api/admin/users/{id}/role doi hoi
 * nguoi goi DA la ADMIN (@PreAuthorize("hasRole('ADMIN')") tren
 * AdminController), nen khong co cach nao tu tao ADMIN dau tien qua API.
 *
 * Runner nay CHI chay khi ca 3 bien BOOTSTRAP_ADMIN_USERNAME/EMAIL/PASSWORD
 * duoc cau hinh tuong minh (mac dinh RONG = tat, khong lam gi ca - an toan
 * cho production khi khong set gi them).
 *
 * CANH BAO: sau khi da dang nhap duoc bang tai khoan nay, PHAI doi mat khau
 * va GO 3 bien nay khoi .env / bien moi truong. Neu de nguyen, moi lan
 * container restart se kiem tra lai (nhung KHONG ghi de neu username da ton
 * tai - xem log WARN de biet trang thai).
 */
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.username:}")
    private String bootstrapUsername;

    @Value("${app.bootstrap.admin.email:}")
    private String bootstrapEmail;

    @Value("${app.bootstrap.admin.password:}")
    private String bootstrapPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapUsername.isBlank() || bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            return;
        }

        if (userRepository.findByUsername(bootstrapUsername).isPresent()) {
            log.info("Bootstrap admin '{}' da ton tai, bo qua (khong ghi de mat khau).", bootstrapUsername);
            return;
        }

        User admin = User.builder()
                .username(bootstrapUsername)
                .email(bootstrapEmail)
                .passwordHash(passwordEncoder.encode(bootstrapPassword))
                .role(Role.ADMIN)
                .locked(false)
                .build();

        userRepository.save(admin);

        log.warn("Da tao BOOTSTRAP ADMIN '{}'. Hay dang nhap, doi mat khau NGAY, " +
                "va go BOOTSTRAP_ADMIN_* khoi .env de tranh lo mat khau ve sau.", bootstrapUsername);
    }
}