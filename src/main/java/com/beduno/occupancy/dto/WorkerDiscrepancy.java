package com.beduno.occupancy.dto;

import java.util.UUID;

public record WorkerDiscrepancy(
        UUID workerId,
        String discrepancyType
) {
}
