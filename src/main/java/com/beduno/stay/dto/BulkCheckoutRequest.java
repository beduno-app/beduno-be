package com.beduno.stay.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BulkCheckoutRequest(
        // Bounded for the same reason as BulkAssignRequest.assignments: one transaction, one
        // audit row per stay.
        @NotEmpty @Size(max = MAX_STAYS) List<UUID> stayIds
) {

    public static final int MAX_STAYS = 500;
}
