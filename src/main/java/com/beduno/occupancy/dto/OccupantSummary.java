package com.beduno.occupancy.dto;

import java.util.UUID;

public record OccupantSummary(
        UUID stayId,
        UUID workerId,
        String firstName,
        String lastName,
        UUID bedId,
        String bedLabel
) {
}
