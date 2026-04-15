package com.bedok.stay.dto;

import java.util.List;
import java.util.UUID;

public record BulkAssignResult(
        int created,
        int errors,
        List<AssignmentResult> results
) {
    public record AssignmentResult(
            int index,
            UUID workerId,
            UUID stayId,
            String status,
            String errorCode
    ) {}
}
