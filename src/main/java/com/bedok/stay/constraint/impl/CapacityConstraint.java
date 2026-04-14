package com.bedok.stay.constraint.impl;

import com.bedok.stay.StayRepository;
import com.bedok.stay.StayStatus;
import com.bedok.stay.constraint.ConstraintContext;
import com.bedok.stay.constraint.HardViolation;
import com.bedok.stay.constraint.SoftViolation;
import com.bedok.stay.constraint.StayConstraint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CapacityConstraint implements StayConstraint {

    private static final List<StayStatus> OCCUPYING_STATUSES = List.of(
            StayStatus.PLANNED, StayStatus.EXPECTED_TODAY, StayStatus.CHECKED_IN
    );

    private final StayRepository stayRepository;

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        var room = ctx.room();
        var available = room.availableSpots();
        if (available <= 0) {
            hard.add(new HardViolation(
                    "CAPACITY_EXCEEDED",
                    "constraint.room.capacity.full",
                    Map.of("roomName", room.getName(), "capacity", room.getCapacity(), "blocked", room.getBlockedSpots())
            ));
            return;
        }

        var effectiveDateTo = ctx.dateTo() != null ? ctx.dateTo() : LocalDate.MAX;
        var agencyId = room.getAgencyId();

        long occupied = ctx.excludeStayId() != null
                ? stayRepository.countActiveStaysInRoomExcluding(
                        room.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, OCCUPYING_STATUSES, ctx.excludeStayId())
                : stayRepository.countActiveStaysInRoom(
                        room.getId(), agencyId, ctx.dateFrom(), effectiveDateTo, OCCUPYING_STATUSES);

        if (occupied >= available) {
            hard.add(new HardViolation(
                    "CAPACITY_EXCEEDED",
                    "constraint.room.capacity.exceeded",
                    Map.of("roomName", room.getName(), "capacity", room.getCapacity(), "occupied", occupied)
            ));
        }
    }
}
