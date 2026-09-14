package com.beduno.auth;

import com.beduno.IntegrationTestBase;
import com.beduno.auth.dto.AuthResponse;
import com.beduno.auth.dto.LoginRequest;
import com.beduno.user.Role;
import com.beduno.user.UserStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Inserts a real user row with a BCrypt hash of {@link #PASSWORD} and returns its email. */
    private String createLoginUser(UserStatus status) {
        ensureAgencyExists(DEFAULT_AGENCY_ID);
        var email = "login-" + UUID.randomUUID() + "@agency.pl";
        jdbcTemplate.update(
                "INSERT INTO users (agency_id, email, password_hash, first_name, last_name, "
                        + "role, language, status) "
                        + "VALUES (?, ?, ?, 'Test', 'User', 'AGENCY_ADMIN', 'EN', ?)",
                DEFAULT_AGENCY_ID, email, passwordEncoder.encode(PASSWORD), status.name());
        return email;
    }

    @Nested
    class Login {

        @Test
        void shouldReturnTokens_whenCredentialsAreValid() {
            var email = createLoginUser(UserStatus.ACTIVE);

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.accessToken()).isNotBlank();
            assertThat(body.refreshToken()).isNotBlank();
            assertThat(body.expiresIn()).isPositive();
            assertThat(body.user().email()).isEqualTo(email);
            assertThat(body.user().role()).isEqualTo(Role.AGENCY_ADMIN.name());
            assertThat(jwtTokenProvider.getAgencyId(body.accessToken())).isEqualTo(DEFAULT_AGENCY_ID);
            assertThat(jwtTokenProvider.isRefreshToken(body.refreshToken())).isTrue();

            var lastLoginAt = jdbcTemplate.queryForObject(
                    "SELECT last_login_at FROM users WHERE email = ?", Object.class, email);
            assertThat(lastLoginAt).isNotNull();
        }

        @Test
        void shouldReturnUnauthorized_whenPasswordIsWrong() {
            var email = createLoginUser(UserStatus.ACTIVE);

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, "not-the-password"), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.invalid_credentials");
        }

        @Test
        void shouldReturnUnauthorized_whenUserIsInactive() {
            // Deactivation is the only revocation lever the product offers. Without this check a
            // dismissed employee kept logging in with their old password indefinitely, because no
            // authentication path ever consulted users.status.
            var email = createLoginUser(UserStatus.INACTIVE);

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // Same code as a wrong password: an inactive account must not be distinguishable.
            assertThat(response.getBody()).contains("error.auth.invalid_credentials");
        }

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
        void shouldReturnNewPair_whenRefreshTokenIsValid() {
            var email = createLoginUser(UserStatus.ACTIVE);
            var login = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class
            ).getBody();

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    java.util.Map.of("refreshToken", login.refreshToken()),
                    AuthResponse.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = response.getBody();
            assertThat(body.accessToken()).isNotBlank();
            assertThat(body.refreshToken()).isNotBlank();
            assertThat(body.user().email()).isEqualTo(email);
            // The refreshed access token carries the claims a refresh token does not.
            assertThat(jwtTokenProvider.getAgencyId(body.accessToken())).isEqualTo(DEFAULT_AGENCY_ID);
        }

        @Test
        void shouldReturnUnauthorized_whenUserIsInactive() {
            // A refresh token outlives the access token by a week and mints fresh pairs, so
            // skipping the status check here would make deactivation ineffective even after
            // login refuses the user.
            var email = createLoginUser(UserStatus.ACTIVE);
            var login = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class
            ).getBody();

            jdbcTemplate.update("UPDATE users SET status = 'INACTIVE' WHERE email = ?", email);

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    java.util.Map.of("refreshToken", login.refreshToken()),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.invalid_refresh_token");
        }

        /**
         * Refresh tokens are stateless and live seven days, and nothing was ever compared against
         * the database -- so a leaked one kept minting fresh pairs for its whole lifetime and the
         * only lever was rotating JWT_SECRET, which logs out every tenant at once. The token
         * version is that lever, scoped to one account.
         */
        @Test
        void shouldReturnUnauthorized_whenTokenVersionHasMovedOn() {
            var email = createLoginUser(UserStatus.ACTIVE);
            var login = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class
            ).getBody();

            jdbcTemplate.update("UPDATE users SET token_version = token_version + 1 WHERE email = ?", email);

            var response = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    java.util.Map.of("refreshToken", login.refreshToken()),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).contains("error.auth.invalid_refresh_token");
        }

        @Test
        void shouldIssueAUsableToken_whenTokenVersionIsUnchanged() {
            // The version check must not reject ordinary refreshes: the pair handed back by one
            // refresh has to survive the next one.
            var email = createLoginUser(UserStatus.ACTIVE);
            var first = restTemplate.postForEntity(
                    "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class
            ).getBody();

            var second = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    java.util.Map.of("refreshToken", first.refreshToken()), AuthResponse.class
            ).getBody();
            var third = restTemplate.postForEntity(
                    "/api/v1/auth/refresh",
                    java.util.Map.of("refreshToken", second.refreshToken()), AuthResponse.class
            );

            assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

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
