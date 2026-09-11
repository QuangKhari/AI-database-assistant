package com.example.aidatabaseassistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "database_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatabaseConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "db_type", nullable = false, length = 20)
    private String dbType;

    @Column(name = "ssl_enabled", nullable = false)
    @Builder.Default
    private boolean sslEnabled = false;

    @Column(nullable = false, length = 100)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(name = "database_name", nullable = false, length = 100)
    private String databaseName;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "encrypted_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedPassword;

    /**
     * Connection hiện có đang được đánh dấu là hoạt động hay không.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Thời điểm test/reconnect gần nhất.
     */
    @Column(name = "last_tested_at")
    private LocalDateTime lastTestedAt;

    /**
     * Kết quả của lần test gần nhất.
     *
     * null = chưa từng test
     * true = test thành công
     * false = test thất bại
     */
    @Column(name = "last_test_successful")
    private Boolean lastTestSuccessful;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}