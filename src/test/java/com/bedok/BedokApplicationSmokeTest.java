package com.bedok;

import com.bedok.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BedokApplicationSmokeTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldStartApplication() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void shouldRejectUnauthenticatedAccessToProtectedEndpoints() {
        // /api/v1/auth/me requires authentication when accessed without /auth/** prefix matching
        // Spring Security may return 401 or 403 for unauthenticated requests
        var response = restTemplate.getForEntity("/api/v1/users", String.class);
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void shouldAcceptValidJwt() {
        var headers = authHeaders(Role.AGENCY_ADMIN);
        // /auth/me will 404 because the test user doesn't exist in DB,
        // but the JWT itself is accepted (not 401)
        var response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void shouldAllowActuatorHealthWithoutAuth() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
