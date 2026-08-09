package com.beduno.stay.constraint;

import com.beduno.property.Property;
import com.beduno.room.Room;
import com.beduno.worker.Worker;

import java.time.LocalDate;
import java.util.UUID;

public record ConstraintContext(
        Worker worker,
        Room room,
        Property property,
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID excludeStayId
) {
}
