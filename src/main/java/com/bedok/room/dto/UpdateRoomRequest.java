package com.bedok.room.dto;

import com.bedok.room.GenderRule;
import com.bedok.room.RoomStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateRoomRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String floor,
        @Min(1) int capacity,
        @Min(0) int blockedSpots,
        @NotNull GenderRule genderRule,
        @NotNull RoomStatus status,
        String notes
) {}
