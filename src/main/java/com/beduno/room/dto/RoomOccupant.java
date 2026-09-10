package com.beduno.room.dto;

import com.beduno.stay.StayStatus;
import com.beduno.worker.Gender;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Who is in a room right now, as the room card renders it.
 *
 * <p>The worker is nested rather than flattened because the card shows a person, not a set of
 * loose fields, and because a flat shape invites callers to read firstName without noticing which
 * of several ids it belongs to.
 */
public record RoomOccupant(
        UUID stayId,
        OccupantWorker worker,
        LocalDate dateFrom,
        LocalDate dateTo,
        StayStatus status
) {

    public record OccupantWorker(
            UUID id,
            String internalId,
            String firstName,
            String lastName,
            Gender gender
    ) {}
}
