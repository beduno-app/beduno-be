package com.beduno.bed.dto;

import com.beduno.bed.BedStatus;

import java.time.Instant;
import java.util.UUID;

public record BedResponse(
        UUID id,
        UUID roomId,
        String label,
        BedStatus status,
        Instant createdAt,
        Instant updatedAt
) {}
