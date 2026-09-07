package com.example.aidatabaseassistant.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Helper DUNG CHUNG cho cac test can tao JWT "bat thuong" (het han, sai
 * chu ky) ma khong the tao qua JwtUtil.generateToken() binh thuong (Phan
 * 2.6/3.7: "401 token het han/khong hop le").
 *
 * Secret PHAI khop voi jwt.secret trong
 * backend/src/test/resources/application.properties, neu khong token se
 * bi coi la "invalid signature" thay vi "expired" - vAn la 401 nhung sai
 * y nghia test muon kiem tra.
 */
public final class JwtTestSupport {

    private static final String TEST_SECRET =
            "TestOnlySecretKeyForJUnitDoNotUseInProduction123456";

    private JwtTestSupport() {
    }

    private static SecretKey signingKey() {
        return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** Token da het han 1 giay truoc - dung dung secret nen se qua duoc
     *  buoc verify chu ky nhung fail o buoc kiem tra expiration. */
    public static String expiredToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now.getTime() - 10_000))
                .expiration(new Date(now.getTime() - 1_000))
                .signWith(signingKey(), io.jsonwebtoken.SignatureAlgorithm.HS256)
                .compact();
    }

    /** Chuoi khong phai JWT hop le (malformed) - dung cho test 401 voi
     *  token "linh tinh" chu khong phai het han. */
    public static String malformedToken() {
        return "this.is.not-a-valid-jwt";
    }

    /** JWT ky bang secret KHAC - mo phong token bi gia mao/sua doi. */
    public static String tokenSignedWithWrongSecret(String username) {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "AnotherSecretKeyThatDoesNotMatchServer1234567890".getBytes(StandardCharsets.UTF_8));

        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000))
                .signWith(wrongKey, io.jsonwebtoken.SignatureAlgorithm.HS256)
                .compact();
    }
}