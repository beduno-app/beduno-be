package com.beduno.stay.constraint;

import com.beduno.bed.Bed;
import com.beduno.property.Property;
import com.beduno.room.Room;
import com.beduno.worker.Worker;

import java.time.LocalDate;
import java.util.UUID;

/**
 * bed is null on every write path until phase 4 wires bed resolution into StayService -- until
 * then, BedOccupancyConstraint and BlockedRoomConstraint's bed-status check both treat a null bed
 * as a no-op rather than a violation. See the named-beds plan's Critical Implementation Details.
 */
public record ConstraintContext(
        Worker worker,
        Room room,
        Property property,
        LocalDate dateFrom,
        LocalDate dateTo,
        UUID excludeStayId,
        Bed bed
) {
}
