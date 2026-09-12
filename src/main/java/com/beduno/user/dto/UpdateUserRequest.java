package com.beduno.user.dto;

import com.beduno.user.Role;
import com.beduno.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull Role role,
        @Size(min = 2, max = 5) String language,
        List<UUID> assignedPropertyIds,
        @NotNull UserStatus status
) {}
