package com.beduno.property.dto;

import com.beduno.property.PropertyStatus;

import java.time.Instant;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        String name,
        String address,
        String city,
        PropertyStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
