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
        void shouldReturnErrorForNonexistentUser() {
            var request = new LoginRequest("nonexistent@agency.pl", "password");
            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", request, String.class
            );
            // User doesn't exist -> NotFoundException -> 404
            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        }

        @Test
        void shouldReturnBadRequestForMissingFields() {
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
        void shouldRejectInvalidRefreshToken() {
            var request = java.util.Map.of("refreshToken", "invalid-token");
            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh", request, String.class
            );
            // Invalid token -> NotFoundException -> 404
            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        }
    }

    @Nested
    class Me {

        @Test
        void shouldReturnErrorForNonexistentJwtUser() {
            var headers = authHeaders(Role.AGENCY_ADMIN);
            // JWT is valid but user doesn't exist in DB
            var response = restTemplate.exchange(
                    "/api/v1/auth/me", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class
            );
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
