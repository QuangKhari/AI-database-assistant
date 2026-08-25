package com.example.aidatabaseassistant.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionUtilTest {

    private EncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        encryptionUtil = new EncryptionUtil();
        ReflectionTestUtils.setField(encryptionUtil, "secret", "test-aes-key-123");
    }

    @Test
    void aesGcmRoundTripUsesRandomIv() {
        String first = encryptionUtil.encrypt("database-password");
        String second = encryptionUtil.encrypt("database-password");

        assertThat(first).startsWith("gcm:v1:").isNotEqualTo(second);
        assertThat(encryptionUtil.decrypt(first)).isEqualTo("database-password");
        assertThat(encryptionUtil.decrypt(second)).isEqualTo("database-password");
    }

    @Test
    void aesGcmRejectsTamperedCiphertext() {
        String encrypted = encryptionUtil.encrypt("database-password");
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> encryptionUtil.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Không thể giải mã thông tin đăng nhập database");
    }
}
