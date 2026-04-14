package com.bedok.worker;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    @Query("""
            SELECT w FROM Worker w
            WHERE w.agencyId = :agencyId
            AND w.status <> com.bedok.worker.WorkerStatus.DELETED
            AND (:status IS NULL OR w.status = :status)
            AND (:gender IS NULL OR w.gender = :gender)
            AND (:search IS NULL OR LOWER(w.firstName) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.lastName) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(w.internalId) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Worker> findAllByAgencyIdWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("status") WorkerStatus status,
            @Param("gender") Gender gender,
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

    boolean existsByAgencyIdAndInternalId(UUID agencyId, String internalId);
}
