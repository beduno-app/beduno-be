package com.bedok.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record RoomOccupancyResponse(
        UUID roomId,
        String roomName,
        String floor,
        int capacity,
        int blockedSpots,
        int occupiedSpots,
        List<OccupantSummary> occupants
) {
}
