package com.beduno.property;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    @Query(value = """
            SELECT * FROM properties p
            WHERE p.agency_id = :agencyId
            AND (CAST(:status AS VARCHAR) IS NULL OR p.status = CAST(:status AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(p.city) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            countQuery = """
            SELECT COUNT(*) FROM properties p
            WHERE p.agency_id = :agencyId
            AND (CAST(:status AS VARCHAR) IS NULL OR p.status = CAST(:status AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(p.city) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            nativeQuery = true)
    Page<Property> findAllByAgencyIdWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);

    Optional<Property> findByIdAndAgencyId(UUID id, UUID agencyId);
}
