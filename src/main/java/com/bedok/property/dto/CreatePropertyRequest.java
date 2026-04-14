package com.bedok.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePropertyRequest(
        @NotBlank @Size(max = 255) String name,
        String address,
        @Size(max = 100) String city,
        String notes
) {}
