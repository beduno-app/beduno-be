package com.beduno.occupancy.dto;

import java.util.List;

public record InspectionDiscrepancyResponse(
        List<RoomDiscrepancy> discrepancies,
        boolean hasDiscrepancies
) {
}
