package com.bedok.property;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    @Query("""
            SELECT p FROM Property p
            WHERE p.agencyId = :agencyId
            AND (:status IS NULL OR p.status = :status)
            AND (:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(p.city) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Property> findAllByAgencyIdWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("status") PropertyStatus status,
            @Param("search") String search,
            Pageable pageable);

    Optional<Property> findByIdAndAgencyId(UUID id, UUID agencyId);
}
