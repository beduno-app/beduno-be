package com.beduno.common.security;

import com.beduno.auth.JwtTokenProvider;
import com.beduno.user.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        try {
            var token = extractToken(request);
            // The refresh check is the other half of the rule enforced in AuthService: the two
            // token kinds are not interchangeable in either direction. It also keeps the claim
            // reads below safe — a refresh token carries no agencyId, and UUID.fromString(null)
            // inside a filter surfaces as a 500 rather than the 401 this case deserves.
            if (token != null && jwtTokenProvider.validateToken(token)
                    && !jwtTokenProvider.isRefreshToken(token)) {
                var claims = jwtTokenProvider.parseToken(token);

                var userId = UUID.fromString(claims.getSubject());
                var agencyId = UUID.fromString(claims.get("agencyId", String.class));
                var role = Role.valueOf(claims.get("role", String.class));
                var lang = claims.get("lang", String.class);

                @SuppressWarnings("unchecked")
                var propertyStrings = (List<String>) claims.get("properties", List.class);
                UUID[] propertyIds;
                if (propertyStrings != null) {
                    propertyIds = propertyStrings.stream()
                            .map(UUID::fromString)
                            .toArray(UUID[]::new);
                } else {
                    propertyIds = new UUID[0];
                }

                var currentUser = new CurrentUser(userId, agencyId, role, propertyIds, lang);
                TenantContext.setAgencyId(agencyId);
                MDC.put("agencyId", agencyId.toString());
                MDC.put("userId", userId.toString());

                var auth = new UsernamePasswordAuthenticationToken(
                        currentUser, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
