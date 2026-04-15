package com.bedok.stay.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record BulkCheckoutRequest(
        @NotEmpty List<UUID> stayIds
) {}
