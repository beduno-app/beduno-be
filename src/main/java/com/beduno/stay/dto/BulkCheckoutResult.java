package com.beduno.stay.dto;

import java.util.List;
import java.util.UUID;

public record BulkCheckoutResult(
        int checkedOut,
        int errors,
        List<CheckoutResult> results
) {
    public record CheckoutResult(
            UUID stayId,
            String status,
            String errorCode
    ) {}
}
