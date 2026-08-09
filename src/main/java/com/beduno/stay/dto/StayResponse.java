package com.beduno.stay.dto;

import com.beduno.stay.StayStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StayResponse(
        UUID id,
        UUID workerId,
        UUID propertyId,
        UUID roomId,
        LocalDate dateFrom,
        LocalDate dateTo,
        StayStatus status,
        String overrideReason,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
