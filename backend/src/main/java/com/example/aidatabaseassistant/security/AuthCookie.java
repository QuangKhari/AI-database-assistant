package com.example.aidatabaseassistant.security;

/**
 * Ten cookie httpOnly chua JWT.
 *
 * package "security" (khong phai "controller") de
 * JwtAuthenticationFilter khong phai phu thuoc nguoc vao tang controller -
 * AuthController (tang controller) moi la noi phu thuoc vao security,
 * dung chieu kien truc thong thuong.
 */
public final class AuthCookie {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    private AuthCookie() {
    }
}