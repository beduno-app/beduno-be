package com.beduno.stay.constraint.impl;

import com.beduno.stay.StayDates;
import com.beduno.stay.StayRepository;
import com.beduno.stay.StayStatus;
import com.beduno.stay.constraint.ConstraintContext;
import com.beduno.stay.constraint.HardViolation;
import com.beduno.stay.constraint.SoftViolation;
import com.beduno.stay.constraint.StayConstraint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Replaces CapacityConstraint's room-level headcount with a per-bed occupied check, structurally
 * identical to DoubleBookingConstraint's worker-overlap check. Every write path resolves a real
 * bed before evaluating; ctx.bed() is null only in unit tests that exercise other constraints,
 * and the check is skipped there.
 */
@Component
@RequiredArgsConstructor
public class BedOccupancyConstraint implements StayConstraint {

    private static final List<StayStatus> OCCUPYING_STATUSES = List.of(
            StayStatus.PLANNED, StayStatus.EXPECTED_TODAY, StayStatus.CHECKED_IN
    );

    private final StayRepository stayRepository;
    private final Clock clock;

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        var bed = ctx.bed();
        if (bed == null) {
            return;
        }

        var agencyId = bed.getAgencyId();
        var effectiveDateTo = StayDates.effectiveEnd(ctx.dateTo());
        var today = LocalDate.now(clock);

        long occupied = ctx.excludeStayId() != null
                ? stayRepository.countActiveStaysInBedExcluding(
                        bed.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, today, OCCUPYING_STATUSES, ctx.excludeStayId())
                : stayRepository.countActiveStaysInBed(
                        bed.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, today, OCCUPYING_STATUSES);

        if (occupied > 0) {
            hard.add(new HardViolation(
                    "BED_OCCUPIED",
                    "constraint.bed.occupied",
                    Map.of("bedLabel", bed.getLabel(), "roomNumber", ctx.room().getRoomNumber())
            ));
        }
    }
}
