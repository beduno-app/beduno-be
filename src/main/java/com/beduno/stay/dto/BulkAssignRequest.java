package com.beduno.stay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BulkAssignRequest(
        @NotEmpty @Valid List<Assignment> assignments
) {
    public record Assignment(
            @NotNull UUID workerId,
            @NotNull UUID propertyId,
            @NotNull UUID roomId,
            @NotNull LocalDate dateFrom,
            LocalDate dateTo,
            String overrideReason
    ) {}
}
