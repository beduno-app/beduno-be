package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record OccupancyExceptionResponse(
        UUID roomId,
        String roomName,
        String exceptionType,
        int capacity,
        int blockedSpots,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
