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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
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
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository =
                CookieCsrfTokenRepository.withHttpOnlyFalse();

        repository.setCookiePath("/");

        return repository;
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
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(
                                new CsrfTokenRequestAttributeHandler()
                        )
                        .ignoringRequestMatchers(
                                "/api/auth/**",
                                "/actuator/health"
                        )
                        .ignoringRequestMatchers(
                                this::hasNoAccessTokenCookie
                        )
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .exceptionHandling(exception ->
                        exception
                                .authenticationEntryPoint(
                                        restAuthenticationEntryPoint
                                )
                                .accessDeniedHandler(
                                        restAccessDeniedHandler
                                )
                )

                .authorizeHttpRequests(auth ->
                        auth
                                .requestMatchers("/api/auth/**").permitAll()
                                .requestMatchers("/actuator/health").permitAll()

                                .dispatcherTypeMatchers(
                                        DispatcherType.ASYNC
                                ).permitAll()

                                .requestMatchers("/api/admin/**")
                                .hasRole("ADMIN")

                                .anyRequest().authenticated()
                )

                .authenticationProvider(authenticationProvider())

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                )

                // QUAN TRONG: CookieCsrfTokenRepository dung co che
                // "deferred token" - token CSRF chi thuc su duoc ghi vao
                // cookie XSRF-TOKEN khi co code nao do goi
                // csrfToken.getToken() de "resolve" no ra. Neu khong co
                // filter nay, khong ai goi ham do -> cookie XSRF-TOKEN
                // KHONG BAO GIO duoc tao -> FE khong co gi de gan vao
                // header X-XSRF-TOKEN -> moi request POST/PUT/PATCH/DELETE
                // deu bi chan 403 boi Spring Security, vinh vien, ke ca
                // sau co che retry o FE (client.ts).
                .addFilterAfter(
                        new CsrfCookieFilter(),
                        CsrfFilter.class
                );

        return http.build();
    }

    // Ep Spring Security "resolve" deferred CsrfToken tren MOI request,
    // qua do CookieCsrfTokenRepository moi thuc su ghi Set-Cookie:
    // XSRF-TOKEN=... vao response. Day la pattern chinh thuc cua Spring
    // Security cho CSRF trong SPA (xem tai lieu "CSRF for Single Page
    // Applications"). Dat SAU CsrfFilter.class de dam bao request
    // attribute CsrfToken.class.getName() da duoc CsrfFilter set truoc do.
    public static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {

            CsrfToken csrfToken =
                    (CsrfToken) request.getAttribute(CsrfToken.class.getName());

            if (csrfToken != null) {
                // Goi getToken() chinh la hanh dong "resolve" deferred
                // token -> kich hoat CookieCsrfTokenRepository.saveToken()
                // -> Set-Cookie: XSRF-TOKEN duoc ghi vao response nay.
                csrfToken.getToken();
            }

            filterChain.doFilter(request, response);
        }
    }

    private boolean hasNoAccessTokenCookie(
            HttpServletRequest request) {

        jakarta.servlet.http.Cookie[] cookies =
                request.getCookies();

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