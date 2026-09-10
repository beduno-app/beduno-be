package com.beduno.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS was {@code allowedOriginPatterns("*")} together with {@code allowCredentials(true)} -- the
 * pattern-based bypass of the rule forbidding a wildcard with credentials, so it genuinely let any
 * site make credentialed calls. It is now an explicit allowlist that defaults to nothing.
 */
class WebConfigCorsTest {

    private static int mappingCount(CorsRegistry registry) {
        // getCorsConfigurations() is protected on CorsRegistry.
        try {
            var method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
            method.setAccessible(true);
            return ((java.util.Map<?, ?>) method.invoke(registry)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void shouldRegisterNoMapping_whenNoOriginsConfigured() {
        var registry = new CorsRegistry();
        new WebConfig(List.of()).addCorsMappings(registry);

        assertThat(mappingCount(registry))
                .as("an unconfigured deployment must reject cross-origin calls, not allow all of them")
                .isZero();
    }

    @Test
    void shouldRegisterNoMapping_whenOriginsAreBlankStrings() {
        // CORS_ALLOWED_ORIGINS="" binds as a single empty element rather than an empty list.
        var registry = new CorsRegistry();
        new WebConfig(List.of("", "  ")).addCorsMappings(registry);

        assertThat(mappingCount(registry)).isZero();
    }

    @Test
    void shouldRegisterMapping_whenOriginsConfigured() {
        var registry = new CorsRegistry();
        new WebConfig(List.of("https://app.beduno.example")).addCorsMappings(registry);

        assertThat(mappingCount(registry)).isEqualTo(1);
    }
}
