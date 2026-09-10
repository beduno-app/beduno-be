package com.beduno.stay.constraint.impl;

import com.beduno.stay.StayRepository;
import com.beduno.stay.StayStatus;
import com.beduno.stay.constraint.ConstraintContext;
import com.beduno.stay.constraint.HardViolation;
import com.beduno.stay.constraint.SoftViolation;
import com.beduno.stay.constraint.StayConstraint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Replaces CapacityConstraint's room-level headcount with a per-bed occupied check, structurally
 * identical to DoubleBookingConstraint's worker-overlap check. A no-op when ctx.bed() is null --
 * no write path resolves a real bed until phase 4, see ConstraintContext's Javadoc.
 */
@Component
@RequiredArgsConstructor
public class BedOccupancyConstraint implements StayConstraint {

    private static final List<StayStatus> OCCUPYING_STATUSES = List.of(
            StayStatus.PLANNED, StayStatus.EXPECTED_TODAY, StayStatus.CHECKED_IN
    );

    private final StayRepository stayRepository;

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        var bed = ctx.bed();
        if (bed == null) {
            return;
        }

        var effectiveDateTo = ctx.dateTo() != null ? ctx.dateTo() : LocalDate.MAX;
        var agencyId = bed.getAgencyId();

        long occupied = ctx.excludeStayId() != null
                ? stayRepository.countActiveStaysInBedExcluding(
                        bed.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, OCCUPYING_STATUSES, ctx.excludeStayId())
                : stayRepository.countActiveStaysInBed(
                        bed.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, OCCUPYING_STATUSES);

        if (occupied > 0) {
            hard.add(new HardViolation(
                    "BED_OCCUPIED",
                    "constraint.bed.occupied",
                    Map.of("bedLabel", bed.getLabel(), "roomNumber", ctx.room().getRoomNumber())
            ));
        }
    }
}
