package com.beduno.stay.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
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

    /** See {@link CreateStayRequest#isDateRangeValid()}. */
    // @JsonIgnore keeps this validation-only accessor out of the OpenAPI schema and out of
    // serialization: it is a rule, not a field, and no client should send or read it.
    @JsonIgnore
    @AssertTrue(message = "error.stay.invalid_dates")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || dateTo.isAfter(dateFrom);
    }
}
