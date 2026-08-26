package com.example.aidatabaseassistant.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionUtilTest {

    private EncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() {
        encryptionUtil = new EncryptionUtil();
        ReflectionTestUtils.setField(encryptionUtil, "secret", "unit-test-secret-key");
    }

    @Test
    void encryptThenDecrypt_shouldReturnOriginalPlainText() {
        String plainText = "SuperSecretDbPassword123!";

        String encrypted = encryptionUtil.encrypt(plainText);
        String decrypted = encryptionUtil.decrypt(encrypted);

        assertEquals(plainText, decrypted);
    }

    @Test
    void encrypt_shouldProduceDifferentCipherText_forSamePlainText_dueToRandomIv() {
        String plainText = "same-password";

        String encryptedFirst = encryptionUtil.encrypt(plainText);
        String encryptedSecond = encryptionUtil.encrypt(plainText);

        // Voi AES/GCM, IV ngau nhien moi lan nen ciphertext phai khac nhau
        // (khong con la AES/ECB dinh dang cu - luon ra cung 1 ciphertext).
        assertNotEquals(encryptedFirst, encryptedSecond);

        // Nhung ca hai deu phai giai ma dung ve plaintext ban dau
        assertEquals(plainText, encryptionUtil.decrypt(encryptedFirst));
        assertEquals(plainText, encryptionUtil.decrypt(encryptedSecond));
    }

    @Test
    void decrypt_shouldThrow_whenCipherTextIsTamperedWith() {
        String encrypted = encryptionUtil.encrypt("original-password");

        // Sua 1 ky tu trong phan ciphertext (giu nguyen do dai) de mo phong
        // du lieu bi can thiep - GCM auth tag phai phat hien va tu choi giai ma.
        char[] chars = encrypted.toCharArray();
        int tamperIndex = chars.length - 5;
        chars[tamperIndex] = (chars[tamperIndex] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThrows(RuntimeException.class, () -> encryptionUtil.decrypt(tampered));
    }

    @Test
    void decrypt_shouldThrow_whenUsingWrongSecret() {
        String encrypted = encryptionUtil.encrypt("original-password");

        EncryptionUtil wrongKeyUtil = new EncryptionUtil();
        ReflectionTestUtils.setField(wrongKeyUtil, "secret", "a-completely-different-secret");

        assertThrows(RuntimeException.class, () -> wrongKeyUtil.decrypt(encrypted));
    }
}