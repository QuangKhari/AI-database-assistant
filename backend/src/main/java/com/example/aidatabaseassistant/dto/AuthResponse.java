package com.example.aidatabaseassistant.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String tokenType;
    private long expiresInSeconds;
    private UserInfo user;

    @Getter
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private String role;
        private LocalDateTime createdAt;
    }
}
