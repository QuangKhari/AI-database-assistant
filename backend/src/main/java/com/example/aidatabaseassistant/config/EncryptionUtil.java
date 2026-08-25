package com.example.aidatabaseassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ma hoa/giai ma mat khau ket noi database bang AES/GCM/NoPadding.
 *
 * Ly do doi tu AES (mac dinh la AES/ECB/PKCS5Padding trong Java) sang AES/GCM:
 * - ECB khong dung IV: cung mot plaintext luon cho ra cung mot ciphertext,
 *   de bi tan cong phan tich pattern va khong co tinh toan ven (authentication).
 * - GCM dung IV ngau nhien moi lan ma hoa (khong bao gio dung lai IV voi cung
 *   1 key) va sinh kem authentication tag, giup phat hien du lieu bi sua doi.
 *
 * Dinh dang output: base64( IV(12 bytes) || ciphertext || authTag(16 bytes) ).
 * IV duoc luu chung voi ciphertext (khong can bi mat) nen khong can bang them.
 *
 * LUU Y MIGRATION: cac password da ma hoa bang AES/ECB truoc do (dinh dang cu)
 * se KHONG giai ma duoc voi class nay nua vi khac ca thuat toan lan dinh dang
 * output. Neu DB dang co connection cu, can xoa va tao lai connection (nhap
 * lai password) sau khi deploy ban nay - moi truong dev/test hien tai chi co
 * vai connection thu nghiem nen chap nhan duoc.
 */
@Component
public class EncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    @Value("${app.encryption.secret}")
    private String secret;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Chuan hoa secret ve dung 32 byte (AES-256) bang SHA-256, thay vi doi hoi
     * nguoi dung phai tu nho nhap dung 16/24/32 ky tu. An toan hon so voi
     * truncate/pad thu cong va tranh loi "Invalid AES key length" khi secret
     * trong application.properties bi go sai do dai.
     */
    private SecretKey deriveKey() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Khong tim thay thuat toan SHA-256", e);
        }
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), spec);

            byte[] cipherTextWithTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherTextWithTag.length);
            buffer.put(iv);
            buffer.put(cipherTextWithTag);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Lỗi mã hóa password", e);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            if (decoded.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Dữ liệu mã hóa không hợp lệ (quá ngắn)");
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherTextWithTag = new byte[buffer.remaining()];
            buffer.get(cipherTextWithTag);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), spec);

            byte[] decrypted = cipher.doFinal(cipherTextWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi giải mã password", e);
        }
    }
}