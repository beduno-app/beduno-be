package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record RoomOccupancyResponse(
        UUID roomId,
        String roomNumber,
        Integer floor,
        int capacity,
        int blockedSpots,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
