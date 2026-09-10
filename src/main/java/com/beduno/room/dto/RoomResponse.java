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
        int bedCount,
        int availableBedCount,
        GenderRule genderRule,
        RoomStatus status,
        String notes,
        int currentOccupancy,
        List<RoomOccupant> occupants,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * The mapper builds everything the room row itself knows; bed and occupancy counts come from
     * beds and stays and are attached here so a single query can serve a whole page of rooms
     * rather than one per room.
     *
     * <p>availableBedCount is ACTIVE beds less current occupancy -- the live vacancy the client
     * uses as its placement gate. This used to be two different numbers under confusingly similar
     * names ({@code Room.availableSpots()}, a static capacity-minus-blocked int, and this method's
     * own occupancy-aware recomputation); now that beds are the only source of truth, there is only
     * one. Server-side enforcement was never affected by that confusion: the constraint engine does
     * its own count.
     */
    public RoomResponse withOccupancy(int totalBeds, int activeBeds, int occupancy, List<RoomOccupant> people) {
        return new RoomResponse(id, propertyId, roomNumber, floor, totalBeds,
                Math.max(0, activeBeds - occupancy), genderRule, status, notes,
                occupancy, people, createdAt, updatedAt);
    }
}
