package com.beduno.common.exception;

import com.beduno.IntegrationTestBase;
import com.beduno.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ordinary client mistakes must arrive as 4xx in the shared envelope. They used to reach the
 * catch-all handler and come back as 500 INTERNAL_ERROR, which tells a caller the server is broken
 * when the request was, invites retries of something that will never succeed, and hides real faults
 * among routine noise in the logs.
 */
class ErrorContractIntegrationTest extends IntegrationTestBase {

    @Test
    void shouldReturnMethodNotAllowed_whenVerbIsWrongForTheEndpoint() {
        var response = restTemplate.exchange(
                "/api/v1/auth/login", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("\"error\":\"METHOD_NOT_ALLOWED\"");
        assertThat(response.getBody()).contains("error.method_not_allowed");
    }

    @Test
    void shouldReturnBadRequest_whenBodyIsNotParseableJson() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = restTemplate.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"email\": ", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"error\":\"BAD_REQUEST\"");
    }

    @Test
    void shouldReturnUnsupportedMediaType_whenContentTypeIsNotJson() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var response = restTemplate.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("email=a@b.pl", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).contains("\"error\":\"UNSUPPORTED_MEDIA_TYPE\"");
    }

    @Test
    void shouldReturnBadRequest_whenPathVariableIsNotAUuid() {
        var response = restTemplate.exchange("/api/v1/workers/not-a-uuid", HttpMethod.GET,
                new HttpEntity<>(authHeaders(Role.AGENCY_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"error\":\"BAD_REQUEST\"");
    }

    /**
     * The entry point writes through {@code getWriter()}, whose encoding is whatever the response
     * declares -- and the servlet default is ISO-8859-1. Left alone, the header misdescribes the
     * body and the first localized string placed in one would mojibake Polish and mangle Cyrillic.
     */
    @Test
    void shouldDeclareUtf8_whenRejectingAnUnauthenticatedRequest() {
        var response = restTemplate.getForEntity("/api/v1/workers", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
    }
}
