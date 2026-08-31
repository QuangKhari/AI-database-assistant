package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository
        extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(
            String tokenHash
    );

    List<PasswordResetToken> findByUser_IdAndUsedAtIsNull(
            Long userId
    );
}