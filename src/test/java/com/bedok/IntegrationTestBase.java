package com.bedok;

import com.bedok.auth.JwtTokenProvider;
import com.bedok.user.Role;
import com.bedok.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class IntegrationTestBase {

    protected static final UUID DEFAULT_AGENCY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    protected HttpHeaders authHeaders(Role role, UUID agencyId) {
        var user = new User();
        try {
            var idField = com.bedok.common.model.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        user.setAgencyId(agencyId);
        user.setRole(role);
        user.setLanguage("EN");
        user.setAssignedPropertyIds(new UUID[0]);

        var token = jwtTokenProvider.generateAccessToken(user);
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected HttpHeaders authHeaders(Role role) {
        return authHeaders(role, DEFAULT_AGENCY_ID);
    }
}
