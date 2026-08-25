package com.example.aidatabaseassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class EncryptionUtil {

    private static final String PREFIX = "gcm:v1:";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final byte[] AAD = "database-credential:v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${app.encryption.secret}")
    private String secret;

    private SecretKeySpec getKey() {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException("AES_KEY phải có đúng 16, 24 hoặc 32 byte UTF-8");
        }
        return new SecretKeySpec(key, "AES");
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể mã hóa thông tin đăng nhập database", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (!encryptedText.startsWith(PREFIX)) {
            return decryptLegacy(encryptedText);
        }
        try {
            String[] parts = encryptedText.substring(PREFIX.length()).split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Dữ liệu mã hóa không đúng định dạng");
            }
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getUrlDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể giải mã thông tin đăng nhập database", e);
        }
    }

    public boolean isLegacy(String encryptedText) {
        return encryptedText != null && !encryptedText.startsWith(PREFIX);
    }

    private String decryptLegacy(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, getKey());
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể giải mã thông tin đăng nhập database cũ", e);
        }
    }
}
