package com.beduno.occupancy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record InspectionReportRequest(
        @NotNull @Valid List<RoomActualOccupancy> rooms
) {
}
