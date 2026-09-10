package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record InspectionRoomEntry(
        UUID roomId,
        String roomNumber,
        Integer floor,
        List<OccupantSummary> expectedOccupants,
        List<OccupantSummary> checkedInOccupants
) {
}
