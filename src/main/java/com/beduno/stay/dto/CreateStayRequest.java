package com.beduno.stay.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateStayRequest(
        @NotNull UUID workerId,
        @NotNull UUID propertyId,
        @NotNull UUID roomId,
        UUID bedId,
        @NotNull LocalDate dateFrom,
        LocalDate dateTo,
        String overrideReason,
        String notes
) {
}
