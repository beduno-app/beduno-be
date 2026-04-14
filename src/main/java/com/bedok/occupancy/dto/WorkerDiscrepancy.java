package com.bedok.occupancy.dto;

import java.util.UUID;

public record WorkerDiscrepancy(
        UUID workerId,
        String discrepancyType
) {
}
