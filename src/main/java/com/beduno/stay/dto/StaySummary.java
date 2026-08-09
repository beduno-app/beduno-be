package com.beduno.stay.dto;

import com.beduno.stay.StayStatus;

import java.time.LocalDate;
import java.util.UUID;

public record StaySummary(
        UUID id,
        UUID workerId,
        UUID roomId,
        LocalDate dateFrom,
        LocalDate dateTo,
        StayStatus status
) {
}
