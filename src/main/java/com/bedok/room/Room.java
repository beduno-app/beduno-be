package com.bedok.room;

import com.bedok.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
public class Room extends BaseEntity {

    @Column(name = "agency_id", nullable = false)
    private UUID agencyId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(nullable = false)
    private String name;

    @Column
    private String floor;

    @Column(nullable = false)
    private int capacity = 1;

    @Column(name = "blocked_spots", nullable = false)
    private int blockedSpots = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender_rule", nullable = false)
    private GenderRule genderRule = GenderRule.ANY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status = RoomStatus.ACTIVE;

    @Column
    private String notes;

    public int availableSpots() {
        return capacity - blockedSpots;
    }
}
