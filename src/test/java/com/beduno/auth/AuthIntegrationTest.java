package com.beduno.auth;

import com.beduno.IntegrationTestBase;
import com.beduno.auth.dto.LoginRequest;
import com.beduno.user.Role;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends IntegrationTestBase {

    @Nested
    class Login {

        @Test
        void shouldReturnUnauthorized_whenUserDoesNotExist() {
            var request = new LoginRequest("nonexistent@agency.pl", "password");
            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", request, String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"");
            assertThat(response.getBody()).contains("error.auth.invalid_credentials");
        }

        @Test
        void shouldReturnBadRequest_whenFieldsAreMissing() {
            var request = java.util.Map.of("email", "", "password", "");
            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", request, String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Refresh {

        @Test
        void shouldReturnUnauthorized_whenRefreshTokenIsInvalid() {
            var request = java.util.Map.of("refreshToken", "invalid-token");
            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh", request, String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.invalid_refresh_token");
        }
    }

    @Nested
    class Me {

        @Test
        void shouldReturnNotFound_whenJwtSubjectHasNoUserRow() {
            var headers = authHeaders(Role.AGENCY_ADMIN);
            // JWT is structurally valid but its subject has no row in users.
            var response = restTemplate.exchange(
                    "/api/v1/auth/me", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    class ProtectedEndpoints {

        @Test
        void shouldReturnUnauthorizedWithErrorBody_whenNoTokenIsSupplied() {
            var response = restTemplate.getForEntity("/api/v1/workers", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"");
            assertThat(response.getBody()).contains("error.auth.unauthorized");
        }

        @Test
        void shouldReturnForbiddenWithErrorBody_whenRoleIsInsufficient() {
            var headers = authHeaders(Role.FRONT_DESK);
            var body = java.util.Map.of(
                    "internalId", "W-001",
                    "firstName", "Jan",
                    "lastName", "Kowalski",
                    "gender", "MALE");

            var response = restTemplate.exchange(
                    "/api/v1/workers", HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("\"error\":\"FORBIDDEN\"");
        }
    }
}
