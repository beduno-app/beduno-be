package com.beduno.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "beduno.jwt")
@Validated
@Getter
@Setter
public class JwtConfig {

    /**
     * Validated because removing the committed default was not by itself enough to fail fast.
     * {@code @ConfigurationProperties} resolves placeholders with unresolvable ones ignored, so an
     * unset {@code JWT_SECRET} bound the literal string {@code ${JWT_SECRET}} instead of failing:
     * the app started, {@code /actuator/health} reported UP, the reverse proxy's health check
     * passed, and every login then returned 500 from a WeakKeyException raised on first use. The
     * minimum length is the 256 bits HS256 requires.
     */
    @NotBlank
    @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256")
    private String secret;

    private long accessTokenExpirationMs = 3600000; // 1 hour
    private long refreshTokenExpirationMs = 604800000; // 7 days
}
