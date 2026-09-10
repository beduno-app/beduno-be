package com.beduno.bed;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BedRepository extends JpaRepository<Bed, UUID> {

    List<Bed> findAllByAgencyIdAndRoomId(UUID agencyId, UUID roomId);

    List<Bed> findAllByAgencyIdAndRoomIdIn(UUID agencyId, Collection<UUID> roomIds);

    Optional<Bed> findByIdAndAgencyId(UUID id, UUID agencyId);

    Optional<Bed> findByIdAndAgencyIdAndRoomId(UUID id, UUID agencyId, UUID roomId);

    boolean existsByRoomIdAndLabel(UUID roomId, String label);

    long countByAgencyIdAndRoomId(UUID agencyId, UUID roomId);
}
