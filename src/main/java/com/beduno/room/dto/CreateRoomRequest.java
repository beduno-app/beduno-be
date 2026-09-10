package com.beduno.room.dto;

import com.beduno.room.GenderRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 100) String roomNumber,
        Integer floor,
        GenderRule genderRule,
        String notes
) {}
