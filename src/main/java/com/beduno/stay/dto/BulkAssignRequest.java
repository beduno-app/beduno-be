package com.beduno.stay.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BulkAssignRequest(
        // One transaction, one audit row and several queries per assignment: an unbounded list
        // is a way for any planner to pin the single instance.
        @NotEmpty @Size(max = MAX_ASSIGNMENTS) @Valid List<Assignment> assignments
) {

    public static final int MAX_ASSIGNMENTS = 500;

    public record Assignment(
            @NotNull UUID workerId,
            @NotNull UUID propertyId,
            @NotNull UUID roomId,
            UUID bedId,
            @NotNull LocalDate dateFrom,
            LocalDate dateTo,
            String overrideReason
    ) {

        /** See {@link CreateStayRequest#isDateRangeValid()}. */
        @JsonIgnore
        @AssertTrue(message = "error.stay.invalid_dates")
        public boolean isDateRangeValid() {
            return dateFrom == null || dateTo == null || dateTo.isAfter(dateFrom);
        }
    }
}
