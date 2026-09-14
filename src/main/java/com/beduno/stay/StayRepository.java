package com.beduno.stay;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StayRepository extends JpaRepository<Stay, UUID> {

    Optional<Stay> findByIdAndAgencyId(UUID id, UUID agencyId);

    /**
     * Counts every stay referencing a property, terminal ones included. Deleting a
     * property is blocked while any row references it, because stays.property_id is
     * a RESTRICT foreign key and the delete would otherwise fail at the database.
     */
    long countByAgencyIdAndPropertyId(UUID agencyId, UUID propertyId);

    /** Counts every stay referencing a room, for the same reason as above. */
    long countByAgencyIdAndRoomId(UUID agencyId, UUID roomId);

    /** Counts every stay referencing a bed, for the same reason as above -- stays.bed_id is a RESTRICT foreign key. */
    long countByAgencyIdAndBedId(UUID agencyId, UUID bedId);

    @Query(value = """
            SELECT * FROM stays s
            WHERE s.agency_id = :agencyId
            AND (CAST(:workerId AS uuid) IS NULL OR s.worker_id = CAST(:workerId AS uuid))
            AND (CAST(:propertyId AS uuid) IS NULL OR s.property_id = CAST(:propertyId AS uuid))
            AND (CAST(:status AS VARCHAR) IS NULL OR s.status = CAST(:status AS VARCHAR))
            AND (CAST(:dateFrom AS DATE) IS NULL OR s.date_to IS NULL OR s.date_to >= CAST(:dateFrom AS DATE))
            AND (CAST(:dateTo AS DATE) IS NULL OR s.date_from <= CAST(:dateTo AS DATE))
            """,
            countQuery = """
            SELECT COUNT(*) FROM stays s
            WHERE s.agency_id = :agencyId
            AND (CAST(:workerId AS uuid) IS NULL OR s.worker_id = CAST(:workerId AS uuid))
            AND (CAST(:propertyId AS uuid) IS NULL OR s.property_id = CAST(:propertyId AS uuid))
            AND (CAST(:status AS VARCHAR) IS NULL OR s.status = CAST(:status AS VARCHAR))
            AND (CAST(:dateFrom AS DATE) IS NULL OR s.date_to IS NULL OR s.date_to >= CAST(:dateFrom AS DATE))
            AND (CAST(:dateTo AS DATE) IS NULL OR s.date_from <= CAST(:dateTo AS DATE))
            """,
            nativeQuery = true)
    Page<Stay> findAllWithFilters(
            @Param("agencyId") UUID agencyId,
            @Param("workerId") UUID workerId,
            @Param("propertyId") UUID propertyId,
            @Param("status") String status,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable
    );

    /**
     * Half-open overlap between the candidate period [dateFrom, effectiveDateTo) and an existing
     * stay. Callers must pass {@link StayDates#effectiveEnd(LocalDate)} rather than the raw
     * dateTo, so that an open-ended candidate is represented by a date PostgreSQL can actually
     * bind. A null-aware predicate is not an option here: HQL renders {@code :p IS NULL} as a
     * bare {@code ? IS NULL}, which PostgreSQL rejects with "could not determine data type".
     */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.bedId = :bedId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom)
            """)
    long countActiveStaysInBed(
            @Param("bedId") UUID bedId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("statuses") List<StayStatus> statuses
    );

    /** As {@link #countActiveStaysInBed}, ignoring the stay being updated. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.bedId = :bedId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom)
              AND s.id <> :excludeId
            """)
    long countActiveStaysInBedExcluding(
            @Param("bedId") UUID bedId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("statuses") List<StayStatus> statuses,
            @Param("excludeId") UUID excludeId
    );

    @Query("SELECT s FROM Stay s WHERE s.status = com.beduno.stay.StayStatus.PLANNED AND s.dateFrom = :date")
    List<Stay> findPlannedArrivingOn(@Param("date") LocalDate date);

    @Query("""
            SELECT s FROM Stay s
            WHERE s.agencyId = :agencyId
              AND s.propertyId = :propertyId
              AND s.status IN :statuses
              AND s.dateFrom <= :date
              AND (s.dateTo IS NULL OR s.dateTo > :date)
            """)
    List<Stay> findActiveStaysForPropertyOnDate(
            @Param("agencyId") UUID agencyId,
            @Param("propertyId") UUID propertyId,
            @Param("date") LocalDate date,
            @Param("statuses") List<StayStatus> statuses
    );

    @Query("""
            SELECT s FROM Stay s
            WHERE s.agencyId = :agencyId
              AND s.propertyId = :propertyId
              AND s.status = com.beduno.stay.StayStatus.EXPECTED_TODAY
              AND s.dateFrom = :date
            """)
    List<Stay> findArrivals(
            @Param("agencyId") UUID agencyId,
            @Param("propertyId") UUID propertyId,
            @Param("date") LocalDate date
    );

    /** Same half-open overlap semantics as {@link #countActiveStaysInBed}, keyed on the worker. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.workerId = :workerId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom)
            """)
    long countOverlappingStaysForWorker(
            @Param("workerId") UUID workerId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("statuses") List<StayStatus> statuses
    );

    /** As {@link #countOverlappingStaysForWorker}, ignoring the stay being updated. */
    @Query("""
            SELECT COUNT(s) FROM Stay s
            WHERE s.workerId = :workerId
              AND s.agencyId = :agencyId
              AND s.status IN :statuses
              AND s.dateFrom < :effectiveDateTo
              AND (s.dateTo IS NULL OR s.dateTo > :dateFrom)
              AND s.id <> :excludeId
            """)
    long countOverlappingStaysForWorkerExcluding(
            @Param("workerId") UUID workerId,
            @Param("agencyId") UUID agencyId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("effectiveDateTo") LocalDate effectiveDateTo,
            @Param("statuses") List<StayStatus> statuses,
            @Param("excludeId") UUID excludeId
    );
}
