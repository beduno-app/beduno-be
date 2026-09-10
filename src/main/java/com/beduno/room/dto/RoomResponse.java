package com.beduno.room.dto;

import com.beduno.room.GenderRule;
import com.beduno.room.RoomStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoomResponse(
        UUID id,
        UUID propertyId,
        String roomNumber,
        Integer floor,
        int capacity,
        int blockedSpots,
        int availableSpots,
        GenderRule genderRule,
        RoomStatus status,
        String notes,
        int currentOccupancy,
        List<RoomOccupant> occupants,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * The mapper builds everything the room row itself knows; occupancy comes from stays and is
     * attached here so a single query can serve a whole page of rooms rather than one per room.
     *
     * <p>availableSpots is recomputed rather than carried through, because the API means genuinely
     * free beds -- capacity less blocked less occupied -- while {@code Room.availableSpots()} means
     * physical capacity less blocked and cannot know about stays. The client uses this field as its
     * placement gate, so leaving occupancy out of it showed a full room as having space and invited
     * a placement the server then refused. Server-side enforcement was never affected: the capacity
     * constraint does its own count, over PLANNED and EXPECTED_TODAY as well as CHECKED_IN.
     */
    public RoomResponse withOccupancy(int occupancy, List<RoomOccupant> people) {
        return new RoomResponse(id, propertyId, roomNumber, floor, capacity, blockedSpots,
                Math.max(0, capacity - blockedSpots - occupancy), genderRule, status, notes,
                occupancy, people, createdAt, updatedAt);
    }
}
