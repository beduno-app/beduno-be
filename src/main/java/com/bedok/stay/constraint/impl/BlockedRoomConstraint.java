package com.bedok.stay.constraint.impl;

import com.bedok.property.PropertyStatus;
import com.bedok.room.RoomStatus;
import com.bedok.stay.constraint.ConstraintContext;
import com.bedok.stay.constraint.HardViolation;
import com.bedok.stay.constraint.SoftViolation;
import com.bedok.stay.constraint.StayConstraint;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BlockedRoomConstraint implements StayConstraint {

    @Override
    public void evaluate(ConstraintContext ctx, List<HardViolation> hard, List<SoftViolation> soft) {
        if (ctx.room().getStatus() == RoomStatus.BLOCKED) {
            hard.add(new HardViolation(
                    "ROOM_BLOCKED",
                    "constraint.room.blocked",
                    Map.of("roomName", ctx.room().getName())
            ));
        }

        if (ctx.property().getStatus() == PropertyStatus.INACTIVE) {
            hard.add(new HardViolation(
                    "PROPERTY_INACTIVE",
                    "constraint.property.inactive",
                    Map.of("propertyName", ctx.property().getName())
            ));
        }
    }
}
