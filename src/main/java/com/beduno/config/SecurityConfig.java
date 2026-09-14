package com.beduno.config;

import com.beduno.common.security.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import com.beduno.common.security.RestAccessDeniedHandler;
import com.beduno.common.security.RestAuthenticationEntryPoint;
import com.beduno.common.security.TenantFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final TenantFilter tenantFilter;
    private final RateLimitFilter rateLimitFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${beduno.security.public-api-docs:true}") boolean publicApiDocs) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(fo -> fo.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> {
                    // Only the two endpoints that mint tokens are anonymous. /auth/me was
                    // covered by an /auth/** wildcard, so an unauthenticated call reached the
                    // controller with a null principal and died in the catch-all handler as a
                    // 500; it now falls through to anyRequest() and answers 401 like everything
                    // else behind the entry point.
                    auth.requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll();
                    auth.requestMatchers("/actuator/health").permitAll();
                    // Anonymous access to the API specification is a deployment choice, not a
                    // constant: it is convenient locally and hands an attacker a map in production.
                    if (publicApiDocs) {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Both filters are {@code @Component}s and are also placed in the chain above, so Boot's
     * servlet-container auto-registration adds each of them a second time as a plain servlet
     * filter -- once outside the security chain, where the principal is not yet set. They extend
     * {@code OncePerRequestFilter}, which masks the effect, but the duplicate registration is
     * still there and the masking is incidental. These turn it off.
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

}
