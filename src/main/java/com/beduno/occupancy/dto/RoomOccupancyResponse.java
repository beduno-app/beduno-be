package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record RoomOccupancyResponse(
        UUID roomId,
        String roomNumber,
        Integer floor,
        int bedCount,
        int availableBedCount,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
