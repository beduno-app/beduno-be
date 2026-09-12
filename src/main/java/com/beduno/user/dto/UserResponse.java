package com.beduno.user.dto;

import com.beduno.user.Role;
import com.beduno.user.UserStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        Role role,
        String language,
        List<UUID> assignedPropertyIds,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {}
