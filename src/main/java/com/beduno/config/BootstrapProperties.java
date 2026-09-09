package com.beduno.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Credentials for the first agency and the first AGENCY_ADMIN. There is no user-management API
 * (open-questions Q6), so without this a freshly deployed instance has an empty users table, no
 * way to obtain a token, and therefore no reachable endpoint at all.
 */
@Configuration
@ConfigurationProperties(prefix = "beduno.bootstrap")
@Getter
@Setter
public class BootstrapProperties {

    /** Off unless a deployment asks for it; the runner is a no-op when false. */
    private boolean enabled = false;

    private String agencyName;
    private String adminEmail;
    private String adminPassword;
    private String adminFirstName = "Agency";
    private String adminLastName = "Admin";
    private String adminLanguage = "PL";
}
