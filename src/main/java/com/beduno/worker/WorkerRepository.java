package com.beduno.worker;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    @Query(value = """
            SELECT * FROM workers w
            WHERE w.agency_id = :agencyId
            AND w.status <> 'DELETED'
            AND (CAST(:status AS VARCHAR) IS NULL OR w.status = CAST(:status AS VARCHAR))
            AND (CAST(:gender AS VARCHAR) IS NULL OR w.gender = CAST(:gender AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(w.first_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(w.last_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(w.internal_id) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            countQuery = """
            SELECT COUNT(*) FROM workers w
            WHERE w.agency_id = :agencyId
            AND w.status <> 'DELETED'
            AND (CAST(:status AS VARCHAR) IS NULL OR w.status = CAST(:status AS VARCHAR))
            AND (CAST(:gender AS VARCHAR) IS NULL OR w.gender = CAST(:gender AS VARCHAR))
            AND (CAST(:search AS VARCHAR) IS NULL
                 OR LOWER(w.first_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(w.last_name) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%'))
                 OR LOWER(w.internal_id) LIKE LOWER(CONCAT('%', CAST(:search AS VARCHAR), '%')))
            """,
            nativeQuery = true)
    Page<Worker> findAllByAgencyIdWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("status") String status,
            @Param("gender") String gender,
            @Param("search") String search,
            Pageable pageable);

    @Query(value = """
            SELECT * FROM workers w
            WHERE w.agency_id = :agencyId
            AND w.status <> 'DELETED'
            AND :tag = ANY(w.tags)
            AND (:status IS NULL OR w.status = CAST(:status AS VARCHAR))
            AND (:gender IS NULL OR w.gender = CAST(:gender AS VARCHAR))
            AND (:search IS NULL OR LOWER(w.first_name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.last_name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.internal_id) LIKE LOWER(CONCAT('%', :search, '%')))
            """,
            countQuery = """
            SELECT COUNT(*) FROM workers w
            WHERE w.agency_id = :agencyId
            AND w.status <> 'DELETED'
            AND :tag = ANY(w.tags)
            AND (:status IS NULL OR w.status = CAST(:status AS VARCHAR))
            AND (:gender IS NULL OR w.gender = CAST(:gender AS VARCHAR))
            AND (:search IS NULL OR LOWER(w.first_name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.last_name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.internal_id) LIKE LOWER(CONCAT('%', :search, '%')))
            """,
            nativeQuery = true)
    Page<Worker> findAllByAgencyIdWithFiltersAndTag(
            @Param("agencyId") UUID agencyId,
            @Param("status") String status,
            @Param("gender") String gender,
            @Param("search") String search,
            @Param("tag") String tag,
            Pageable pageable);

    Optional<Worker> findByIdAndAgencyIdAndStatusNot(UUID id, UUID agencyId, WorkerStatus status);

    /**
     * Agency-filtered deliberately: the ids come from a stay query, and reaching for findAllById
     * here would be a cross-tenant read that the tenancy rule would then have to carve out.
     */
    List<Worker> findAllByAgencyIdAndIdIn(UUID agencyId, Collection<UUID> ids);

    boolean existsByAgencyIdAndInternalId(UUID agencyId, String internalId);
}
