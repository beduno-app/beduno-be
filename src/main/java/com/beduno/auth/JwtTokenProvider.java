package com.beduno.auth;

import com.beduno.config.JwtConfig;
import com.beduno.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtConfig jwtConfig;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        var now = new Date();
        var expiry = new Date(now.getTime() + jwtConfig.getAccessTokenExpirationMs());

        var propertyIds = user.getAssignedPropertyIds() != null
                ? Arrays.stream(user.getAssignedPropertyIds()).map(UUID::toString).toList()
                : java.util.List.<String>of();

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("agencyId", user.getAgencyId().toString())
                .claim("role", user.getRole().name())
                .claim("properties", propertyIds)
                .claim("lang", user.getLanguage())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(User user) {
        var now = new Date();
        var expiry = new Date(now.getTime() + jwtConfig.getRefreshTokenExpirationMs());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public UUID getAgencyId(String token) {
        return UUID.fromString(parseToken(token).get("agencyId", String.class));
    }

    public long getAccessTokenExpirationSeconds() {
        return jwtConfig.getAccessTokenExpirationMs() / 1000;
    }
}
