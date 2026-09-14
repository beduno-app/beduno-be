package com.beduno.auth;

import com.beduno.auth.dto.AuthResponse;
import com.beduno.auth.dto.LoginRequest;
import com.beduno.auth.dto.RefreshRequest;
import com.beduno.common.exception.NotFoundException;
import com.beduno.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import com.beduno.user.User;
import com.beduno.user.UserRepository;
import com.beduno.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * Compared against when the email is unknown, so that the BCrypt cost is paid on every login
     * attempt and an unknown address cannot be told from a known one by response time.
     */
    private static final String DUMMY_PASSWORD_HASH =
            new BCryptPasswordEncoder().encode("this-value-is-never-a-password");

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email());

        // Always run a BCrypt comparison, against a fixed dummy hash when the email is unknown,
        // so that the response time does not reveal whether an account exists. The dummy hash
        // can never match a real password because it was encoded from a value no caller sends.
        var passwordHash = user.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH);
        var passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        // An inactive account is reported exactly like a wrong password: the caller must not be
        // able to tell a deactivated colleague's address from an unknown one.
        if (user.isEmpty() || !passwordMatches || user.get().getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("error.auth.invalid_credentials");
        }

        var authenticated = user.get();
        authenticated.setLastLoginAt(Instant.now());
        return buildAuthResponse(authenticated);
    }

    public AuthResponse refresh(RefreshRequest request) {
        // Parsed once. validateToken, isRefreshToken and the claim reads each used to re-verify
        // the HMAC over the same string, so a single refresh cost three verifications.
        Claims claims;
        try {
            claims = jwtTokenProvider.parseToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("error.auth.invalid_refresh_token");
        }
        if (!JwtTokenProvider.REFRESH_TOKEN_TYPE.equals(claims.get("type", String.class))) {
            throw new UnauthorizedException("error.auth.invalid_refresh_token");
        }

        // A token whose subject no longer exists is an invalid token, not a missing
        // resource — reporting it as 404 would leak whether an account was deleted.
        var user = userRepository.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(() -> new UnauthorizedException("error.auth.invalid_refresh_token"));

        // Deactivation has to bite here too, otherwise a refresh token issued before the
        // deactivation keeps minting fresh pairs for its full 7-day life, indefinitely.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException("error.auth.invalid_refresh_token");
        }

        // The revocation lever: a token minted before the user's version was bumped is stale,
        // even though its signature and expiry are both still perfectly valid.
        if (jwtTokenProvider.getTokenVersion(claims) != user.getTokenVersion()) {
            throw new UnauthorizedException("error.auth.invalid_refresh_token");
        }

        return buildAuthResponse(user);
    }

    public AuthResponse.UserInfo getCurrentUser(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("error.auth.user_not_found"));
        return toUserInfo(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        var accessToken = jwtTokenProvider.generateAccessToken(user);
        var refreshToken = jwtTokenProvider.generateRefreshToken(user);
        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirationSeconds(),
                toUserInfo(user)
        );
    }

    private AuthResponse.UserInfo toUserInfo(User user) {
        var propertyIds = user.getAssignedPropertyIds() != null
                ? Arrays.stream(user.getAssignedPropertyIds()).map(UUID::toString).toArray(String[]::new)
                : new String[0];
        return new AuthResponse.UserInfo(
                user.getId().toString(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole().name(),
                user.getLanguage(),
                propertyIds
        );
    }
}
