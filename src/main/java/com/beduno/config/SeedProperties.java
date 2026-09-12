package com.beduno.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Switch for {@link SeedRunner}. Off unless a deployment or local profile asks for it, exactly
 * like {@link BootstrapProperties} — see that class for why this is a property and not a
 * migration.
 */
@Configuration
@ConfigurationProperties(prefix = "beduno.seed")
@Getter
@Setter
public class SeedProperties {

    private boolean enabled = false;
}
