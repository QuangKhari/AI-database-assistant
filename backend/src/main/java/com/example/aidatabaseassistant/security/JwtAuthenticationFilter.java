package com.example.aidatabaseassistant.security;

import com.example.aidatabaseassistant.config.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    /**
     * Lay token JWT tu request.
     *
     * UU TIEN cookie httpOnly "access_token" (luong browser that, dat boi
     * AuthController sau khi login/register) - FALLBACK ve header
     * "Authorization: Bearer ..." de:
     *   1. Khong pha cac integration test hien co dang tu set header nay
     *      truc tiep (TestRestTemplate/MockMvc).
     *   2. Van ho tro client kieu API thuan (Postman, script, mobile...)
     *      khong dung cookie.
     */
    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();

        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AuthCookie.ACCESS_TOKEN_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }

        return null;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token != null) {

            if (jwtUtil.validateToken(token)) {
                try {
                    String username = jwtUtil.extractUsername(token);

                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    if (!userDetails.isAccountNonLocked()) {
                        SecurityContextHolder.clearContext();
                        filterChain.doFilter(request, response);
                        return;
                    }

                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authToken);

                } catch (Exception e) {
                    log.warn(
                            "Xac thuc JWT that bai cho request {} {}: {}",
                            request.getMethod(),
                            request.getRequestURI(),
                            e.toString()
                    );
                    SecurityContextHolder.clearContext();
                }
            } else {
                log.warn(
                        "Token JWT khong hop le (validateToken=false) cho request {} {}",
                        request.getMethod(),
                        request.getRequestURI()
                );
            }
        } else {
            log.debug(
                    "Khong tim thay token (cookie/Authorization) cho request {} {}",
                    request.getMethod(),
                    request.getRequestURI()
            );
        }

        filterChain.doFilter(request, response);
    }
}