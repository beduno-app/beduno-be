package com.beduno.stay.dto;

import jakarta.validation.constraints.AssertTrue;
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

    /**
     * Stays are half-open periods, so a same-day dateTo describes an empty stay. Without this the
     * request passed the constraint engine and only failed at the chk_stays_dates CHECK, which
     * surfaced as a 500.
     */
    @AssertTrue(message = "error.stay.invalid_dates")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || dateTo.isAfter(dateFrom);
    }
}
