package com.beduno.property.dto;

import com.beduno.property.PropertyStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdatePropertyRequest(
        @NotBlank @Size(max = 255) String name,
        String address,
        @Size(max = 100) String city,
        String notes,
        @NotNull PropertyStatus status
) {}
