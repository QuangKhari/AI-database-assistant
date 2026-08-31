package com.example.aidatabaseassistant.security;

import com.example.aidatabaseassistant.config.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {

            String token = header.substring(7);

            if (jwtUtil.validateToken(token)) {
                try {
                    String username = jwtUtil.extractUsername(token);

                    UserDetails userDetails =
                            userDetailsService.loadUserByUsername(username);
                    if (!userDetails.isAccountNonLocked()) {
                        SecurityContextHolder.clearContext();
                        filterChain.doFilter(request, response);
                        return;
                    }

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    SecurityContextHolder.getContext().setAuthentication(authToken);

                } catch (Exception e) {
                    // TRUOC DAY: nuot loi hoan toan, khong log gi ca -
                    // khien request bi coi la "chua dang nhap" (anonymous)
                    // ma khong ai biet ly do that su la gi. Log lai de
                    // con debug duoc (VD: user bi xoa sau khi token da
                    // phat hanh, DB loi, role null...).
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
        } else if (header != null) {

            // Co header Authorization nhung khong dung dinh dang "Bearer ..."
            log.warn(
                    "Header Authorization sai dinh dang (khong bat dau bang 'Bearer ') cho request {} {}",
                    request.getMethod(),
                    request.getRequestURI()
            );
        } else {

            // Hoan toan khong co header Authorization - binh thuong voi cac
            // endpoint public (/api/auth/**), nhung neu xay ra voi endpoint
            // can dang nhap thi day chinh la nguyen nhan. Chi log DEBUG vi
            // se rat nhieu voi cac request public.
            log.debug(
                    "Khong co header Authorization cho request {} {}",
                    request.getMethod(),
                    request.getRequestURI()
            );
        }

        filterChain.doFilter(request, response);
    }
}