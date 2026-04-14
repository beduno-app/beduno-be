package com.bedok.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Page<Room> findAllByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId, Pageable pageable);

    Optional<Room> findByIdAndAgencyIdAndPropertyId(UUID id, UUID agencyId, UUID propertyId);

    boolean existsByPropertyIdAndName(UUID propertyId, String name);
}
