package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record OccupancyExceptionResponse(
        UUID roomId,
        String roomNumber,
        String exceptionType,
        int capacity,
        int blockedSpots,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
