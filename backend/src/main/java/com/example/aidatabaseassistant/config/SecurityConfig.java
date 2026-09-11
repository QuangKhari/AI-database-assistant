package com.example.aidatabaseassistant.config;

import com.example.aidatabaseassistant.security.CustomUserDetailsService;
import com.example.aidatabaseassistant.security.JwtAuthenticationFilter;
import com.example.aidatabaseassistant.security.RestAccessDeniedHandler;
import com.example.aidatabaseassistant.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(passwordEncoder());

        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {

        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(
                List.of(allowedOrigins.split("\\s*,\\s*"))
        );

        config.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
                .cors(cors ->
                        cors.configurationSource(corsConfigurationSource())
                )

                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                CookieCsrfTokenRepository.withHttpOnlyFalse()
                        )
                        .csrfTokenRequestHandler(
                                new CsrfTokenRequestAttributeHandler()
                        )
                        .ignoringRequestMatchers("/api/auth/**", "/actuator/health")
                        .ignoringRequestMatchers(this::hasNoAccessTokenCookie)
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .exceptionHandling(exception ->
                        exception
                                .authenticationEntryPoint(restAuthenticationEntryPoint)
                                .accessDeniedHandler(restAccessDeniedHandler)
                )

                .authorizeHttpRequests(auth ->
                        auth
                                // Authentication endpoints
                                .requestMatchers("/api/auth/**").permitAll()

                                // Docker health check
                                .requestMatchers("/actuator/health").permitAll()

                                // SSE/async dispatch:
                                // request ban đầu vẫn phải authenticated,
                                // chỉ cho phép async continuation tiếp tục.
                                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                                // Admin endpoints
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                                // Tất cả API còn lại bắt buộc đăng nhập
                                .anyRequest().authenticated()
                )

                .authenticationProvider(authenticationProvider())

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                // Ep resolve CsrfToken tren MOI request de cookie
                // "XSRF-TOKEN" thuc su duoc ghi ra ngay tu request dau tien
                // (mac dinh Spring Security 6 chi tao token "lazy" - neu
                // khong ep, FE se khong bao gio thay cookie nay de doc lai).
                .addFilterAfter(
                        new CsrfCookieFilter(),
                        BasicAuthenticationFilter.class
                );

        return http.build();
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                @org.springframework.lang.NonNull HttpServletRequest request,
                @org.springframework.lang.NonNull HttpServletResponse response,
                @org.springframework.lang.NonNull FilterChain filterChain)
                throws ServletException, IOException {

            CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");

            if (csrfToken != null) {
                // Goi .getToken() la thao tac kich hoat CookieCsrfTokenRepository
                // thuc su ghi cookie XSRF-TOKEN vao response.
                csrfToken.getToken();
            }

            filterChain.doFilter(request, response);
        }
    }

    /**
     * True neu request KHONG mang cookie "access_token" - tuc la request
     * nay khong the bi loi dung qua CSRF (xem giai thich chi tiet o
     * .csrf(...) ben tren), nen duoc bo qua yeu cau CSRF token.
     *
     * Bao gom ca truong hop dung header Authorization (khong cookie) lan
     * request hoan toan chua dang nhap (khong cookie, khong header) - ca
     * hai deu phai duoc bo qua CSRF de giu dung hanh vi 401 cu, khong bi
     * CSRF chan nham thanh 403.
     */
    private boolean hasNoAccessTokenCookie(HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return true;
        }

        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (com.example.aidatabaseassistant.security.AuthCookie.ACCESS_TOKEN_COOKIE
                    .equals(cookie.getName())) {
                return false;
            }
        }

        return true;
    }
}