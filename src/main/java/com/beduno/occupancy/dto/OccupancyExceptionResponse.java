package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record OccupancyExceptionResponse(
        UUID roomId,
        String roomNumber,
        String exceptionType,
        int bedCount,
        int availableBedCount,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
