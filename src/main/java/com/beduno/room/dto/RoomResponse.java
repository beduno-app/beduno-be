package com.beduno.room.dto;

import com.beduno.room.GenderRule;
import com.beduno.room.RoomStatus;

import java.time.Instant;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        UUID propertyId,
        String name,
        String floor,
        int capacity,
        int blockedSpots,
        int availableSpots,
        GenderRule genderRule,
        RoomStatus status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
