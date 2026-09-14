package com.beduno.config;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One container and one Spring context for both startup-runner tests.
 *
 * <p>They need their own database because they empty the schema between cases, which the shared
 * container in {@code IntegrationTestBase} cannot afford -- but they did not need one *each*. Two
 * classes declaring identical containers and identical {@code @DynamicPropertySource} blocks meant
 * the suite paid for two extra Postgres start-ups and two extra context boots, roughly thirty
 * seconds apiece on a hosted runner, for isolation from each other that neither one needs. Sharing
 * the declaration also means the next runner test inherits it rather than adding a fifth container.
 *
 * <p>Both runners are disabled at start-up here: each test drives its own runner explicitly, with
 * the properties that case is about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class RunnerTestBase {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("beduno.jwt.secret", () -> "test-secret-key-that-is-at-least-256-bits-long-for-hs256");
        registry.add("beduno.bootstrap.enabled", () -> "false");
        registry.add("beduno.seed.enabled", () -> "false");
    }
}
