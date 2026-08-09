package com.beduno.auth;

import com.beduno.common.model.BaseEntity;
import com.beduno.config.JwtConfig;
import com.beduno.user.Role;
import com.beduno.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        var config = new JwtConfig();
        config.setSecret("test-secret-key-that-is-at-least-256-bits-long-for-hs256-test");
        config.setAccessTokenExpirationMs(3600000);
        config.setRefreshTokenExpirationMs(604800000);
        provider = new JwtTokenProvider(config);
    }

    private User createTestUser() {
        var user = new User();
        try {
            var idField = BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, UUID.fromString("11111111-1111-1111-1111-111111111111"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        user.setAgencyId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        user.setRole(Role.AGENCY_ADMIN);
        user.setLanguage("PL");
        user.setAssignedPropertyIds(new UUID[]{
                UUID.fromString("33333333-3333-3333-3333-333333333333")
        });
        return user;
    }

    @Nested
    class AccessToken {

        @Test
        void shouldGenerateValidToken() {
            var token = provider.generateAccessToken(createTestUser());
            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        void shouldContainCorrectUserId() {
            var token = provider.generateAccessToken(createTestUser());
            assertThat(provider.getUserId(token))
                    .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        }

        @Test
        void shouldContainCorrectAgencyId() {
            var token = provider.generateAccessToken(createTestUser());
            assertThat(provider.getAgencyId(token))
                    .isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        }

        @Test
        void shouldContainRoleClaim() {
            var token = provider.generateAccessToken(createTestUser());
            var claims = provider.parseToken(token);
            assertThat(claims.get("role", String.class)).isEqualTo("AGENCY_ADMIN");
        }

        @Test
        void shouldContainLanguageClaim() {
            var token = provider.generateAccessToken(createTestUser());
            var claims = provider.parseToken(token);
            assertThat(claims.get("lang", String.class)).isEqualTo("PL");
        }
    }

    @Nested
    class RefreshToken {

        @Test
        void shouldGenerateValidRefreshToken() {
            var token = provider.generateRefreshToken(createTestUser());
            assertThat(provider.validateToken(token)).isTrue();
        }

        @Test
        void shouldContainRefreshTypeClaim() {
            var token = provider.generateRefreshToken(createTestUser());
            var claims = provider.parseToken(token);
            assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        }
    }

    @Nested
    class Validation {

        @Test
        void shouldRejectInvalidToken() {
            assertThat(provider.validateToken("invalid.token.here")).isFalse();
        }

        @Test
        void shouldRejectNullToken() {
            assertThat(provider.validateToken(null)).isFalse();
        }

        @Test
        void shouldRejectEmptyToken() {
            assertThat(provider.validateToken("")).isFalse();
        }
    }
}
