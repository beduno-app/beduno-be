package com.bedok.room.dto;

import com.bedok.room.GenderRule;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String floor,
        @Min(1) int capacity,
        @Min(0) int blockedSpots,
        GenderRule genderRule,
        String notes
) {}
