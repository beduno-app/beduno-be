package com.beduno.occupancy.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record RoomActualOccupancy(
        @NotNull UUID roomId,
        @NotNull List<UUID> presentWorkerIds
) {
}
