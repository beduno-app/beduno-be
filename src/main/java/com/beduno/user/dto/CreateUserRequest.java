package com.beduno.user.dto;

import com.beduno.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 255) String password,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull Role role,
        @NotBlank @Size(min = 2, max = 5) String language,
        List<UUID> assignedPropertyIds
) {}
