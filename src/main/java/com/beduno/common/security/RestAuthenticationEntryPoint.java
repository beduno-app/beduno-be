package com.beduno.common.security;

import com.beduno.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Returns 401 with the standard {@link ErrorResponse} envelope when an
 * unauthenticated caller hits a protected endpoint.
 *
 * <p>Without this, Spring Security falls back to {@code Http403ForbiddenEntryPoint}
 * and emits a bodyless 403, which clients cannot distinguish from a genuine
 * role-based denial.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // Explicit: the servlet default is ISO-8859-1 and getWriter() encodes with whatever the
        // response declares, so without this the header lies and any localized string mojibakes.
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of("UNAUTHORIZED", "error.auth.unauthorized"));
    }
}
