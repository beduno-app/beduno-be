package com.beduno.auth;

import com.beduno.auth.dto.AuthResponse;
import com.beduno.auth.dto.LoginRequest;
import com.beduno.auth.dto.RefreshRequest;
import com.beduno.common.exception.NotFoundException;
import com.beduno.user.User;
import com.beduno.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException("error.auth.invalid_credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new NotFoundException("error.auth.invalid_credentials");
        }

        user.setLastLoginAt(Instant.now());
        return buildAuthResponse(user);
    }

    public AuthResponse refresh(RefreshRequest request) {
        if (!jwtTokenProvider.validateToken(request.refreshToken())) {
            throw new NotFoundException("error.auth.invalid_refresh_token");
        }

        var userId = jwtTokenProvider.getUserId(request.refreshToken());
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("error.auth.user_not_found"));

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
