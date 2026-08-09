package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record InspectionRoomEntry(
        UUID roomId,
        String roomName,
        String floor,
        List<OccupantSummary> expectedOccupants,
        List<OccupantSummary> checkedInOccupants
) {
}
