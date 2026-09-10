package com.beduno.occupancy.dto;

import java.util.List;
import java.util.UUID;

public record RoomDiscrepancy(
        UUID roomId,
        String roomNumber,
        List<WorkerDiscrepancy> items
) {
}
