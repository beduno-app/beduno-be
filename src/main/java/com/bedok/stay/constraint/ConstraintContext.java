package com.bedok.stay.constraint;

import com.bedok.property.Property;
import com.bedok.room.Room;
import com.bedok.worker.Worker;

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
