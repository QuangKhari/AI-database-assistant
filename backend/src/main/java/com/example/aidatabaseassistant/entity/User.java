package com.example.aidatabaseassistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Column(
            name = "locked",
            nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE"
    )
    @Builder.Default
    private boolean locked = false;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    /**
     * Giữ tương thích với các System Database đã được tạo từ phiên bản cũ.
     * Việc khóa/mở khóa tài khoản hiện được điều khiển bởi trường {@code locked};
     * tuy nhiên cột {@code enabled} cũ vẫn là NOT NULL ở một số database nên
     * Hibernate phải ghi giá trị này khi tạo user mới.
     */
    @Column(
            name = "enabled",
            nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE"
    )
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DatabaseConnection> databaseConnections = new ArrayList<>();
}
