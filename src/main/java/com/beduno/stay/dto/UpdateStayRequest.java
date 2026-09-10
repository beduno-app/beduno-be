package com.beduno.stay.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateStayRequest(
        @NotNull UUID roomId,
        UUID bedId,
        @NotNull LocalDate dateFrom,
        LocalDate dateTo,
        String overrideReason,
        String notes
) {
}
