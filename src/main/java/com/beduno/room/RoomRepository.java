package com.beduno.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    Page<Room> findAllByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId, Pageable pageable);

    List<Room> findAllByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId);

    Optional<Room> findByIdAndAgencyId(UUID id, UUID agencyId);

    Optional<Room> findByIdAndAgencyIdAndPropertyId(UUID id, UUID agencyId, UUID propertyId);

    boolean existsByPropertyIdAndRoomNumber(UUID propertyId, String roomNumber);

    long countByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId);
}
