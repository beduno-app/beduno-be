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

@Component
@RequiredArgsConstructor
public class DoubleBookingConstraint implements StayConstraint {

    private static final List<StayStatus> ACTIVE_STATUSES = List.of(
            StayStatus.PLANNED, StayStatus.EXPECTED_TODAY, StayStatus.CHECKED_IN
    );

    private final StayRepository stayRepository;
    private final Clock clock;

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        var worker = ctx.worker();
        var agencyId = worker.getAgencyId();
        var effectiveDateTo = StayDates.effectiveEnd(ctx.dateTo());
        var today = LocalDate.now(clock);

        long overlapping = ctx.excludeStayId() != null
                ? stayRepository.countOverlappingStaysForWorkerExcluding(
                        worker.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, today, ACTIVE_STATUSES, ctx.excludeStayId())
                : stayRepository.countOverlappingStaysForWorker(
                        worker.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, today, ACTIVE_STATUSES);

        if (overlapping > 0) {
            hard.add(new HardViolation(
                    "DOUBLE_BOOKING",
                    "constraint.worker.double_booking",
                    Map.of("workerName", worker.getFirstName() + " " + worker.getLastName())
            ));
        }
    }
}
