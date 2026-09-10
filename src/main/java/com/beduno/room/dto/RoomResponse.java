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
     */
    public RoomResponse withOccupancy(int occupancy, List<RoomOccupant> people) {
        return new RoomResponse(id, propertyId, roomNumber, floor, capacity, blockedSpots,
                availableSpots, genderRule, status, notes, occupancy, people,
                createdAt, updatedAt);
    }
}
