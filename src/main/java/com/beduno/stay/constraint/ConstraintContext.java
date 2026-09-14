package com.beduno.stay.constraint;

import com.beduno.bed.Bed;
import com.beduno.property.Property;
import com.beduno.room.Room;
import com.beduno.worker.Worker;

import java.time.LocalDate;
import java.util.UUID;

/**
 * bed is non-null on every write path: StayService resolves one before evaluating, and
 * {@code stays.bed_id} is NOT NULL since V14. The two bed-aware constraints still tolerate a null
 * bed by skipping their check, which is what unit tests exercising the other constraints rely on
 * -- but it is a tolerance, not a transition state, so do not add a path that depends on it.
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
