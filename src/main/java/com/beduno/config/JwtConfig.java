package com.beduno.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "beduno.jwt")
@Getter
@Setter
public class JwtConfig {

    private String secret;
    private long accessTokenExpirationMs = 3600000; // 1 hour
    private long refreshTokenExpirationMs = 604800000; // 7 days
}
