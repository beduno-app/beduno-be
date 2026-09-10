package com.beduno.auth;

import com.beduno.IntegrationTestBase;
import com.beduno.auth.dto.LoginRequest;
import com.beduno.user.Role;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        @Test
        void shouldReturnUnauthorized_whenAccessTokenIsPresentedAsRefreshToken() {
            // Both tokens are signed with the same key, so signature validation alone accepted an
            // access token here and handed back a fresh 7-day refresh token in exchange. Access
            // tokens travel on every call and leak far more readily; renewing one into a refresh
            // token would have made a short-lived credential effectively permanent.
            var accessToken = jwtTokenProvider.generateAccessToken(
                    testUser(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID, new UUID[0]));

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh", java.util.Map.of("refreshToken", accessToken), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.invalid_refresh_token");
        }

        @Test
        void shouldReturnUnauthorized_whenRefreshTokenIsPresentedAsAccessToken() {
            // The other direction. A refresh token carries no agencyId claim, so the tenant filter
            // used to reach UUID.fromString(null) and the catch-all handler reported that as 500.
            var refreshToken = jwtTokenProvider.generateRefreshToken(
                    testUser(Role.AGENCY_ADMIN, DEFAULT_AGENCY_ID, new UUID[0]));
            var headers = new HttpHeaders();
            headers.setBearerAuth(refreshToken);

            var response = restTemplate.exchange(
                    "/api/v1/workers", HttpMethod.GET, new HttpEntity<>(headers), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.unauthorized");
        }
    }

    @Nested
    class Me {

        @Test
        void shouldReturnUnauthorized_whenCalledAnonymously() {
            // /api/v1/auth/** was permitted wholesale, so this reached the controller with a null
            // principal and came back as 500 from the catch-all handler.
            var response = restTemplate.getForEntity("/api/v1/auth/me", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("\"error\":\"UNAUTHORIZED\"");
            assertThat(response.getBody()).contains("error.auth.unauthorized");
        }

        @Test
        void shouldReturnNotFound_whenJwtSubjectHasNoUserRow() {
            // JWT is structurally valid but its subject has no row in users. The id is generated
            // rather than DEFAULT_USER_ID, which other test classes insert.
            var headers = authHeadersForUser(Role.AGENCY_ADMIN, UUID.randomUUID());
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

    @Nested
    class EmailUniqueness {

        @Test
        void shouldRejectSameEmail_whenItAlreadyExistsInAnotherAgency() {
            // Login looks a user up by email with no tenant filter. UNIQUE (agency_id, email)
            // allowed the same address in two agencies, which made that lookup -- and therefore
            // which tenant the caller was authenticated into -- non-deterministic.
            ensureAgencyExists(DEFAULT_AGENCY_ID);
            ensureAgencyExists(OTHER_AGENCY_ID);
            var email = "shared-" + UUID.randomUUID() + "@agency.pl";

            insertUser(DEFAULT_AGENCY_ID, email);

            assertThatThrownBy(() -> insertUser(OTHER_AGENCY_ID, email))
                    .hasMessageContaining("uq_users_email");
        }

        private void insertUser(UUID agencyId, String email) {
            jdbcTemplate.update(
                    "INSERT INTO users (agency_id, email, password_hash, first_name, last_name, "
                            + "role, language, status) "
                            + "VALUES (?, ?, 'hash', 'Test', 'User', 'AGENCY_ADMIN', 'EN', 'ACTIVE')",
                    agencyId, email);
        }
    }
}
